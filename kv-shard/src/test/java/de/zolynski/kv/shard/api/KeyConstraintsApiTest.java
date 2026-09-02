package de.zolynski.kv.shard.api;

import de.zolynski.kv.core.store.EntryRules;
import de.zolynski.kv.core.store.InvalidEntryException;
import de.zolynski.kv.core.store.Store;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"kv.shard.count=1", "kv.shard.ordinal=0"})
class KeyConstraintsApiTest {

  @LocalServerPort
  int port;

  @Autowired
  Store store;

  RestClient client;

  @BeforeEach
  void setUp() {
    store.clear();
    client = RestClient.builder()
            .defaultStatusHandler(status -> true, (_, _) -> {
            })
            .build();
  }

  @Test
  void aKeyAtExactlyTheLimitIsAcceptedAndOneByteMoreIsRefused() {
    String atLimit = "k".repeat(EntryRules.MAX_KEY_BYTES);
    assertEquals(HttpStatus.CREATED, put(atLimit).getStatusCode());
    assertTrue(store.keys(10).contains(atLimit));

    ResponseEntity<String> tooLong = put("k".repeat(EntryRules.MAX_KEY_BYTES + 1));
    assertEquals(HttpStatus.BAD_REQUEST, tooLong.getStatusCode());
    assertTrue(tooLong.getBody().contains("KEY_TOO_LONG"),
            "the response must name the rule, got: " + tooLong.getBody());
    assertTrue(tooLong.getBody().contains("maxKeyBytes"),
            "the response must state the limit, got: " + tooLong.getBody());
  }

  @Test
  void ourLimitBindsBeforeTheContainersDoes() {
    ResponseEntity<String> response = put("k".repeat(4_000));
    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertTrue(response.getBody() != null && response.getBody().contains("KEY_TOO_LONG"),
            "a 4,000 byte key must be refused by our rule, not tolerated by the container");
  }

  @Test
  void controlCharactersAreRefusedByRuleWithTheRuleNamed() {
    ResponseEntity<String> response = put("a\nb");
    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertTrue(response.getBody().contains("KEY_CONTROL_CHARACTER"),
            "got: " + response.getBody());
    assertEquals(0, store.size());
  }

  @Test
  void multiByteKeysAreMeasuredInBytesSoTheRuleIsPredictable() {
    assertEquals(HttpStatus.CREATED,
            put("ä".repeat(EntryRules.MAX_KEY_BYTES / 2)).getStatusCode());
    assertEquals(HttpStatus.BAD_REQUEST,
            put("ä".repeat(EntryRules.MAX_KEY_BYTES / 2 + 1)).getStatusCode());
  }

  @Test
  void theEmptyKeyIsLegal() {
    assertEquals(HttpStatus.CREATED, put("").getStatusCode());
    assertEquals("v", get("").getBody());
    assertTrue(store.keys(10).contains(""));
  }

  @Test
  void reservedAndAwkwardCharactersStillSurviveIntact() {
    for (String key : new String[]{
            "a/b/c", "a//b", "a;b", "a+b", "a b", "100%", "a#b?c", "{braced}",
            "ключ", "🚀", "tombstone"}) {
      assertEquals(HttpStatus.CREATED, put(key).getStatusCode(), "rejected: " + key);
      assertTrue(store.keys(100).contains(key), "mangled in transit: " + key);
    }
  }

  @Test
  void percentEncodedAndLiteralFormsOfTheSameKeyAreTheSameKey() {
    assertEquals(HttpStatus.CREATED, putRaw("a+b").getStatusCode());
    assertEquals(HttpStatus.OK, putRaw("a%2Bb").getStatusCode(),
            "the encoded form should have found the existing key");
    assertEquals(1, store.size());
  }

  @Test
  void everyPrintableAsciiCharacterIsEitherStorableOrRefusedByANamedRule() {
    for (char c = 0x20; c < 0x7F; c++) {
      String key = "a" + c + "b";
      ResponseEntity<String> response = put(key);
      int status = response.getStatusCode().value();

      if (c == '\\') {
        assertEquals(400, status, "backslash must stay refused");
        store.clear();
        continue;
      }

      if (status == 201) {
        assertTrue(store.keys(500).contains(key),
                "accepted but stored under a different name: " + describe(c));
      } else {
        assertEquals(400, status, "unexpected status for " + describe(c));
        assertTrue(response.getBody() != null && response.getBody().contains("\"rule\""),
                "refused without naming a rule: " + describe(c)
                        + " -> " + response.getBody());
      }
      store.clear();
    }
  }

  private static String describe(char c) {
    return "'" + c + "' (U+" + String.format("%04X", (int) c) + ")";
  }

  @Test
  void backslashKeysCannotExistAtAll() {
    assertEquals(HttpStatus.BAD_REQUEST, put("back\\slash").getStatusCode());
    assertEquals(0, store.size());

    assertEquals(EntryRules.Rule.KEY_RESERVED_CHARACTER, assertThrows(InvalidEntryException.class,
            () -> store.put("back\\slash", "v")).rule(), "the store itself must refuse it by the named rule, so the key space stays "
            + "exactly what HTTP can address");
  }

  @Test
  void encodedSlashIsRefusedByTheContainerButCostsNoKey() {
    assertEquals(HttpStatus.BAD_REQUEST, putRaw("a%2Fb").getStatusCode());
    assertEquals(HttpStatus.CREATED, putRaw("a/b").getStatusCode());
    assertTrue(store.keys(10).contains("a/b"));
  }

  @Test
  void encodedNullIsRefusedTwiceOverWhichIsFine() {
    assertEquals(HttpStatus.BAD_REQUEST, putRaw("a%00b").getStatusCode());
  }

  @Test
  void dotSegmentsReachTheStoreIntact() {
    assertEquals(HttpStatus.CREATED, put("a/../b").getStatusCode());
    assertTrue(store.keys(10).contains("a/../b"),
            "the server must store the key it was given, got " + store.keys(10));
    assertEquals("v", get("a/../b").getBody());

    store.clear();
    put("a//b");
    assertTrue(store.keys(10).contains("a//b"), "empty segments are preserved too");
    assertNotEquals("a/b", store.keys(10).getFirst());
  }

  @Test
  void percentEncodedDotSegmentsDecodeBackToDots() {
    assertEquals(HttpStatus.CREATED, putRaw("r/%2E%2E/s").getStatusCode());
    assertTrue(store.keys(10).contains("r/../s"),
            "encoded dots should decode back to a literal dot segment, got " + store.keys(10));
    assertTrue(store.get("r/%2E%2E/s").isEmpty(), "the key is the decoded form, not the encoded one");
  }

  private ResponseEntity<String> put(String key) {
    return putRaw(encode(key));
  }

  private ResponseEntity<String> putRaw(String encodedKey) {
    return client.put()
            .uri(URI.create("http://localhost:" + port + "/internal/keys/" + encodedKey))
            .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
            .body("v")
            .retrieve()
            .toEntity(String.class);
  }

  private ResponseEntity<String> get(String key) {
    return client.get()
            .uri(URI.create("http://localhost:" + port + "/internal/keys/" + encode(key)))
            .retrieve()
            .toEntity(String.class);
  }

  private static final HexFormat HEX = HexFormat.of().withUpperCase();

  private static String encode(String key) {
    StringBuilder out = new StringBuilder();
    for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
      int c = b & 0xFF;
      boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
              || (c >= '0' && c <= '9') || "-._~!$&'()*+,=:@/".indexOf(c) >= 0;
      if (safe) {
        out.append((char) c);
      } else {
        out.append('%').append(HEX.toHexDigits((byte) c));
      }
    }
    return out.toString();
  }
}
