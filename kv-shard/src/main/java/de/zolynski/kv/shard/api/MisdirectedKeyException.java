package de.zolynski.kv.shard.api;

/// Raised when a shard is handed a key that belongs somewhere else.
public class MisdirectedKeyException extends RuntimeException {

  private final transient String key;
  private final int actualShard;
  private final int expectedShard;

  public MisdirectedKeyException(String key, int actualShard, int expectedShard) {
    super("key '" + key + "' belongs to shard " + expectedShard + ", not " + actualShard
            + "; the router's shard count probably disagrees with the cluster");
    this.key = key;
    this.actualShard = actualShard;
    this.expectedShard = expectedShard;
  }

  public String key() {
    return key;
  }

  public int actualShard() {
    return actualShard;
  }

  public int expectedShard() {
    return expectedShard;
  }
}
