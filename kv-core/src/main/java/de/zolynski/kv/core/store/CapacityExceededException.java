package de.zolynski.kv.core.store;

/// Thrown when a write operation fails due to missing capacity.
///
/// Old entries are not evicted, so the store rejects new entries
/// rather than running into OOM issues.
public class CapacityExceededException extends RuntimeException {

  public CapacityExceededException(String message) {
    super(message);
  }
}
