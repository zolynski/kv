package de.zolynski.kv.router.config;

import de.zolynski.kv.core.partition.KeyPartitioner;
import de.zolynski.kv.core.partition.ModuloPartitioner;
import de.zolynski.kv.router.shard.ShardClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.StringHttpMessageConverter;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class RouterConfiguration {

  @Bean
  public KeyPartitioner keyPartitioner(RouterProperties properties) {
    return new ModuloPartitioner(properties.shardCount());
  }

  @Bean
  public ShardClient shardClient(RouterProperties properties) {
    return new ShardClient(properties);
  }

  /// Carries scatter-gather calls to separate regular requests from slower fanout ones.
  @Bean(name = "fanOutExecutor", destroyMethod = "close")
  public ExecutorService fanOutExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  /// Overrides Spring's default `text/plain` charset of ISO-8859-1 with UTF-8.
  @Bean
  public StringHttpMessageConverter stringHttpMessageConverter() {
    return new StringHttpMessageConverter(StandardCharsets.UTF_8);
  }
}
