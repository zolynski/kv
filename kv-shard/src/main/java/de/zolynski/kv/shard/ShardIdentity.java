package de.zolynski.kv.shard;

import de.zolynski.kv.core.partition.KeyPartitioner;
import de.zolynski.kv.core.partition.ModuloPartitioner;

/// Which shard this process is, and whether a given key belongs here.
///
/// The ordinal is derived from the hostname rather than configured: a StatefulSet names pods
/// `<statefulset>-<ordinal>` and that name is stable across restarts, so injecting it separately
/// would create a second source of truth that can disagree with the first.
///
/// The ownership check exists because nothing otherwise validates that the router's idea of the
/// cluster matches reality. A router started with `SHARD_COUNT=4` against a StatefulSet of 3
/// would write keys to the wrong shards and read back 404s forever, with no error anywhere.
/// Here that becomes an immediate 421.
public final class ShardIdentity {

  private final int ordinal;
  private final int count;
  private final boolean enforceOwnership;
  private final KeyPartitioner partitioner;

  public ShardIdentity(int ordinal, int count, boolean enforceOwnership) {
    if (ordinal < 0 || ordinal >= count) {
      throw new IllegalArgumentException(
              "shard ordinal " + ordinal + " outside cluster of " + count);
    }
    this.ordinal = ordinal;
    this.count = count;
    this.enforceOwnership = enforceOwnership;
    this.partitioner = new ModuloPartitioner(count);
  }

  /// Extracts the trailing ordinal from a StatefulSet pod name, e.g. `kv-shard-2` to 2.
  ///
  /// @return the parsed ordinal, or `fallback` when the hostname carries no usable suffix
  /// (running locally, in a plain Deployment, or under docker-compose)
  public static int ordinalFromHostname(String hostname, int fallback) {
    if (hostname == null) {
      return fallback;
    }
    int dash = hostname.lastIndexOf('-');
    if (dash < 0 || dash == hostname.length() - 1) {
      return fallback;
    }
    try {
      // The substring after the final dash cannot contain one, so it cannot parse
      // negative. Out-of-range values throw and fall back, which is what we want.
      return Integer.parseInt(hostname.substring(dash + 1));
    } catch (NumberFormatException _) {
      return fallback;
    }
  }

  public boolean owns(String key) {
    return partitioner.shardFor(key) == ordinal;
  }

  /// Whether a misdirected key should be refused rather than silently stored.
  public boolean shouldReject(String key) {
    return enforceOwnership && count > 1 && !owns(key);
  }

  public int expectedOwnerOf(String key) {
    return partitioner.shardFor(key);
  }

  public int ordinal() {
    return ordinal;
  }

  public int count() {
    return count;
  }
}
