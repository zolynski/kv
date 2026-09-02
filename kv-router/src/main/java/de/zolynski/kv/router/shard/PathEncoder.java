package de.zolynski.kv.router.shard;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/// Percent-encodes a key for use as a URL path.
final class PathEncoder {

  private static final HexFormat HEX = HexFormat.of().withUpperCase();

  ///  RFC 3986 pchar, minus `;`, plus `/`.
  private static final boolean[] SAFE = new boolean[128];

  static {
    for (char c = 'a'; c <= 'z'; c++) {
      SAFE[c] = true;
    }
    for (char c = 'A'; c <= 'Z'; c++) {
      SAFE[c] = true;
    }
    for (char c = '0'; c <= '9'; c++) {
      SAFE[c] = true;
    }
    for (char c : "-._~!$&'()*+,=:@/".toCharArray()) {
      SAFE[c] = true;
    }
  }

  private PathEncoder() {
  }

  static String encodeKey(String key) {
    byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
    StringBuilder out = new StringBuilder(bytes.length + 16);
    for (byte b : bytes) {
      int c = b & 0xFF;
      if (c < 128 && SAFE[c]) {
        out.append((char) c);
      } else {
        out.append('%').append(HEX.toHexDigits((byte) c));
      }
    }
    return out.toString();
  }
}
