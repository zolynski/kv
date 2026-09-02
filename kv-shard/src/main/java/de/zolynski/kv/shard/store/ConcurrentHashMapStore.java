package de.zolynski.kv.shard.store;

import de.zolynski.kv.core.store.CapacityExceededException;
import de.zolynski.kv.core.store.Store;
import de.zolynski.kv.core.store.StoreConfig;
import de.zolynski.kv.core.store.StoreStats;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static de.zolynski.kv.core.store.EntryRules.requireKeyNotNull;
import static de.zolynski.kv.core.store.EntryRules.validateKey;
import static de.zolynski.kv.core.store.EntryRules.validateValue;

/// Baseline for store backed by the JDK's ConcurrentHashMap.
public final class ConcurrentHashMapStore implements Store {

  private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();
  private final StoreConfig config;

  public ConcurrentHashMapStore(StoreConfig config) {
    this.config = config;
  }

  @Override
  public Optional<String> put(String key, String value) {
    validateKey(key);
    validateValue(value);
    // Check for capacity iff this is a new entry.
    if (!store.containsKey(key) && store.size() >= config.maxEntries()) {
      throw new CapacityExceededException("entry limit reached: " + config.maxEntries());
    }
    return Optional.ofNullable(store.put(key, value));
  }

  @Override
  public Optional<String> get(String key) {
    requireKeyNotNull(key);
    return Optional.ofNullable(store.get(key));
  }

  @Override
  public Optional<String> remove(String key) {
    requireKeyNotNull(key);
    return Optional.ofNullable(store.remove(key));
  }

  @Override
  public int size() {
    return store.size();
  }

  @Override
  public List<String> keys(int limit) {
    List<String> out = new ArrayList<>(Math.clamp(limit, 0, store.size()));
    for (Map.Entry<String, String> e : store.entrySet()) {
      if (out.size() >= limit) {
        break;
      }
      out.add(e.getKey());
    }
    return out;
  }

  @Override
  public StoreStats stats() {
    return new StoreStats("ConcurrentHashMapStore", store.size(), config.maxEntries(), -1, -1);
  }

  @Override
  public void clear() {
    store.clear();
  }
}
