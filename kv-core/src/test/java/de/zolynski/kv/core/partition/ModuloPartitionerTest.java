package de.zolynski.kv.core.partition;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// How keys spread across shards.
class ModuloPartitionerTest {

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 8, 16, 32})
  void everyKeyLandsInRange(int shardCount) {
    // given a partitioner of some size
    ModuloPartitioner partitioner = new ModuloPartitioner(shardCount);

    // when it is asked about a spread of keys
    // then every answer names a shard that exists. Math.abs would break this: abs of
    // Integer.MIN_VALUE is itself negative, and would index off the front of the array.
    for (int i = 0; i < 20_000; i++) {
      String key = "user:" + i + ":profile";
      int shard = partitioner.shardFor(key);
      assertTrue(shard >= 0 && shard < shardCount,
              () -> "key " + key + " named shard " + shard + " of " + shardCount);
    }
  }

  @Test
  void theSameKeyAlwaysGoesToTheSameShard() {
    // The property the architecture rests on: routing is a pure function of the key, so every
    // router reaches the same conclusion without coordinating with any other.
    ModuloPartitioner partitioner = new ModuloPartitioner(3);
    ModuloPartitioner another = new ModuloPartitioner(3);

    for (String key : new String[]{"greeting", "tenant/4711/config", "こんにちは:é-🚀", ""}) {
      assertEquals(partitioner.shardFor(key), partitioner.shardFor(key));
      assertEquals(partitioner.shardFor(key), another.shardFor(key),
              "a second instance disagreed about " + key);
    }
  }

  @Test
  void keysAreSpreadRatherThanPiledOntoOneShard() {
    // A partitioner that compiles but distributes badly passes every functional test while
    // destroying the point of sharding. This is the test that fails for String.hashCode on its
    // own, which is why KeyHash puts it through a finalizer first.
    ModuloPartitioner partitioner = new ModuloPartitioner(3);
    int[] counts = new int[3];

    for (int i = 0; i < 30_000; i++) {
      counts[partitioner.shardFor("user:" + i)]++;
    }

    int min = IntStream.of(counts).min().orElseThrow();
    int max = IntStream.of(counts).max().orElseThrow();
    assertEquals(30_000, IntStream.of(counts).sum());
    assertTrue((double) max / min < 1.05,
            () -> "uneven distribution across shards: " + Arrays.toString(counts));
  }

  @Test
  void aSingleShardOwnsEverything() {
    // The degenerate case docker-compose and the laptop setup actually run.
    ModuloPartitioner partitioner = new ModuloPartitioner(1);

    for (int i = 0; i < 1_000; i++) {
      assertEquals(0, partitioner.shardFor("k" + i));
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
  void aClusterMustHaveAtLeastOneShard(int shardCount) {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> new ModuloPartitioner(shardCount));
    assertTrue(e.getMessage().contains("shardCount"), "got: " + e.getMessage());
  }
}
