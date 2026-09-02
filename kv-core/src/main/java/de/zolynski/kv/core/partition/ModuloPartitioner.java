package de.zolynski.kv.core.partition;

import de.zolynski.kv.core.hash.KeyHash;

/// Assigns keys to shards by `hash(key) mod shardCount`.
///
/// Distributes keys uniformly as long as the hash works properly.
public record ModuloPartitioner(int shardCount) implements KeyPartitioner {

  public ModuloPartitioner {
    if (shardCount < 1) {
      throw new IllegalArgumentException("shardCount must be >= 1, was " + shardCount);
    }
  }

  @Override
  public int shardFor(String key) {
    return Math.floorMod(KeyHash.hash32(key), shardCount);
  }
}
