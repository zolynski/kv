package de.zolynski.kv.shard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/// A single shard: one in-memory store behind a REST face.
@SpringBootApplication
@ConfigurationPropertiesScan
public class ShardApplication {

  static void main(String[] args) {
    SpringApplication.run(ShardApplication.class, args);
  }
}
