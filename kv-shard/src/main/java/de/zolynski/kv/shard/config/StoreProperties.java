package de.zolynski.kv.shard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/// @param implementation which `Store` implementation to use
/// @param maxEntries     total entries admitted before writes are rejected with 507
@ConfigurationProperties("kv.store")
public record StoreProperties(
        Implementation implementation,
        int maxEntries) {

  public enum Implementation {
    CHAINED,
    CONCURRENT_HASH_MAP
  }
}
