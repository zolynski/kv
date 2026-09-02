package de.zolynski.kv.core.store;

/// Capacity bound for a store, in entries. [EntryRules] caps a single value at 100 KiB, so the
/// two together give the worst case a heap has to survive.
public record StoreConfig(int maxEntries) {

  public StoreConfig {
    if (maxEntries < 1) {
      throw new IllegalArgumentException("maxEntries must be >= 1, was " + maxEntries);
    }
  }

  public static StoreConfig unbounded() {
    return new StoreConfig(Integer.MAX_VALUE);
  }
}
