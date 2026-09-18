import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A road network (intersections around downtown Madison, WI and UW-Madison
 * campus) used to demonstrate DijkstraGraph as a real route-planning graph.
 * Nodes carry a display name and lat/lon so the frontend can plot them on a
 * map; edges are directed with a distance-in-miles weight, including some
 * one-way streets so shortest-path direction actually matters. Data is
 * loaded from data/locations.csv and data/roads.csv by RoadNetworkLoader
 * (see data/sources.md for provenance).
 */
public class RoadNetwork {

  /** A named intersection, with coordinates for map rendering. */
  public record Intersection(String id, String name, double lat, double lon) {}

  /**
   * A directed road segment between two intersections, weighted by miles.
   * {@code busRoute} is the real Madison Metro Transit route (per
   * cityofmadison.com/metro) that runs this corridor, or null for a
   * walk-only segment; used to estimate travel time and to suggest a bus.
   */
  public record Road(String from, String to, double miles, String busRoute) {}

  private final DijkstraGraph<String, Double> graph = new DijkstraGraph<>();
  private final Map<String, Intersection> intersections = new LinkedHashMap<>();
  private final List<Road> roads = new ArrayList<>();
  private final Map<String, Road> roadIndex = new LinkedHashMap<>();

  public RoadNetwork() {
    this(RoadNetworkLoader.load());
  }

  /** Builds the network from already-loaded data -- exposed for tests and future alternate datasets. */
  RoadNetwork(RoadNetworkLoader.NetworkData data) {
    for (Intersection i : data.intersections()) {
      intersections.put(i.id(), i);
      graph.insertNode(i.id());
    }
    for (Road r : data.roads()) {
      roads.add(r);
      roadIndex.put(r.from() + "->" + r.to(), r);
      graph.insertEdge(r.from(), r.to(), r.miles());
    }
  }

  /** The Road record for a direct leg from `from` to `to`, or null if there isn't one. */
  public Road roadBetween(String from, String to) {
    return roadIndex.get(from + "->" + to);
  }

  // Rough average speeds used to turn a leg's distance into an estimated
  // travel time: a city bus (including stops) is faster than walking, but
  // nowhere near highway speed.
  private static final double WALK_MPH = 3.5;
  private static final double BUS_MPH = 12.0;

  /** Estimated minutes to cover `miles`, walking or riding `busRoute` if given. */
  public static int estimatedMinutes(double miles, String busRoute) {
    double mph = busRoute == null ? WALK_MPH : BUS_MPH;
    return (int) Math.max(1, Math.round(miles / mph * 60));
  }

  public DijkstraGraph<String, Double> graph() {
    return graph;
  }

  public Collection<Intersection> intersections() {
    return intersections.values();
  }

  public boolean hasIntersection(String id) {
    return intersections.containsKey(id);
  }

  public String nameOf(String id) {
    Intersection i = intersections.get(id);
    return i == null ? id : i.name();
  }

  public List<Road> roads() {
    return roads;
  }

  private static final double EARTH_RADIUS_MILES = 3958.8;

  /**
   * A haversine-distance {@link AStarHeuristic} over this network's real
   * coordinates -- admissible for A* and bidirectional A*, since a
   * straight-line distance can never exceed the real road distance
   * between two points.
   */
  public AStarHeuristic<String> haversineHeuristic() {
    return (fromId, toId) -> {
      Intersection from = intersections.get(fromId);
      Intersection to = intersections.get(toId);
      return haversineMiles(from.lat(), from.lon(), to.lat(), to.lon());
    };
  }

  private static double haversineMiles(double lat1, double lon1, double lat2, double lon2) {
    double phi1 = Math.toRadians(lat1);
    double phi2 = Math.toRadians(lat2);
    double dPhi = Math.toRadians(lat2 - lat1);
    double dLambda = Math.toRadians(lon2 - lon1);
    double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
        + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
    return 2 * EARTH_RADIUS_MILES * Math.asin(Math.min(1.0, Math.sqrt(a)));
  }
}
