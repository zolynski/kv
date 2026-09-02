package de.zolynski.kv.core.hash;

/// Java String's hashCode is not great. It does not create uniform hash
/// distributions, so another proven hash function is used.
public final class KeyHash {

  private KeyHash() {
  }

  public static int hash32(String key) {
    // MurmurHash3's fmix32, applied to the JDK's memoised hash.
    int h = key.hashCode();
    h ^= h >>> 16;
    h *= 0x85EBCA6B;
    h ^= h >>> 13;
    h *= 0xC2B2AE35;
    h ^= h >>> 16;
    return h;
  }
}
