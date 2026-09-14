package de.zolynski.kv.shard.config;

import de.zolynski.kv.core.store.Store;
import de.zolynski.kv.core.store.StoreConfig;
import de.zolynski.kv.shard.store.ConcurrentHashMapStore;
import de.zolynski.kv.shard.store.StripedChainedStore;
import de.zolynski.kv.shard.ShardIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.StringHttpMessageConverter;

import java.nio.charset.StandardCharsets;

@Configuration
public class StoreConfiguration {

  private static final Logger log = LoggerFactory.getLogger(StoreConfiguration.class);

  /// Picks the store implementation at startup.
  @Bean
  public Store store(StoreProperties properties) {
    StoreConfig config = new StoreConfig(properties.maxEntries());
    Store store = switch (properties.implementation()) {
      case CHAINED -> new StripedChainedStore(config);
      case CONCURRENT_HASH_MAP -> new ConcurrentHashMapStore(config);
    };
    log.info("store={} maxEntries={}",
            store.stats().implementation(), properties.maxEntries());
    return store;
  }

  @Bean
  public StringHttpMessageConverter stringHttpMessageConverter() {
    return new StringHttpMessageConverter(StandardCharsets.UTF_8);
  }

  /// `POD_NAME` comes from the downward API and is preferred over `HOSTNAME`, which the container
  /// runtime sets by convention rather than Kubernetes by contract.
  private static String podName() {
    String fromDownwardApi = System.getenv("POD_NAME");
    return fromDownwardApi != null && !fromDownwardApi.isBlank()
            ? fromDownwardApi
            : System.getenv("HOSTNAME");
  }

  @Bean
  public ShardIdentity shardIdentity(ShardProperties properties) {
    int ordinal = properties.ordinal() >= 0
            ? properties.ordinal()
            : ShardIdentity.ordinalFromHostname(podName(), 0);
    ShardIdentity identity = new ShardIdentity(ordinal, properties.count(),
            properties.enforceOwnership());
    log.info("shard {} of {} (ownership enforcement {})", identity.ordinal(),
            identity.count(), properties.enforceOwnership() ? "on" : "off");
    return identity;
  }
}
