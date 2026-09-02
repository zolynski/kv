package de.zolynski.kv.shard.api;

import de.zolynski.kv.core.store.Store;
import de.zolynski.kv.core.store.StoreStats;
import de.zolynski.kv.shard.ShardIdentity;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/// The shard's HTTP access, only available from the router.
@RestController
@RequestMapping("/internal")
public class ShardController {

  private static final String SHARD_HEADER = "X-KV-Shard";
  private static final String TEXT = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8";

  private final Store store;
  private final ShardIdentity identity;

  public ShardController(Store store, ShardIdentity identity) {
    this.store = store;
    this.identity = identity;
  }

  @PutMapping(path = "/keys/{*key}", produces = TEXT)
  public ResponseEntity<String> put(@PathVariable("key") String key,
                                    @RequestBody(required = false) String value) {
    String actualKey = normalise(key);
    guardOwnership(actualKey);
    // An absent body is taken as the empty string: "" is a legitimate value, and there is no
    // other way for a caller to write one.
    return store.put(actualKey, value == null ? "" : value)
            .map(previous -> shardResponse(ResponseEntity.ok()).body(previous))
            .orElseGet(() -> shardResponse(ResponseEntity.status(201)).build());
  }

  @GetMapping(path = "/keys/{*key}", produces = TEXT)
  public ResponseEntity<String> get(@PathVariable("key") String key) {
    String actualKey = normalise(key);
    guardOwnership(actualKey);
    return store.get(actualKey)
            .map(value -> shardResponse(ResponseEntity.ok()).body(value))
            .orElseGet(() -> shardResponse(ResponseEntity.notFound()).build());
  }

  @DeleteMapping(path = "/keys/{*key}")
  public ResponseEntity<Void> delete(@PathVariable("key") String key) {
    String actualKey = normalise(key);
    guardOwnership(actualKey);

    return store.remove(actualKey).isPresent()
            ? shardResponse(ResponseEntity.noContent()).build()
            : shardResponse(ResponseEntity.notFound()).build();
  }

  /// A bounded sample of this shard's keys.
  @GetMapping("/keys")
  public ResponseEntity<List<String>> keys(@RequestParam(defaultValue = "100") int limit) {
    int bounded = Math.clamp(limit, 0, 10_000);
    return shardResponse(ResponseEntity.ok()).body(store.keys(bounded));
  }

  @DeleteMapping("/keys")
  public ResponseEntity<Void> clear() {
    store.clear();
    return shardResponse(ResponseEntity.noContent()).build();
  }

  @GetMapping("/stats")
  public ResponseEntity<ShardStats> stats() {
    return shardResponse(ResponseEntity.ok())
            .body(new ShardStats(identity.ordinal(), identity.count(), store.stats()));
  }

  /// The trailing-wildcard capture keeps the leading slash; the key itself does not have one.
  private static String normalise(String captured) {
    if (captured == null) {
      return "";
    }
    return captured.startsWith("/") ? captured.substring(1) : captured;
  }

  private void guardOwnership(String key) {
    if (identity.shouldReject(key)) {
      throw new MisdirectedKeyException(key, identity.ordinal(),
              identity.expectedOwnerOf(key));
    }
  }

  private ResponseEntity.BodyBuilder shardResponse(ResponseEntity.BodyBuilder builder) {
    return builder.header(SHARD_HEADER, Integer.toString(identity.ordinal()));
  }

  private ResponseEntity.HeadersBuilder<?> shardResponse(
          ResponseEntity.HeadersBuilder<?> builder) {
    return builder.header(SHARD_HEADER, Integer.toString(identity.ordinal()));
  }

  /// @param store occupancy of this shard alone, not the cluster
  public record ShardStats(int ordinal, int shardCount, StoreStats store) {
  }
}
