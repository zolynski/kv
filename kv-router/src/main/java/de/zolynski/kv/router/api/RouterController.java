package de.zolynski.kv.router.api;

import de.zolynski.kv.core.partition.KeyPartitioner;
import de.zolynski.kv.core.store.EntryRules;
import de.zolynski.kv.core.store.StoreStats;
import de.zolynski.kv.router.shard.ShardClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static de.zolynski.kv.core.store.EntryRules.validateKey;
import static de.zolynski.kv.core.store.EntryRules.validateValue;

/// The public API.
///
/// Every request is resolved to one or multiple shards and proxied there.
@RestController
@RequestMapping("/api/v1")
public class RouterController {

  private static final Logger log = LoggerFactory.getLogger(RouterController.class);
  private static final String SHARD_HEADER = "X-KV-Shard";
  private static final String TEXT = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8";

  /// Backstop for a whole scatter-gather.
  private static final Duration FAN_OUT_TIMEOUT = Duration.ofSeconds(10);

  private final KeyPartitioner partitioner;
  private final ShardClient shards;
  private final ExecutorService fanOut;

  public RouterController(
          KeyPartitioner partitioner, ShardClient shards,
          @Qualifier("fanOutExecutor") ExecutorService fanOut
  ) {
    this.partitioner = partitioner;
    this.shards = shards;
    this.fanOut = fanOut;
  }

  @PutMapping(path = "/keys/{*key}", produces = TEXT)
  public ResponseEntity<String> put(
          @PathVariable("key") String captured,
          @RequestBody(required = false) String value
  ) {
    String key = normalise(captured);
    validateKey(key);
    validateValue(value);
    return proxy(key, shard -> shards.put(shard, key, value));
  }

  @GetMapping(path = "/keys/{*key}", produces = TEXT)
  public ResponseEntity<String> get(@PathVariable("key") String captured) {
    String key = normalise(captured);
    validateKey(key);
    return proxy(key, shard -> shards.get(shard, key));
  }

  @DeleteMapping(path = "/keys/{*key}", produces = TEXT)
  public ResponseEntity<String> delete(@PathVariable("key") String captured) {
    String key = normalise(captured);
    validateKey(key);
    return proxy(key, shard -> shards.delete(shard, key));
  }

  /// The trailing-wildcard capture keeps the leading slash; the key itself does not have one.
  ///
  /// The same method as ShardController's, and both tiers need it: without this the router
  /// validates and partitions `/foo` rather than `foo`, so every key silently gains a slash and
  /// a key of exactly MAX_KEY_BYTES is refused as one byte too long.
  private static String normalise(String captured) {
    if (captured == null) {
      return "";
    }
    return captured.startsWith("/") ? captured.substring(1) : captured;
  }

  /// Lists keys from across the cluster, just for demo.
  ///
  /// A scatter-gather across all shards.
  @GetMapping("/keys")
  public ResponseEntity<List<KeyLocation>> keys(@RequestParam(defaultValue = "100") int limit) {
    int bounded = Math.clamp(limit, 0, 10_000);
    int perShard = Math.ceilDiv(bounded, Math.max(1, shards.shardCount()));

    List<List<String>> perShardKeys = scatter(shard -> shards.keys(shard, perShard), List.<String>of());

    List<KeyLocation> merged = new ArrayList<>(bounded);
    for (int round = 0; merged.size() < bounded; round++) {
      boolean anyLeft = false;
      for (int shard = 0; shard < perShardKeys.size() && merged.size() < bounded; shard++) {
        List<String> keys = perShardKeys.get(shard);
        if (round < keys.size()) {
          merged.add(new KeyLocation(keys.get(round), shard));
          anyLeft = true;
        }
      }
      if (!anyLeft) {
        break;
      }
    }
    return ResponseEntity.ok(merged);
  }

  // Delete all keys from across the cluster, also just for demo.
  @DeleteMapping("/keys")
  public ResponseEntity<Void> clear() {
    scatter(shard -> {
      shards.clear(shard);
      return true;
    }, false);
    return ResponseEntity.noContent().build();
  }

  /// The cluster as the router understands it, including per-shard occupancy.
  @GetMapping("/topology")
  public ResponseEntity<Topology> topology() {
    List<ShardView> views = scatter(shard -> {
      try {
        ShardClient.ShardStatsView stats = shards.stats(shard);
        return new ShardView(shard, shards.url(shard), true,
                stats == null ? null : stats.store());
      } catch (RuntimeException e) {
        log.warn("shard {} unreachable: {}", shard, e.toString());
        return new ShardView(shard, shards.url(shard), false, null);
      }
    }, null);

    List<ShardView> present = views.stream().filter(Objects::nonNull).toList();
    long entries = present.stream()
            .filter(v -> v.store() != null)
            .mapToLong(v -> v.store().entries())
            .sum();

    return ResponseEntity.ok(new Topology(
            shards.shardCount(), entries,
            new Limits(EntryRules.MAX_KEY_BYTES, EntryRules.MAX_VALUE_BYTES), present)
    );
  }

  /// Resolves the owning shard, forwards, and passes the shard's own status back untouched.
  private ResponseEntity<String> proxy(String key, ShardCall call) {
    int shard = partitioner.shardFor(key);
    ShardClient.ShardResponse response = call.apply(shard);
    return ResponseEntity.status(response.status())
            .header(SHARD_HEADER, Integer.toString(shard))
            .body(response.body());
  }

  /// Runs one call per shard concurrently. Shards not responding in time are ignored.
  private <T> List<T> scatter(ShardTask<T> task, T whenMissing) {
    List<Callable<T>> calls = new ArrayList<>(shards.shardCount());
    for (int shard = 0; shard < shards.shardCount(); shard++) {
      int ordinal = shard;
      calls.add(() -> task.apply(ordinal));
    }

    try {
      List<Future<T>> futures = fanOut.invokeAll(calls,
              FAN_OUT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      List<T> results = new ArrayList<>(futures.size());
      for (int shard = 0; shard < futures.size(); shard++) {
        try {
          results.add(futures.get(shard).get());
        } catch (Exception e) {
          log.warn("shard {} did not answer the fan-out: {}", shard, e.toString());
          results.add(whenMissing);
        }
      }
      return results;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("fan-out interrupted", e);
    }
  }

  @FunctionalInterface
  private interface ShardCall {
    ShardClient.ShardResponse apply(int shard);
  }

  @FunctionalInterface
  private interface ShardTask<T> {
    T apply(int shard) throws Exception;
  }

  public record KeyLocation(String key, int shard) {
  }

  public record ShardView(int ordinal, String url, boolean reachable, StoreStats store) {
  }

  public record Limits(int maxKeyBytes, int maxValueBytes) {
  }

  public record Topology(
          int shardCount, long totalEntries,
          Limits limits, List<ShardView> shards
  ) {
  }
}
