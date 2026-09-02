package de.zolynski.kv.router.shard;

import de.zolynski.kv.core.store.StoreStats;
import de.zolynski.kv.router.config.RouterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.net.http.HttpClient;
import java.util.List;

/// The router's client to access shards.
public class ShardClient {

  private static final Logger log = LoggerFactory.getLogger(ShardClient.class);

  private static final MediaType TEXT_UTF8 = new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

  private final RestClient[] clients;
  private final String[] urls;

  public ShardClient(RouterProperties properties) {
    HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(READ_TIMEOUT);

    this.clients = new RestClient[properties.shardCount()];
    this.urls = new String[properties.shardCount()];
    for (int i = 0; i < properties.shardCount(); i++) {
      urls[i] = properties.urlFor(i);
      clients[i] = RestClient.builder()
              .baseUrl(urls[i])
              .requestFactory(factory)
              // Shard status codes are translated to proper errors.
              .defaultStatusHandler(_ -> true, (_, _) -> {
              })
              .build();
    }
    log.info("routing to {} shards: {}", urls.length, String.join(", ", urls));
  }

  public ShardResponse put(int shard, String key, String value) {
    ResponseEntity<String> response = clients[shard].put()
            .uri(keyUri(shard, key))
            .contentType(TEXT_UTF8)
            .body(value)
            .retrieve()
            .toEntity(String.class);
    return ShardResponse.of(response);
  }

  public ShardResponse get(int shard, String key) {
    return ShardResponse.of(clients[shard].get()
            .uri(keyUri(shard, key))
            .retrieve()
            .toEntity(String.class));
  }

  public ShardResponse delete(int shard, String key) {
    return ShardResponse.of(clients[shard].delete()
            .uri(keyUri(shard, key))
            .retrieve()
            .toEntity(String.class));
  }

  @SuppressWarnings("unchecked")
  public List<String> keys(int shard, int limit) {
    List<String> keys = clients[shard].get()
            .uri(URI.create(urls[shard] + "/internal/keys?limit=" + limit))
            .retrieve()
            .body(List.class);
    return keys == null ? List.of() : keys;
  }

  public void clear(int shard) {
    clients[shard].delete()
            .uri(URI.create(urls[shard] + "/internal/keys"))
            .retrieve()
            .toBodilessEntity();
  }

  public ShardStatsView stats(int shard) {
    return clients[shard].get()
            .uri(URI.create(urls[shard] + "/internal/stats"))
            .retrieve()
            .body(ShardStatsView.class);
  }

  public String url(int shard) {
    return urls[shard];
  }

  public int shardCount() {
    return clients.length;
  }

  private URI keyUri(int shard, String key) {
    return URI.create(urls[shard] + "/internal/keys/" + PathEncoder.encodeKey(key));
  }

  public record ShardResponse(int status, String body) {

    static ShardResponse of(ResponseEntity<String> response) {
      return new ShardResponse(response.getStatusCode().value(), response.getBody());
    }
  }

  public record ShardStatsView(int ordinal, int shardCount, StoreStats store) {
  }
}
