package de.zolynski.kv.core.store;

import java.nio.charset.StandardCharsets;

/// What a key and a value are allowed to be.
///
/// These are mostly arbitrary picked limits, not grounded in underlying limitations. Redis
/// allows 512 MiB for keys/values but discourages from using more than 1024 bytes for keys.
/// memcached even limits keys to 250 bytes.
public final class EntryRules {

  /// Maximum key length in UTF-8 bytes.
  public static final int MAX_KEY_BYTES = 1024;

  /// Maximum value length in UTF-8 bytes.
  ///
  /// We also use this value to calculate the capacity.
  public static final int MAX_VALUE_BYTES = 100 * 1024;

  private EntryRules() {
  }

  /// Whether a key is set.
  public static void requireKeyNotNull(String key) {
    if (key == null) {
      throw new InvalidEntryException(Rule.KEY_NULL, "key must not be null");
    }
  }

  /// Whether a value is set.
  public static void requireValueNotNull(String value) {
    if (value == null) {
      throw new InvalidEntryException(Rule.VALUE_NULL, "value must not be null");
    }
  }

  /// Whether a key is valid; this only checks for length and invalid chars, not for Unicode shenanigans.
  public static void validateKey(String key) {
    requireKeyNotNull(key);

    int bytes = key.getBytes(StandardCharsets.UTF_8).length;
    if (bytes > MAX_KEY_BYTES) {
      throw new InvalidEntryException(
              Rule.KEY_TOO_LONG,
              "key is " + bytes + " UTF-8 bytes, the limit is " + MAX_KEY_BYTES
      );
    }

    for (int i = 0; i < key.length(); i++) {
      char c = key.charAt(i);
      // Control characters are never meaningful in a key and are what corrupts logs,
      // headers and anything that reads the key back out of a line-oriented format.
      if (c < 0x20 || c == 0x7F) {
        throw new InvalidEntryException(
                Rule.KEY_CONTROL_CHARACTER,
                "key contains the control character U+" + String.format("%04X", (int) c) + " at index " + i
        );
      }
      // Servlet containers refuse a backslash in a path - literal or encoded - before dispatch,
      // so no validation here can produce the error instead. Stating the rule is still what makes
      // the system consistent: without it a programmatic caller could create a key no HTTP client
      // could ever read back.
      if (c == '\\') {
        throw new InvalidEntryException(
                Rule.KEY_RESERVED_CHARACTER,
                "key contains a backslash at index " + i
                        + "; backslashes are reserved because servlet containers refuse "
                        + "them in URL paths, so such a key could never be addressed"
        );
      }
    }
  }

  /// Whether a value is set.
  ///
  /// Values are arbitrary text: control characters, newlines, and tabs are all legitimate
  /// content, so only length is constrained.
  public static void validateValue(String value) {
    requireValueNotNull(value);

    if (value.length() > MAX_VALUE_BYTES) {
      throw new InvalidEntryException(
              Rule.VALUE_TOO_LONG,
              "value is " + value.length() + " characters, which cannot fit "
                      + MAX_VALUE_BYTES + " UTF-8 bytes"
      );
    }

    int bytes = value.getBytes(StandardCharsets.UTF_8).length;
    if (bytes > MAX_VALUE_BYTES) {
      throw new InvalidEntryException(
              Rule.VALUE_TOO_LONG,
              "value is " + bytes + " UTF-8 bytes, the limit is " + MAX_VALUE_BYTES
      );
    }
  }

  ///  The named rules, so a rejection can say which one it broke rather than just "invalid".
  public enum Rule {
    KEY_NULL,
    KEY_TOO_LONG,
    KEY_CONTROL_CHARACTER,
    KEY_RESERVED_CHARACTER,
    VALUE_NULL,
    VALUE_TOO_LONG
  }
}
