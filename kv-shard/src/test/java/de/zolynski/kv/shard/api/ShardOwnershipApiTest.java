package de.zolynski.kv.shard.api;

import de.zolynski.kv.core.partition.ModuloPartitioner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"kv.shard.count=3", "kv.shard.ordinal=0", "kv.shard.enforce-ownership=true"})
class ShardOwnershipApiTest {

  private static final ModuloPartitioner PARTITIONER = new ModuloPartitioner(3);

  @LocalServerPort
  int port;

  private RestClient client() {
    return RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .defaultStatusHandler(status -> true, (_, _) -> {
            })
            .build();
  }

  @Test
  void refusesKeysBelongingToAnotherShardWith421() {
    String foreignKey = keyOwnedBy(1);

    ResponseEntity<String> response = client().put()
            .uri("/internal/keys/" + foreignKey)
            .contentType(MediaType.TEXT_PLAIN)
            .body("value")
            .retrieve()
            .toEntity(String.class);

    assertEquals(HttpStatus.MISDIRECTED_REQUEST, response.getStatusCode());
    assertTrue(response.getBody() != null && response.getBody().contains("expectedShard"),
            "the problem detail should name the shard that should have received it");
  }

  @Test
  void refusesMisdirectedReadsAndDeletesToo() {
    String foreignKey = keyOwnedBy(2);

    assertEquals(HttpStatus.MISDIRECTED_REQUEST,
            client().get().uri("/internal/keys/" + foreignKey)
                    .retrieve().toBodilessEntity().getStatusCode());
    assertEquals(HttpStatus.MISDIRECTED_REQUEST,
            client().delete().uri("/internal/keys/" + foreignKey)
                    .retrieve().toBodilessEntity().getStatusCode());
  }

  @Test
  void acceptsKeysItGenuinelyOwns() {
    String ownKey = keyOwnedBy(0);
    assertEquals(HttpStatus.CREATED,
            client().put().uri("/internal/keys/" + ownKey)
                    .contentType(MediaType.TEXT_PLAIN).body("mine")
                    .retrieve().toEntity(String.class).getStatusCode());
  }

  private static String keyOwnedBy(int shard) {
    for (int i = 0; i < 10_000; i++) {
      String candidate = "probe-" + i;
      if (PARTITIONER.shardFor(candidate) == shard) {
        return candidate;
      }
    }
    throw new IllegalStateException("no key hashed to shard " + shard);
  }
}
