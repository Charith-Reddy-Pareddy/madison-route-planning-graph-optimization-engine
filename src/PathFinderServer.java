import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * A small Java backend, built on the JDK's built-in HTTP server, that
 * exposes the road network's graph and its Dijkstra shortest-path
 * computation over HTTP, and serves the static frontend from webRoot.
 *
 * <p>API:
 * <ul>
 *   <li>{@code GET /api/graph} - all intersections and roads, for the map.
 *   <li>{@code GET /api/route?start=ID&end=ID} - shortest path + cost.
 * </ul>
 */
public class PathFinderServer {

  private final RoadNetwork network;
  private final Path webRoot;
  private HttpServer server;
  private ExecutorService executor;

  public PathFinderServer(RoadNetwork network, Path webRoot) {
    this.network = network;
    this.webRoot = webRoot;
  }

  public void start(int port) throws IOException {
    server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/api/health", guarded(PathFinderServer::handleHealth));
    server.createContext("/api/graph", guarded(this::handleGraph));
    server.createContext("/api/route", guarded(this::handleRoute));
    server.createContext("/", guarded(this::handleStatic));
    // The default executor (null) handles one request at a time on a single
    // thread -- a slow request blocks every other client. Virtual threads
    // give each request its own thread at near-zero cost instead.
    executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.start();
  }

  /**
   * Wraps a handler so an unexpected exception becomes a clean 500 JSON
   * response instead of a broken/empty connection -- the JDK's HttpServer
   * doesn't do this itself, it just logs to stderr and drops the exchange.
   */
  private static HttpHandler guarded(HttpHandler handler) {
    return exchange -> {
      try {
        handler.handle(exchange);
      } catch (Exception e) {
        System.err.println("Unhandled error handling " + exchange.getRequestURI() + ": " + e);
        e.printStackTrace();
        sendJson(exchange, 500, Json.error("internal server error"));
      }
    };
  }

  public void stop() {
    if (server != null) {
      server.stop(0);
    }
    // HttpServer.stop() does not shut down a custom executor -- it's ours to close.
    if (executor != null) {
      executor.shutdown();
    }
  }

  /** The actual bound port -- useful when started on port 0 for tests. */
  public int port() {
    return server.getAddress().getPort();
  }

  // --- API handlers --------------------------------------------------

  private static void handleHealth(HttpExchange exchange) throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      sendJson(exchange, 405, Json.error("method not allowed"));
      return;
    }
    sendJson(exchange, 200, "{\"status\":\"ok\"}");
  }

  private void handleGraph(HttpExchange exchange) throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      sendJson(exchange, 405, Json.error("method not allowed"));
      return;
    }
    StringBuilder nodesJson = new StringBuilder("[");
    Iterator<RoadNetwork.Intersection> nodeIter = network.intersections().iterator();
    while (nodeIter.hasNext()) {
      RoadNetwork.Intersection i = nodeIter.next();
      nodesJson.append("{\"id\":").append(Json.string(i.id()))
          .append(",\"name\":").append(Json.string(i.name()))
          .append(",\"lat\":").append(Json.number(i.lat()))
          .append(",\"lon\":").append(Json.number(i.lon()))
          .append("}");
      if (nodeIter.hasNext()) nodesJson.append(",");
    }
    nodesJson.append("]");

    StringBuilder edgesJson = new StringBuilder("[");
    List<RoadNetwork.Road> roads = network.roads();
    for (int i = 0; i < roads.size(); i++) {
      RoadNetwork.Road r = roads.get(i);
      edgesJson.append("{\"from\":").append(Json.string(r.from()))
          .append(",\"to\":").append(Json.string(r.to()))
          .append(",\"miles\":").append(Json.number(r.miles()))
          .append(",\"busRoute\":").append(Json.stringOrNull(r.busRoute()))
          .append("}");
      if (i < roads.size() - 1) edgesJson.append(",");
    }
    edgesJson.append("]");

    String body = "{\"nodes\":" + nodesJson + ",\"edges\":" + edgesJson + "}";
    sendJson(exchange, 200, body);
  }

  private void handleRoute(HttpExchange exchange) throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      sendJson(exchange, 405, Json.error("method not allowed"));
      return;
    }
    Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
    String start = params.get("start");
    String end = params.get("end");
    if (start == null || start.isBlank() || end == null || end.isBlank()) {
      sendJson(exchange, 400, Json.error("both 'start' and 'end' query parameters are required"));
      return;
    }
    if (!network.hasIntersection(start) || !network.hasIntersection(end)) {
      sendJson(exchange, 404, Json.error("unknown intersection id"));
      return;
    }

    try {
      List<String> path = network.graph().shortestPathData(start, end);
      // Dijkstra accumulates cost as a running sum of edge weights, which can
      // land a hair off a "clean" decimal (e.g. 1.7000000000000002) due to
      // binary floating-point rounding -- round for display, not just for looks.
      double cost = round2(network.graph().shortestPathCost(start, end));

      List<String> pathEntries = new ArrayList<>();
      List<String> segmentEntries = new ArrayList<>();
      int totalMinutes = 0;
      for (int i = 0; i < path.size(); i++) {
        String id = path.get(i);
        pathEntries.add("{\"id\":" + Json.string(id) + ",\"name\":" + Json.string(network.nameOf(id)) + "}");
        if (i < path.size() - 1) {
          String nextId = path.get(i + 1);
          RoadNetwork.Road road = network.roadBetween(id, nextId);
          double legMiles = round2(road.miles());
          // Walking pace regardless of whether a bus also covers this leg --
          // this is the on-foot time; the frontend estimates bus-trip time
          // separately for the legs it groups into an actual bus ride.
          int minutes = RoadNetwork.estimatedMinutes(road.miles(), null);
          totalMinutes += minutes;
          segmentEntries.add("{\"from\":" + Json.string(id)
              + ",\"to\":" + Json.string(nextId)
              + ",\"miles\":" + Json.number(legMiles)
              + ",\"minutes\":" + minutes
              + ",\"busRoute\":" + Json.stringOrNull(road.busRoute()) + "}");
        }
      }

      String body = "{\"path\":[" + String.join(",", pathEntries) + "]"
          + ",\"segments\":[" + String.join(",", segmentEntries) + "]"
          + ",\"totalMiles\":" + Json.number(cost)
          + ",\"totalMinutes\":" + totalMinutes + "}";
      sendJson(exchange, 200, body);
    } catch (NoSuchElementException e) {
      sendJson(exchange, 404, Json.error("no route found between those intersections"));
    }
  }

  private static double round2(double d) {
    return Math.round(d * 100.0) / 100.0;
  }

  private static Map<String, String> parseQuery(String rawQuery) {
    Map<String, String> params = new LinkedHashMap<>();
    if (rawQuery == null || rawQuery.isEmpty()) {
      return params;
    }
    for (String pair : rawQuery.split("&")) {
      int eq = pair.indexOf('=');
      String key = eq >= 0 ? pair.substring(0, eq) : pair;
      String value = eq >= 0 ? pair.substring(eq + 1) : "";
      params.put(
          URLDecoder.decode(key, StandardCharsets.UTF_8),
          URLDecoder.decode(value, StandardCharsets.UTF_8));
    }
    return params;
  }

  private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  // --- Static file handler --------------------------------------------

  private void handleStatic(HttpExchange exchange) throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(405, -1);
      exchange.close();
      return;
    }
    String requested = exchange.getRequestURI().getPath();
    if (requested.equals("/")) {
      requested = "/index.html";
    }
    Path resolved = webRoot.resolve("." + requested).normalize();
    if (!resolved.startsWith(webRoot) || !Files.isRegularFile(resolved)) {
      byte[] notFound = "404 not found".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(404, notFound.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(notFound);
      }
      return;
    }
    exchange.getResponseHeaders().set("Content-Type", contentTypeFor(resolved));
    // Vite fingerprints filenames under /assets/ (content-hash in the name),
    // so those are safe to cache forever; index.html must always revalidate
    // so a new deploy's asset references actually get picked up.
    exchange.getResponseHeaders().set("Cache-Control",
        requested.startsWith("/assets/") ? "public, max-age=31536000, immutable" : "no-cache");
    byte[] content = Files.readAllBytes(resolved);
    exchange.sendResponseHeaders(200, content.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(content);
    }
  }

  private static String contentTypeFor(Path path) {
    String name = path.getFileName().toString();
    if (name.endsWith(".html")) return "text/html; charset=utf-8";
    if (name.endsWith(".css")) return "text/css; charset=utf-8";
    if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
    if (name.endsWith(".svg")) return "image/svg+xml";
    return "application/octet-stream";
  }
}
