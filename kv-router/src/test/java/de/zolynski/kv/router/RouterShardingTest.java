package de.zolynski.kv.router;

import de.zolynski.kv.core.partition.ModuloPartitioner;
import de.zolynski.kv.core.store.EntryRules;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.BDDAssertions.then;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RouterShardingTest {

  private static final int SHARDS = 3;
  private static final ModuloPartitioner PARTITIONER = new ModuloPartitioner(SHARDS);
  private static final List<StubShard> shards = new ArrayList<>();

  static {
    // Shard URLs must exist before Spring resolves @DynamicPropertySource.
    try {
      for (int i = 0; i < SHARDS; i++) {
        shards.add(new StubShard(i));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @DynamicPropertySource
  static void routerPointsAtStubShards(DynamicPropertyRegistry registry) {
    registry.add("kv.router.shard-urls",
            () -> shards.stream().map(StubShard::url).collect(Collectors.joining(",")));
  }

  @AfterAll
  static void stopShards() {
    shards.forEach(StubShard::close);
  }

  @LocalServerPort
  int port;

  RestClient client;

  @BeforeEach
  void setUp() {
    shards.forEach(StubShard::reset);
    client = RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .defaultStatusHandler(status -> true, (_, _) -> {
            })
            .build();
  }

  @Test
  void aKeyIsStoredOnTheShardThePartitionerNamesAndOnNoOther() {
    // given a spread of keys
    List<String> keys = keys("key:", 60);

    // when each is written through the router
    keys.forEach(key -> put(key, "v:" + key));

    // then it sits on its owner, and nowhere else
    for (String key : keys) {
      int owner = PARTITIONER.shardFor(key);
      then(shards.get(owner).entries())
              .as("key %s belongs to shard %d", key, owner)
              .containsEntry(key, "v:" + key);
      for (int other = 0; other < SHARDS; other++) {
        if (other != owner) {
          then(shards.get(other).entries())
                  .as("shard %d must not hold a key it does not own", other)
                  .doesNotContainKey(key);
        }
      }
    }
  }

  @Test
  void aRoundTripPreservesTheValueAndNamesTheServingShard() {
    // when the key is written
    ResponseEntity<String> created = put("greeting", "hello");

    // then the response says who stored it
    then(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    then(created.getHeaders().getFirst("X-KV-Shard"))
            .as("the response should say which shard handled it")
            .isEqualTo(Integer.toString(PARTITIONER.shardFor("greeting")));

    // and the value round-trips, then is gone once deleted
    then(get("greeting").getBody()).isEqualTo("hello");
    then(delete("greeting").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    then(get("greeting").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void theWildcardsLeadingSlashIsNotPartOfTheKey() {
    // when a key containing no slash is written
    put("greeting", "hello");

    // then that is exactly the key the shard was asked to store
    then(shards.stream().flatMap(shard -> shard.entries().keySet().stream()).toList())
            .containsExactly("greeting");
  }

  @Test
  void keysSurviveEncodingHazardsOnTheWayToTheShard() {
    // given keys that each break a naive implementation: '/' would be split into segments,
    // '+' would become a space under form decoding, '{' would be read as a URI template
    // variable, '%' would be double-decoded, and ';' would be truncated as a matrix parameter
    List<String> hazards = List.of("tenant/42/config", "a+b", "a b", "{braced}", "100%",
            "a;b", "q?x=1", "hash#tag", "こんにちは:é-🚀");

    // when each is written
    hazards.forEach(key -> then(put(key, "v:" + key).getStatusCode())
            .as("failed to store %s", key).isEqualTo(HttpStatus.CREATED));

    // then nothing was mangled, in transit or at rest
    for (String key : hazards) {
      then(get(key).getBody()).as("failed to retrieve %s", key).isEqualTo("v:" + key);
    }
    then(shards.stream().flatMap(s -> s.entries().keySet().stream()).toList())
            .as("keys should reach the shard exactly as written")
            .containsAll(hazards);
  }

  @Test
  void dotSegmentsSurviveTheRouterToShardHopUnchanged() {
    String key = "p/../q";

    then(put(key, "kept").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    then(shards.get(PARTITIONER.shardFor(key)).entries()).containsEntry(key, "kept");
    then(get(key).getBody()).isEqualTo("kept");
  }

  @Test
  void aTruncatedListingStillRepresentsEveryShard() {
    // given more keys than the listing will return
    keys("k", 300).forEach(key -> put(key, "v"));

    // when a bounded listing is asked for
    List<Map<String, Object>> listed = client.get().uri("/api/v1/keys?limit=30")
            .retrieve().body(List.class);

    // then the limit is honoured and the fan-out interleaved rather than drained in order
    then(listed).hasSize(30);
    then(listed.stream().map(entry -> entry.get("shard")).distinct().sorted().toList())
            .as("a truncated listing should still represent every shard")
            .containsExactly(0, 1, 2);
  }

  @Test
  void topologyReportsPerShardOccupancyAndTheClusterTotal() {
    // given a known number of keys spread across the cluster
    keys("k", 90).forEach(key -> put(key, "v"));

    // when topology is asked for
    Map<String, Object> topology = client.get().uri("/api/v1/topology")
            .retrieve().body(Map.class);

    // then it accounts for all of them, and every shard answered
    then(topology).containsEntry("shardCount", SHARDS).doesNotContainKey("partitioner");
    then(((Number) topology.get("totalEntries")).intValue()).isEqualTo(90);

    List<Map<String, Object>> views = (List<Map<String, Object>>) topology.get("shards");
    then(views).hasSize(SHARDS).allMatch(view -> (Boolean) view.get("reachable"));
  }

  @Test
  void aDeadShardDegradesTheListingInsteadOfFailingIt() {
    // given a populated cluster
    keys("k", 300).forEach(key -> put(key, "v"));

    // when one shard stops answering entirely
    shards.get(1).down(true);

    // then browsing still works, minus that shard
    ResponseEntity<String> response = client.get().uri("/api/v1/keys?limit=60")
            .retrieve().toEntity(String.class);
    then(response.getStatusCode())
            .as("one dead shard must not take down browsing of the other two")
            .isEqualTo(HttpStatus.OK);
    then(response.getBody()).doesNotContain("\"shard\":1");

    // and topology reports it as unreachable rather than omitting it
    Map<String, Object> topology = client.get().uri("/api/v1/topology")
            .retrieve().body(Map.class);
    List<Map<String, Object>> views = (List<Map<String, Object>>) topology.get("shards");
    then((Boolean) views.get(1).get("reachable")).isFalse();
  }

  @Test
  void aKeyThatBreaksAPublishedRuleCostsTheShardsNothing() {
    // The reason validation lives at the edge rather than only in the store: a request that
    // breaks a published rule should cost nothing at all. Asserted by counting shard requests
    // rather than by trusting the arrangement.
    int[] before = shards.stream().mapToInt(StubShard::requestCount).toArray();

    // when each rule is broken in turn
    ResponseEntity<String> tooLong = put("k".repeat(EntryRules.MAX_KEY_BYTES + 1), "v");
    ResponseEntity<String> control = put("a\u0007b", "v");
    ResponseEntity<String> bigValue = put("fine", "v".repeat(EntryRules.MAX_VALUE_BYTES + 1));

    // then each is refused by name, with the limit the caller needs
    then(tooLong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    then(tooLong.getBody()).contains("KEY_TOO_LONG", "maxKeyBytes");
    then(control.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    then(control.getBody()).contains("KEY_CONTROL_CHARACTER");
    then(bigValue.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    then(bigValue.getBody()).contains("VALUE_TOO_LONG");

    // and no shard was ever asked
    for (int i = 0; i < SHARDS; i++) {
      then(shards.get(i).requestCount())
              .as("shard %d was contacted for a request that broke a published rule", i)
              .isEqualTo(before[i]);
    }
  }

  @Test
  void aKeyAtExactlyTheLimitIsRoutedNormally() {
    // The boundary is inclusive, and a maximum-length key still fits the request line with
    // room to spare - which is the headroom the rules were chosen to guarantee.
    String atLimit = "k".repeat(EntryRules.MAX_KEY_BYTES);

    then(put(atLimit, "v").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    then(get(atLimit).getBody()).isEqualTo("v");
  }

  @Test
  void aShardsCapacityRejectionReachesTheClientUnchanged() {
    // 507 must not be flattened into a 500: the client's correct reaction is to delete
    // something, and only the real status tells it so.
    shards.forEach(shard -> shard.full(true));

    then(put("anything", "v").getStatusCode()).isEqualTo(HttpStatus.INSUFFICIENT_STORAGE);
  }

  private static List<String> keys(String prefix, int count) {
    return IntStream.range(0, count).mapToObj(i -> prefix + i).toList();
  }

  private ResponseEntity<String> put(String key, String value) {
    return client.put().uri(uriFor(key))
            .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
            .body(value)
            .retrieve().toEntity(String.class);
  }

  private ResponseEntity<String> get(String key) {
    return client.get().uri(uriFor(key)).retrieve().toEntity(String.class);
  }

  private ResponseEntity<String> delete(String key) {
    return client.delete().uri(uriFor(key)).retrieve().toEntity(String.class);
  }

  private URI uriFor(String key) {
    return URI.create("http://localhost:" + port + "/api/v1/keys/" + encode(key));
  }

  private static final HexFormat HEX = HexFormat.of().withUpperCase();

  /// Mirrors what a browser or curl would send: percent-encoded, slashes left alone.
  private static String encode(String key) {
    StringBuilder out = new StringBuilder();
    for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
      int c = b & 0xFF;
      boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
              || (c >= '0' && c <= '9') || "-._~!$&'()*+,=:@/".indexOf(c) >= 0;
      if (safe) {
        out.append((char) c);
      } else {
        out.append('%').append(HEX.toHexDigits((byte) c));
      }
    }
    return out.toString();
  }
}
