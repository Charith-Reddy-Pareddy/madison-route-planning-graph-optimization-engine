import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration tests: starts the real PathFinderServer on an
 * ephemeral port and drives it over real HTTP with java.net.http.HttpClient,
 * covering the full request/response cycle (routing, query parsing, JSON
 * serialization, and status codes) rather than calling the graph directly.
 */
public class PathFinderServerIntegrationTest {

  private static PathFinderServer server;
  private static RoadNetwork network;
  private static HttpClient client;
  private static String baseUrl;

  @BeforeAll
  public static void startServer() throws IOException {
    network = new RoadNetwork();
    server = new PathFinderServer(network, Path.of("web"));
    server.start(0); // ephemeral port so tests never collide with a running dev server
    baseUrl = "http://localhost:" + server.port();
    client = HttpClient.newHttpClient();
  }

  @AfterAll
  public static void stopServer() {
    server.stop();
  }

  private static final Pattern TRAILING_COMMA = Pattern.compile(",\\s*[}\\]]");

  private HttpResponse<String> get(String pathAndQuery) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery)).GET().build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if ("application/json; charset=utf-8".equals(response.headers().firstValue("Content-Type").orElse(""))) {
      assertTrue(!TRAILING_COMMA.matcher(response.body()).find(),
          "response body has a trailing comma (invalid JSON): " + response.body());
    }
    return response;
  }

  @Test
  public void graphEndpointListsAllIntersectionsAndRoads() throws Exception {
    HttpResponse<String> resp = get("/api/graph");
    assertEquals(200, resp.statusCode());
    assertTrue(resp.body().contains("\"nodes\""));
    assertTrue(resp.body().contains("\"edges\""));
    assertTrue(resp.body().contains("Capitol Square"));
  }

  @Test
  public void routeEndpointMatchesDirectAlgorithmResult() throws Exception {
    // Cross-check the HTTP layer against calling the algorithm directly,
    // so this test catches wiring/serialization bugs, not just algorithm bugs.
    // Rounded the same way PathFinderServer rounds for display -- see
    // floatingPointSummationNoiseIsRoundedAway below for why that matters.
    double expectedCost = Math.round(network.graph().shortestPathCost("capitol", "camp_randall") * 100.0) / 100.0;

    HttpResponse<String> resp = get("/api/route?start=capitol&end=camp_randall");
    assertEquals(200, resp.statusCode());
    assertTrue(resp.body().contains("\"totalMiles\":" + Json.number(expectedCost)));
    assertTrue(resp.body().contains("\"id\":\"capitol\""));
    assertTrue(resp.body().contains("\"id\":\"camp_randall\""));
  }

  @Test
  public void floatingPointSummationNoiseIsRoundedAway() throws Exception {
    // Dijkstra accumulates cost as a running sum of edge weights, which can
    // land a hair off a "clean" decimal (e.g. 1.7000000000000002 instead of
    // 1.7) due to binary floating-point rounding, depending on exactly which
    // weights get summed -- the server rounds for display so that never
    // leaks, whichever pair of nodes and path that turns out to affect.
    // bascom_hill -> camp_randall is one-way, so the reverse trip is forced
    // onto a different, longer path than the direct one -- exercising a
    // real multi-edge summation rather than a single edge weight.
    double expectedCost = Math.round(network.graph().shortestPathCost("camp_randall", "bascom_hill") * 100.0) / 100.0;
    HttpResponse<String> resp = get("/api/route?start=camp_randall&end=bascom_hill");
    assertEquals(200, resp.statusCode());
    assertTrue(resp.body().contains("\"totalMiles\":" + Json.number(expectedCost)),
        "expected a clean " + expectedCost + ", got: " + resp.body());
    assertTrue(!resp.body().contains("000000"), "response leaked floating-point noise: " + resp.body());
  }

  @Test
  public void routeEndpointReturns404ForUnknownIntersection() throws Exception {
    HttpResponse<String> resp = get("/api/route?start=capitol&end=nowhere");
    assertEquals(404, resp.statusCode());
    assertTrue(resp.body().contains("error"));
  }

  @Test
  public void routeEndpointReturns400WhenParamsMissing() throws Exception {
    HttpResponse<String> resp = get("/api/route?start=capitol");
    assertEquals(400, resp.statusCode());
    assertTrue(resp.body().contains("error"));
  }

  @Test
  public void routeEndpointHandlesSameStartAndEnd() throws Exception {
    HttpResponse<String> resp = get("/api/route?start=capitol&end=capitol");
    assertEquals(200, resp.statusCode());
    assertTrue(resp.body().contains("\"totalMiles\":0"));
  }

  @Test
  public void staticIndexPageIsServed() throws Exception {
    HttpResponse<String> resp = get("/");
    assertEquals(200, resp.statusCode());
    assertTrue(resp.body().contains("<html"));
  }

  @Test
  public void healthEndpointReportsOk() throws Exception {
    HttpResponse<String> resp = get("/api/health");
    assertEquals(200, resp.statusCode());
    assertTrue(resp.body().contains("\"status\":\"ok\""));
  }

  @Test
  public void hashedAssetsAreCachedForeverButIndexHtmlIsNot() throws Exception {
    HttpResponse<String> index = get("/");
    assertEquals("no-cache", index.headers().firstValue("Cache-Control").orElse(null));

    Matcher assetRef = Pattern.compile("/assets/[^\"]+\\.js").matcher(index.body());
    assertTrue(assetRef.find(), "expected index.html to reference a hashed JS asset: " + index.body());

    HttpResponse<String> asset = get(assetRef.group());
    assertEquals(200, asset.statusCode());
    assertEquals("public, max-age=31536000, immutable", asset.headers().firstValue("Cache-Control").orElse(null));
  }

  @Test
  public void handlesConcurrentRequestsWithoutSerializing() throws Exception {
    // Regression test for server.setExecutor(null), which processes one
    // request at a time on a single thread -- with that bug, this many
    // concurrent requests would take far longer than the assertion below.
    int concurrency = 20;
    ExecutorService pool = Executors.newFixedThreadPool(concurrency);
    List<Callable<HttpResponse<String>>> tasks = new ArrayList<>();
    for (int i = 0; i < concurrency; i++) {
      tasks.add(() -> get("/api/route?start=capitol&end=camp_randall"));
    }

    long startNanos = System.nanoTime();
    List<Future<HttpResponse<String>>> results = pool.invokeAll(tasks);
    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
    pool.shutdown();

    for (Future<HttpResponse<String>> result : results) {
      assertEquals(200, result.get().statusCode());
    }
    assertTrue(elapsedMs < 5000, concurrency + " concurrent requests took " + elapsedMs + "ms -- looks serialized");
  }
}
