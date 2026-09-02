package de.zolynski.kv.core.partition;

/// Decides which shard owns a key.
public interface KeyPartitioner {

    /// Returns which shard owns the given key.
    ///
    /// Can be extended to a set in the future, if keys with wildcards (e.g. user:*) are planned.
    int shardFor(String key);

    /// The number of shards in the cluster.
    int shardCount();
}
