package de.zolynski.kv.shard.store;

import de.zolynski.kv.core.store.CapacityExceededException;
import de.zolynski.kv.core.store.EntryRules;
import de.zolynski.kv.core.store.InvalidEntryException;
import de.zolynski.kv.core.store.Store;
import de.zolynski.kv.core.store.StoreConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Testing the two store implementations.
abstract class StoreContractTest {

  private Store store;

  protected abstract Store newStore(StoreConfig config);

  @BeforeEach
  void setUp() {
    store = newStore(StoreConfig.unbounded());
  }

  @Test
  void putReturnsNullOnInsertAndPreviousValueOnUpdate() {
    assertTrue(store.put("k", "first").isEmpty());
    assertEquals(Optional.of("first"), store.put("k", "second"));
    assertEquals(Optional.of("second"), store.get("k"));
    assertEquals(1, store.size(), "an update must not grow the store");
  }

  @Test
  void getReturnsNullForAbsentKey() {
    store.put("present", "v");
    assertTrue(store.get("absent").isEmpty());
  }

  @Test
  void removeReturnsPreviousValueThenNull() {
    store.put("k", "v");
    assertEquals(Optional.of("v"), store.remove("k"));
    assertTrue(store.get("k").isEmpty());
    assertTrue(store.remove("k").isEmpty());
    assertEquals(0, store.size());
  }

  @Test
  void rejectsNullKeysAndValues() {
    assertAll(
            () -> assertThrows(IllegalArgumentException.class, () -> store.put(null, "v")),
            () -> assertThrows(IllegalArgumentException.class, () -> store.put("k", null)),
            () -> assertThrows(IllegalArgumentException.class, () -> store.get(null)),
            () -> assertThrows(IllegalArgumentException.class, () -> store.remove(null)));
  }

  @Test
  void handlesAwkwardKeysAndValues() {
    // Keys and values are variable length by spec; empty, unicode and at-the-limit all count.
    String empty = "";
    String unicode = "こんにちは/こんにちは:é🚀";
    String longestLegalKey = "x".repeat(EntryRules.MAX_KEY_BYTES);

    store.put(empty, "empty-key");
    store.put(unicode, "unicode");
    store.put(longestLegalKey, "long");
    store.put("empty-value", "");

    assertAll(
            () -> assertEquals(Optional.of("empty-key"), store.get(empty)),
            () -> assertEquals(Optional.of("unicode"), store.get(unicode)),
            () -> assertEquals(Optional.of("long"), store.get(longestLegalKey)),
            () -> assertEquals(Optional.of(""), store.get("empty-value")),
            () -> assertEquals(4, store.size()));
  }

  @Test
  void refusesEntriesThatBreakThePublishedRules() {
    assertAll(
            () -> assertThrows(InvalidEntryException.class,
                    () -> store.put("k".repeat(EntryRules.MAX_KEY_BYTES + 1), "v")),
            () -> assertThrows(InvalidEntryException.class, () -> store.put("a\nb", "v")),
            () -> assertThrows(InvalidEntryException.class,
                    () -> store.put("k", "v".repeat(EntryRules.MAX_VALUE_BYTES + 1))),
            () -> assertEquals(0, store.size(), "a rejected write must store nothing"));
  }

  @Test
  void survivesEnoughInsertsToForceRepeatedResizes() {
    int n = 50_000;
    for (int i = 0; i < n; i++) {
      store.put("key:" + i, "value:" + i);
    }
    assertEquals(n, store.size());
    for (int i = 0; i < n; i++) {
      assertEquals(Optional.of("value:" + i), store.get("key:" + i), "lost entry after resize: key:" + i);
    }
  }

  @Test
  void reclaimsSpaceAcrossRepeatedInsertRemoveChurn() {
    // A store must give space back. Churn that grows the table without reclaiming what was
    // removed degrades lookups until they go quadratic, and size() alone would not show it.
    for (int round = 0; round < 20; round++) {
      for (int i = 0; i < 2_000; i++) {
        store.put("churn:" + i, "round:" + round);
      }
      assertEquals(2_000, store.size());
      for (int i = 0; i < 2_000; i++) {
        assertEquals(Optional.of("round:" + round), store.remove("churn:" + i));
      }
      assertEquals(0, store.size());
    }
    store.put("after", "churn");
    assertEquals(Optional.of("churn"), store.get("after"));
    assertEquals(1, store.size());
  }

  @Test
  void deletingFromTheMiddleOfAProbeChainKeepsLaterEntriesReachable() {
    // Blanking a slot instead of tombstoning it severs the chain, stranding everything
    // inserted after the deleted entry. Dense insert then sparse delete provokes it.
    int n = 5_000;
    for (int i = 0; i < n; i++) {
      store.put("chain:" + i, "v" + i);
    }
    for (int i = 0; i < n; i += 2) {
      store.remove("chain:" + i);
    }
    for (int i = 1; i < n; i += 2) {
      assertEquals(Optional.of("v" + i), store.get("chain:" + i), "stranded key chain:" + i);
    }
    assertEquals(n / 2, store.size());
  }

  @Test
  void clearEmptiesTheStore() {
    for (int i = 0; i < 1_000; i++) {
      store.put("k" + i, "v" + i);
    }
    store.clear();
    assertEquals(0, store.size());
    assertTrue(store.get("k500").isEmpty());
    store.put("fresh", "v");
    assertEquals(Optional.of("v"), store.get("fresh"));
  }

  @Test
  void keysRespectsItsLimitAndReturnsOnlyLiveEntries() {
    for (int i = 0; i < 500; i++) {
      store.put("k" + i, "v" + i);
    }
    for (int i = 0; i < 250; i++) {
      store.remove("k" + i);
    }

    List<String> limited = store.keys(10);
    List<String> all = store.keys(Integer.MAX_VALUE);

    assertEquals(10, limited.size());
    assertEquals(250, all.size());
    assertEquals(250, new HashSet<>(all).size(), "keys() must not report duplicates");
    assertTrue(all.stream().allMatch(k -> store.get(k) != null),
            "keys() must not report removed entries");
  }

  @Test
  void rejectsWritesPastTheEntryLimitRatherThanEvicting() {
    Store bounded = newStore(new StoreConfig(1_000));

    int accepted = 0;
    try {
      for (int i = 0; i < 100_000; i++) {
        bounded.put("k" + i, "v");
        accepted++;
      }
    } catch (CapacityExceededException _) {
      // the point of the test
    }

    assertTrue(accepted < 100_000, "an unbounded store would have accepted everything");
    assertTrue(accepted > 0, "the store rejected before storing anything");
    assertNotNull(bounded.get("k0"), "rejection must not evict what was already accepted");
  }

  @Test
  void updatingAnExistingKeyIsNotBlockedByTheEntryLimit() {
    Store bounded = newStore(new StoreConfig(8));
    bounded.put("k", "v1");
    bounded.put("k", "v2");
    assertEquals(Optional.of("v2"), bounded.get("k"));
  }

  @Test
  void concurrentWritersOnDisjointKeysNeverLoseAnUpdate() throws Exception {
    // Cross-talk between stripes shows up here as a missing or wrongly-valued key.
    int threads = 8;
    int perThread = 20_000;
    runConcurrently(threads, id -> {
      for (int i = 0; i < perThread; i++) {
        store.put("t" + id + ":" + i, "owned-by-" + id);
      }
    });

    assertEquals(threads * perThread, store.size());
    for (int id = 0; id < threads; id++) {
      for (int i = 0; i < perThread; i += 500) {
        assertEquals(Optional.of("owned-by-" + id), store.get("t" + id + ":" + i));
      }
    }
  }

  @Test
  void readersNeverObserveAValueThatWasNeverWritten() throws Exception {
    Set<String> legalValues = new HashSet<>();
    for (int i = 0; i < 16; i++) {
      legalValues.add("value-" + i);
    }
    List<String> keys = new ArrayList<>();
    for (int i = 0; i < 64; i++) {
      keys.add("hot:" + i);
    }

    int writers = 4;
    AtomicInteger activeWriters = new AtomicInteger(writers);
    AtomicReference<String> illegal = new AtomicReference<>();

    runConcurrently(writers + 4, id -> {
      if (id < writers) {
        try {
          for (int round = 0; round < 50_000; round++) {
            String key = keys.get(round % keys.size());
            if (round % 7 == 0) {
              store.remove(key);
            } else {
              store.put(key, "value-" + (round % 16));
            }
          }
        } finally {
          activeWriters.decrementAndGet();
        }
      } else {
        // Readers spin only while writes are still in flight, so the race window is
        // exactly the interesting one and the test cannot hang.
        while (activeWriters.get() > 0 && illegal.get() == null) {
          for (String key : keys) {
            Optional<String> observed = store.get(key);
            if (observed.isPresent() && !legalValues.contains(observed.get())) {
              illegal.set(observed.get());
              return;
            }
          }
        }
      }
    });

    assertNull(illegal.get(), () -> "reader observed torn value: " + illegal.get());
  }

  @Test
  void writesArePubliclyVisibleOnceTheWriterCompletes() throws Exception {
    int threads = 6;
    runConcurrently(threads, id -> {
      for (int i = 0; i < 5_000; i++) {
        String key = "shared:" + i;
        store.put(key, "written");
        assertEquals(Optional.of("written"), store.get(key), "writer could not read its own write");
      }
    });
    for (int i = 0; i < 5_000; i++) {
      assertEquals(Optional.of("written"), store.get("shared:" + i));
    }
  }

  ///  Runs `body` on `threads` threads released simultaneously, failing on any error.
  private static void runConcurrently(int threads, ThreadBody body) throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    List<AssertionError> failures = Collections.synchronizedList(new ArrayList<>());
    AtomicReference<Throwable> unexpected = new AtomicReference<>();

    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch done = new CountDownLatch(threads);
      for (int id = 0; id < threads; id++) {
        int threadId = id;
        pool.submit(() -> {
          try {
            start.await();
            body.run(threadId);
          } catch (AssertionError e) {
            failures.add(e);
          } catch (Throwable t) {
            unexpected.compareAndSet(null, t);
          } finally {
            done.countDown();
          }
        });
      }
      start.countDown();
      assertTrue(done.await(120, TimeUnit.SECONDS), "concurrent phase timed out");
    }

    if (unexpected.get() != null) {
      throw new AssertionError("worker threw", unexpected.get());
    }
    assertTrue(failures.isEmpty(), () -> "worker assertion failed: " + failures.getFirst());
  }

  @FunctionalInterface
  private interface ThreadBody {
    void run(int threadId) throws Exception;
  }

  @Test
  void theEntryRulesAreEnforcedOnWriteButNotOnRead() {
    String tooLong = "k".repeat(EntryRules.MAX_KEY_BYTES + 1);
    assertThrows(InvalidEntryException.class, () -> store.put(tooLong, "v"));
    assertThrows(InvalidEntryException.class, () -> store.put("a\nb", "v"));
    assertEquals(0, store.size(), "a rejected write must store nothing");

    assertDoesNotThrow(() -> store.get(tooLong));
    assertDoesNotThrow(() -> store.remove(tooLong));
  }
}
