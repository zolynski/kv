package de.zolynski.kv.router;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RouterApplication {

  static void main(String[] args) {
    SpringApplication.run(RouterApplication.class, args);
  }
}
