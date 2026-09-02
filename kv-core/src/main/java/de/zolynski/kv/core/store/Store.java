package de.zolynski.kv.core.store;

import java.util.List;
import java.util.Optional;

/// A concurrent string-to-string map.
public interface Store {

  /// Adds or replaces a value.
  ///
  /// @return the previous value, or empty if absent
  Optional<String> put(String key, String value);

  /// Returns the value for the given key.
  ///
  /// @return the stored value, or empty if absent
  Optional<String> get(String key);

  /// Removes a value with the given key.
  ///
  /// @return the removed value, or empty if absent
  Optional<String> remove(String key);

  /* Pure demo methods down here */

  /// Number of stored keys.
  int size();

  /// A bounded sample of keys, in unspecified order. Scans are O(capacity) and exist only to make
  /// the UI browsable; the limit is mandatory, so a fan-out across shards cannot become an
  /// accidental full-cluster dump.
  List<String> keys(int limit);

  /// Statistics about the store.
  StoreStats stats();

  /// Removes all entries.
  void clear();
}
