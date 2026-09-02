package de.zolynski.kv.router;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/// A shard, standing in for the real service during router tests.
final class StubShard implements AutoCloseable {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int DEFAULT_LIMIT = 100;

  private final HttpServer server;
  private final Map<String, String> entries = new ConcurrentHashMap<>();
  private final int ordinal;
  private final AtomicInteger requestCount = new AtomicInteger();

  /// Every data request sleeps this long - used to provoke fan-out timeouts.
  private volatile long delayMillis;

  /// Writes answer 507, as a full store would.
  private volatile boolean full;

  /// Readiness reports 503, as a shard still warming up would.
  private volatile boolean ready = true;

  /// Every request has its connection dropped without a response.
  ///
  /// This rather than stopping the server: the router resolves shard addresses once at startup,
  /// so a stub that stopped and rebound on a fresh ephemeral port would be permanently invisible
  /// to it and would poison every later test in the class.
  private volatile boolean down;

  StubShard(int ordinal) throws IOException {
    this.ordinal = ordinal;
    this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/internal/keys", whenUp(this::handleKeys));
    server.createContext("/internal/stats", whenUp(this::handleStats));
    server.createContext("/actuator/health", whenUp(exchange -> respond(exchange,
            ready ? 200 : 503, Map.of("status", ready ? "UP" : "OUT_OF_SERVICE"))));
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /// The shard's contents, for tests that assert *where* a key landed rather than what came back.
  Map<String, String> entries() {
    return entries;
  }

  int requestCount() {
    return requestCount.get();
  }

  void delay(long millis) {
    this.delayMillis = millis;
  }

  void full(boolean full) {
    this.full = full;
  }

  void ready(boolean ready) {
    this.ready = ready;
  }

  void down(boolean down) {
    this.down = down;
  }

  void reset() {
    entries.clear();
    delayMillis = 0;
    full = false;
    ready = true;
    down = false;
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private HttpHandler whenUp(HttpHandler handler) {
    return exchange -> {
      if (down) {
        exchange.close();
        return;
      }
      handler.handle(exchange);
    };
  }

  private void handleKeys(HttpExchange exchange) throws IOException {
    requestCount.incrementAndGet();
    sleepIfDelayed();

    // getPath(), not getRawPath(): the URI decodes percent-escapes for us and - unlike
    // URLDecoder, which is a *form* decoder - leaves '+' as the literal it is in a path.
    String suffix = exchange.getRequestURI().getPath().substring("/internal/keys".length());
    String method = exchange.getRequestMethod();

    if (suffix.isEmpty() || suffix.equals("/")) {
      handleCollection(exchange, method);
      return;
    }

    String key = suffix.substring(1);
    switch (method) {
      case "PUT" -> {
        if (full) {
          respondText(exchange, 507, "store is full");
          return;
        }
        String value = new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8);
        String previous = entries.put(key, value);
        respondText(exchange, previous == null ? 201 : 200,
                previous == null ? "" : previous);
      }
      case "GET" -> {
        String value = entries.get(key);
        respondText(exchange, value == null ? 404 : 200, value == null ? "" : value);
      }
      case "DELETE" -> respondText(exchange, entries.remove(key) == null ? 404 : 204, "");
      default -> respondText(exchange, 405, "");
    }
  }

  private void handleCollection(HttpExchange exchange, String method) throws IOException {
    if ("DELETE".equals(method)) {
      entries.clear();
      respondText(exchange, 204, "");
      return;
    }
    List<String> keys = entries.keySet().stream().limit(limitFrom(exchange)).toList();
    respond(exchange, 200, keys);
  }

  private void handleStats(HttpExchange exchange) throws IOException {
    respond(exchange, 200, Map.of(
            "ordinal", ordinal,
            "shardCount", 0,
            "store", Map.of("implementation", "StubShard", "entries", entries.size(),
                    "maxEntries", -1, "segments", 1, "capacity", entries.size())));
  }

  private void sleepIfDelayed() {
    if (delayMillis <= 0) {
      return;
    }
    try {
      Thread.sleep(delayMillis);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }

  private static int limitFrom(HttpExchange exchange) {
    String query = exchange.getRequestURI().getQuery();
    if (query == null) {
      return DEFAULT_LIMIT;
    }
    return java.util.Arrays.stream(query.split("&"))
            .filter(part -> part.startsWith("limit="))
            .findFirst()
            .map(part -> Integer.parseInt(part.substring("limit=".length())))
            .orElse(DEFAULT_LIMIT);
  }

  private void respondText(HttpExchange exchange, int status, String body) throws IOException {
    exchange.getResponseHeaders().add("Content-Type", "text/plain;charset=UTF-8");
    exchange.getResponseHeaders().add("X-KV-Shard", Integer.toString(ordinal));
    write(exchange, status, body);
  }

  private static void respond(HttpExchange exchange, int status, Object body) throws IOException {
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    write(exchange, status, JSON.writeValueAsString(body));
  }

  private static void write(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
  }
}
