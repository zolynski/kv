package de.zolynski.kv.core.store;

/// A snapshot of one store's occupancy. Surfaced by the router's `/topology` endpoint so the
/// UI can show per-shard fill, which is what makes the sharding visible in a demo.
public record StoreStats(
        String implementation,
        int entries,
        int maxEntries,
        int segments,
        int capacity
) {
}
