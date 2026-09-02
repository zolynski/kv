package de.zolynski.kv.shard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/// @param ordinal          this shard's index; -1 means "derive it from the pod hostname"
/// @param count            total shards in the cluster, fixed at startup
/// @param enforceOwnership reject keys this shard should not own with 421 instead of storing them
@ConfigurationProperties("kv.shard")
public record ShardProperties(int ordinal, int count, boolean enforceOwnership) {
  // Defaults live in application.yaml. A bad count is caught by ShardIdentity's constructor,
  // which rejects an ordinal outside the cluster - a real invariant rather than a fallback.
}
