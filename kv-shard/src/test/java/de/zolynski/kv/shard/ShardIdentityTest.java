package de.zolynski.kv.shard;

import de.zolynski.kv.core.partition.ModuloPartitioner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShardIdentityTest {

  @Test
  void derivesTheOrdinalFromAStatefulSetPodName() {
    assertEquals(0, ShardIdentity.ordinalFromHostname("kv-shard-0", -1));
    assertEquals(7, ShardIdentity.ordinalFromHostname("kv-shard-7", -1));
    assertEquals(12, ShardIdentity.ordinalFromHostname("some-long-name-12", -1));
  }

  @Test
  void fallsBackWhenTheHostnameCarriesNoOrdinal() {
    // Deployments, docker-compose and laptops all produce names of this shape. Guessing an
    // ordinal from them would be worse than admitting there isn't one.
    assertEquals(0, ShardIdentity.ordinalFromHostname("localhost", 0));
    assertEquals(0, ShardIdentity.ordinalFromHostname("kv-shard-abc", 0));
    assertEquals(0, ShardIdentity.ordinalFromHostname("kv-shard-", 0));
    assertEquals(0, ShardIdentity.ordinalFromHostname("nodashes", 0));
    assertEquals(0, ShardIdentity.ordinalFromHostname(null, 0));
    assertEquals(0, ShardIdentity.ordinalFromHostname("kv-shard-99999999999999", 0));
  }

  @Test
  void readsTheOrdinalAsTheSegmentAfterTheFinalDash() {
    // "kv-shard--1" is a StatefulSet named "kv-shard-" at ordinal 1, not a negative ordinal:
    // the suffix after the last dash cannot itself contain a sign.
    assertEquals(1, ShardIdentity.ordinalFromHostname("kv-shard--1", 0));
  }

  @Test
  void rejectsAnOrdinalOutsideTheCluster() {
    // Catches the skew at startup rather than one request at a time.
    assertThrows(IllegalArgumentException.class, () -> new ShardIdentity(3, 3, true));
    assertThrows(IllegalArgumentException.class, () -> new ShardIdentity(-1, 3, true));
  }

  @Test
  void ownershipAgreesWithThePartitionerTheRouterUses() {
    // Both sides must derive placement identically or the 421 check would reject valid keys.
    int shards = 4;
    ModuloPartitioner partitioner = new ModuloPartitioner(shards);
    ShardIdentity[] cluster = new ShardIdentity[shards];
    for (int i = 0; i < shards; i++) {
      cluster[i] = new ShardIdentity(i, shards, true);
    }

    for (int i = 0; i < 5_000; i++) {
      String key = "key:" + i;
      int owner = partitioner.shardFor(key);
      assertTrue(cluster[owner].owns(key));
      assertFalse(cluster[owner].shouldReject(key));
      for (int s = 0; s < shards; s++) {
        if (s != owner) {
          assertTrue(cluster[s].shouldReject(key));
        }
      }
    }
  }

  @Test
  void ownershipEnforcementIsInertInASingleShardCluster() {
    ShardIdentity solo = new ShardIdentity(0, 1, true);
    assertFalse(solo.shouldReject("anything"));
  }

  @Test
  void enforcementCanBeDisabled() {
    ShardIdentity lenient = new ShardIdentity(0, 4, false);
    assertFalse(lenient.shouldReject("some-key-owned-elsewhere"));
  }
}
