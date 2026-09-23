import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calls the public OSRM demo routing API (router.project-osrm.org) for a real, live driving
 * distance between two coordinates -- the live-data counterpart to the precomputed {@code
 * driveMiles} in data/roads.csv (see scripts/compute_route_distances.py and data/sources.md).
 * Used only when a route is requested with {@code mode=drive&live=true}; every other request
 * reads the precomputed graph and never touches the network, so this stays strictly opt-in and
 * never becomes a hard dependency for the rest of the app.
 *
 * <p>OSRM's public demo server only reliably serves the "driving" profile (not walking), so live
 * mode is drive-only -- see {@link PathFinderServer#handleRoute}. A short timeout and a null
 * return on any failure (bad response, timeout, network error, rate limit) let the caller fall
 * back to the precomputed distance for that one leg rather than fail the whole route -- a public
 * demo server having a bad moment shouldn't take a route request down with it.
 */
public class LiveRoutingClient {

  private static final String BASE_URL = "http://router.project-osrm.org/route/v1/driving/";
  private static final Duration TIMEOUT = Duration.ofSeconds(4);
  private static final double METERS_PER_MILE = 1609.344;

  // OSRM's response nests "distance" at both the route level and each leg level, with the same
  // value for a simple two-waypoint query (one leg) -- the first match in the body is enough,
  // without pulling in a full JSON parser for one numeric field from a response shape we control
  // the request side of.
  private static final Pattern DISTANCE_PATTERN = Pattern.compile("\"distance\"\\s*:\\s*([0-9.]+)");

  private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

  /** Real live driving distance in miles between two coordinates, or null if the live call failed for any reason. */
  public Double liveDriveMiles(double fromLat, double fromLon, double toLat, double toLon) {
    String url = BASE_URL + fromLon + "," + fromLat + ";" + toLon + "," + toLat + "?overview=false";
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return null;
      }
      Matcher matcher = DISTANCE_PATTERN.matcher(response.body());
      if (!matcher.find()) {
        return null;
      }
      double meters = Double.parseDouble(matcher.group(1));
      return meters / METERS_PER_MILE;
    } catch (Exception e) {
      // Network error, timeout, malformed response -- any of these means "no live data this
      // time," not "crash the request." The caller falls back to the precomputed distance.
      return null;
    }
  }
}
