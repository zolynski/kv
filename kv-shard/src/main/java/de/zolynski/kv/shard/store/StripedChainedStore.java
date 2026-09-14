package de.zolynski.kv.shard.store;

import de.zolynski.kv.core.store.CapacityExceededException;
import de.zolynski.kv.core.store.EntryRules;
import de.zolynski.kv.core.store.Store;
import de.zolynski.kv.core.store.StoreConfig;
import de.zolynski.kv.core.store.StoreStats;
import de.zolynski.kv.core.hash.KeyHash;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;

/// A chained hash table with striped locks.
///
/// A bucket array whose entries are linked nodes, cut into independently locked stripes.
///
/// Readers take no lock: GET walks the chain optimistically and then validates its stamp,
/// falling back to a real read lock only if a writer touched the stripe meanwhile.
public class StripedChainedStore implements Store {

  private static final int DEFAULT_CAPACITY = 64;
  private static final int STRIPE_COUNT = 64; // Number of distinct locks

  /// Average entries per bucket before the table doubles.
  private static final double MAX_LOAD_FACTOR = 0.75;

  /// The actual data store, guarded by the locks below.
  private volatile Node[] table;
  private final StampedLock[] locks;
  private final StoreConfig config;

  /// Entry count, kept incrementally rather than by walking every chain.
  private final AtomicInteger entryCount = new AtomicInteger();

  static {
    // A bucket must never be reachable under two different locks: a writer mutates its chain head
    // holding only its own key's stripe lock, so the bucket has to determine the stripe. That
    // holds while STRIPE_COUNT divides the capacity, and resize only ever doubles it.
    if (DEFAULT_CAPACITY % STRIPE_COUNT != 0) {
      throw new IllegalStateException(
              "STRIPE_COUNT " + STRIPE_COUNT + " must divide DEFAULT_CAPACITY " + DEFAULT_CAPACITY);
    }
  }

  public StripedChainedStore() {
    this(StoreConfig.unbounded());
  }

  public StripedChainedStore(StoreConfig config) {
    this.config = config;
    this.table = new Node[DEFAULT_CAPACITY];
    this.locks = new StampedLock[STRIPE_COUNT];
    for (int i = 0; i < STRIPE_COUNT; i++) {
      this.locks[i] = new StampedLock();
    }
  }

  private static class Node {
    final String key;
    String value;
    Node next;

    Node(String key, String value, Node next) {
      this.key = key;
      this.value = value;
      this.next = next;
    }
  }

  /// Bucket for `key` in a table of `capacity` buckets.
  private static int bucketIndex(int hash, int capacity) {
    return Math.floorMod(hash, capacity);
  }

  /// The stripe for a hash, chosen without reference to the table.
  private StampedLock lockFor(int hash) {
    return locks[Math.floorMod(hash, STRIPE_COUNT)];
  }

  /// Acquires every write lock in ascending order, returning the stamps needed to release them.
  private long[] lockAll() {
    long[] stamps = new long[locks.length];
    for (int i = 0; i < locks.length; i++) {
      stamps[i] = locks[i].writeLock();
    }
    return stamps;
  }

  private void unlockAll(long[] stamps) {
    for (int i = locks.length - 1; i >= 0; i--) {
      locks[i].unlockWrite(stamps[i]);
    }
  }

  @Override
  public Optional<String> get(String key) {
    EntryRules.requireKeyNotNull(key);
    int hash = KeyHash.hash32(key);
    StampedLock lock = lockFor(hash);

    long stamp = lock.tryOptimisticRead();
    if (stamp != 0L) {
      Node found = findNode(table, key, hash);
      String value = found == null ? null : found.value;
      // Trustworthy only if no writer held this stripe for the whole traversal. Otherwise the
      // walk above may have crossed a half-applied write and is discarded.
      if (lock.validate(stamp)) {
        return Optional.ofNullable(value);
      }
    }

    stamp = lock.readLock();
    try {
      Node found = findNode(table, key, hash);
      return Optional.ofNullable(found == null ? null : found.value);
    } finally {
      lock.unlockRead(stamp);
    }
  }

  /// Walks one bucket's chain and returns the matching node, or null.
  private static Node findNode(Node[] table, String key, int hash) {
    for (Node node = table[bucketIndex(hash, table.length)]; node != null; node = node.next) {
      if (node.key.equals(key)) {
        return node;
      }
    }
    return null;
  }

  @Override
  public Optional<String> put(String key, String value) {
    EntryRules.validateKey(key);
    EntryRules.validateValue(value);

    int hash = KeyHash.hash32(key);
    StampedLock lock = lockFor(hash);
    long stamp = lock.writeLock();
    try {
      Node[] current = table;
      int index = bucketIndex(hash, current.length);

      Node existing = findNode(current, key, hash);
      if (existing != null) {
        // An overwrite must hand back what was there: the REST layer distinguishes a create
        // (201) from a replace (200 with the previous value) on exactly this.
        String previous = existing.value;
        existing.value = value;
        return Optional.of(previous);
      }

      // Only a genuinely new entry consumes capacity; an overwrite above never does.
      if (entryCount.get() >= config.maxEntries()) {
        throw new CapacityExceededException("entry limit reached: " + config.maxEntries());
      }
      current[index] = new Node(key, value, current[index]);
      entryCount.incrementAndGet();
    } finally {
      lock.unlockWrite(stamp);
    }

    // Deliberately outside the bucket lock: a resize needs every lock.
    resizeIfNeeded();
    return Optional.empty();
  }

  @Override
  public Optional<String> remove(String key) {
    EntryRules.requireKeyNotNull(key);
    int hash = KeyHash.hash32(key);
    StampedLock lock = lockFor(hash);
    long stamp = lock.writeLock();
    try {
      Node[] current = table;
      int index = bucketIndex(hash, current.length);
      Node head = current[index];
      Node prev = null;

      while (head != null) {
        if (head.key.equals(key)) {
          if (prev != null) {
            prev.next = head.next;
          } else {
            current[index] = head.next;
          }
          entryCount.decrementAndGet();
          return Optional.ofNullable(head.value);
        }
        prev = head;
        head = head.next;
      }
      return Optional.empty();
    } finally {
      lock.unlockWrite(stamp);
    }
  }

  @Override
  public int size() {
    return entryCount.get();
  }

  /// Doubles the bucket array once the average chain exceeds MAX_LOAD_FACTOR.
  private void resizeIfNeeded() {
    if (entryCount.get() <= table.length * MAX_LOAD_FACTOR) {
      return;
    }
    long[] stamps = lockAll();
    try {
      Node[] current = table;
      if (entryCount.get() <= current.length * MAX_LOAD_FACTOR) {
        return; // another writer already grew it
      }
      Node[] grown = new Node[current.length << 1];
      for (Node head : current) {
        for (Node node = head; node != null; node = node.next) {
          int index = bucketIndex(KeyHash.hash32(node.key), grown.length);
          grown[index] = new Node(node.key, node.value, grown[index]);
        }
      }
      table = grown; // volatile write publishes the fully built array
    } finally {
      unlockAll(stamps);
    }
  }

  @Override
  public StoreStats stats() {
    return new StoreStats(
            "StripedChainedStore", entryCount.get(),
            config.maxEntries(), STRIPE_COUNT, table.length
    );
  }

  @Override
  public List<String> keys(int limit) {
    long[] stamps = lockAll();
    try {
      List<String> keyList = new ArrayList<>(Math.clamp(limit, 0, entryCount.get()));
      for (Node head : table) {
        while (head != null) {
          if (keyList.size() >= limit) {
            return keyList;
          }
          keyList.add(head.key);
          head = head.next;
        }
      }
      return keyList;
    } finally {
      unlockAll(stamps);
    }
  }

  @Override
  public void clear() {
    long[] stamps = lockAll();
    try {
      table = new Node[DEFAULT_CAPACITY];
      entryCount.set(0);
    } finally {
      unlockAll(stamps);
    }
  }
}
