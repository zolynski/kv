package de.zolynski.kv.shard.api;

import de.zolynski.kv.core.store.Store;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "kv.shard.count=1",
                "kv.shard.ordinal=0",
                "kv.store.max-entries=100"})
class ShardCapacityApiTest {

  @LocalServerPort
  int port;

  @Autowired
  Store store;

  RestClient client;

  @BeforeEach
  void setUp() {
    store.clear();
    client = RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .defaultStatusHandler(status -> true, (_, _) -> {
            })
            .build();
  }

  @Test
  void writesPastCapacityAreRejectedWith507AndEarlierEntriesSurvive() {
    int accepted = 0;
    HttpStatus last = null;
    for (int i = 0; i < 200; i++) {
      ResponseEntity<String> response = client.put().uri("/internal/keys/k" + i)
              .contentType(MediaType.TEXT_PLAIN).body("v")
              .retrieve().toEntity(String.class);
      last = HttpStatus.valueOf(response.getStatusCode().value());
      if (last == HttpStatus.CREATED) {
        accepted++;
      } else {
        break;
      }
    }

    assertEquals(HttpStatus.INSUFFICIENT_STORAGE, last);
    assertEquals(100, accepted, "the configured entry limit should be admitted exactly");
    assertEquals(HttpStatus.OK,
            client.get().uri("/internal/keys/k0").retrieve().toBodilessEntity().getStatusCode(),
            "rejecting a write must never evict an accepted one");
  }

  @Test
  void updatingAnExistingKeyStillWorksWhenFull() {
    for (int i = 0; i < 200; i++) {
      client.put().uri("/internal/keys/full" + i)
              .contentType(MediaType.TEXT_PLAIN).body("v")
              .retrieve().toBodilessEntity();
    }
    ResponseEntity<String> update = client.put().uri("/internal/keys/full0")
            .contentType(MediaType.TEXT_PLAIN).body("updated")
            .retrieve().toEntity(String.class);

    assertTrue(update.getStatusCode().is2xxSuccessful(),
            "a full store must still accept overwrites; they consume no new slot");
  }
}
