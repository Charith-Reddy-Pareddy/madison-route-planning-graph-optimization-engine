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

  /** Which travel mode a route is being planned for -- each has its own graph and its own real distances. */
  public enum Mode {
    WALK,
    DRIVE,
    /** Walking, but the graph excludes any real steps segment and any real incline over 8% (see {@link Road}). */
    ACCESSIBLE
  }

  /**
   * A directed road segment between two intersections, with a real distance for each mode (see
   * scripts/compute_route_distances.py and data/sources.md). {@code driveMiles} is null for the
   * handful of edges with no drivable route found in the real street data -- that edge simply
   * isn't part of the drive-mode graph. {@code busRoute} is the real Madison Metro Transit route
   * (per cityofmadison.com/metro) that runs this corridor, or null for a walk-only segment; used
   * to estimate travel time and to suggest a bus.
   *
   * <p>{@code hasSteps} and {@code maxInclinePercent} describe the real OSM tags found along this
   * road's actual default walking route (see scripts/compute_accessibility.py) -- 38% of roads in
   * this network currently route through real stairs on their shortest path. {@code
   * accessibleMiles} is a real alternate distance computed over a walk graph that excludes any
   * real steps segment and any real incline tag over 8%, or null if no such route exists at all
   * (a real, honest fact about some parts of this hilly campus, not a bug -- see data/sources.md)
   * -- selectable as {@link Mode#ACCESSIBLE}.
   */
  public record Road(
      String from, String to, double walkMiles, Double driveMiles, Boolean hasSteps, Double maxInclinePercent,
      Double accessibleMiles, String busRoute) {
    /** The real distance for this road in the given mode, or null if this road doesn't exist in that mode. */
    public Double milesFor(Mode mode) {
      return switch (mode) {
        case WALK -> walkMiles;
        case DRIVE -> driveMiles;
        case ACCESSIBLE -> accessibleMiles;
      };
    }
  }

  private final DijkstraGraph<String, Double> walkGraph = new DijkstraGraph<>();
  private final DijkstraGraph<String, Double> driveGraph = new DijkstraGraph<>();
  private final DijkstraGraph<String, Double> accessibleGraph = new DijkstraGraph<>();
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
      walkGraph.insertNode(i.id());
      driveGraph.insertNode(i.id());
      accessibleGraph.insertNode(i.id());
    }
    for (Road r : data.roads()) {
      roads.add(r);
      roadIndex.put(r.from() + "->" + r.to(), r);
      walkGraph.insertEdge(r.from(), r.to(), r.walkMiles());
      if (r.driveMiles() != null) {
        driveGraph.insertEdge(r.from(), r.to(), r.driveMiles());
      }
      if (r.accessibleMiles() != null) {
        accessibleGraph.insertEdge(r.from(), r.to(), r.accessibleMiles());
      }
    }
  }

  /** The Road record for a direct leg from `from` to `to`, or null if there isn't one. */
  public Road roadBetween(String from, String to) {
    return roadIndex.get(from + "->" + to);
  }

  // Rough average speeds used to turn a leg's distance into an estimated travel time: a city bus
  // (including stops) is faster than walking but nowhere near highway speed; city driving
  // (including lights and turns) is faster still but nowhere near highway speed either.
  private static final double WALK_MPH = 3.5;
  private static final double BUS_MPH = 12.0;
  private static final double DRIVE_MPH = 20.0;

  /** Estimated minutes to cover `miles` in `mode`, riding `busRoute` instead of walking it if given (WALK/ACCESSIBLE only). */
  public static int estimatedMinutes(double miles, Mode mode, String busRoute) {
    double mph = mode == Mode.DRIVE ? DRIVE_MPH : (busRoute == null ? WALK_MPH : BUS_MPH);
    return (int) Math.max(1, Math.round(miles / mph * 60));
  }

  /** The walk-mode graph -- kept as the default {@link #graph()} for callers that predate travel modes. */
  public DijkstraGraph<String, Double> graph() {
    return graph(Mode.WALK);
  }

  public DijkstraGraph<String, Double> graph(Mode mode) {
    return switch (mode) {
      case WALK -> walkGraph;
      case DRIVE -> driveGraph;
      case ACCESSIBLE -> accessibleGraph;
    };
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

  public Intersection intersectionOf(String id) {
    return intersections.get(id);
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
