package de.zolynski.kv.router.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/// @param shardCount       how many shards exist
/// @param shardUrlTemplate a shard's address with `{ordinal}` substituted
/// @param shardUrls        explicit per-shard addresses for the deployments where they are not
///                         regular, such as tests binding to ephemeral ports
@ConfigurationProperties("kv.router")
public record RouterProperties(
        int shardCount,
        String shardUrlTemplate,
        List<String> shardUrls) {

  public RouterProperties {
    shardUrls = shardUrls == null ? List.of() : shardUrls;
    if (!shardUrls.isEmpty()) {
      shardCount = shardUrls.size();
    }
  }

  public String urlFor(int ordinal) {
    return shardUrls.isEmpty()
            ? shardUrlTemplate.replace("{ordinal}", Integer.toString(ordinal))
            : shardUrls.get(ordinal);
  }
}
