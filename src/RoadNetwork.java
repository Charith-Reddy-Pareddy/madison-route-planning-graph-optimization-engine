import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, fixed road network (intersections around downtown Madison, WI)
 * used to demonstrate DijkstraGraph as a real route-planning graph. Nodes
 * carry a display name and lat/lon so the frontend can plot them on a map;
 * edges are directed with a distance-in-miles weight, including some
 * one-way streets so shortest-path direction actually matters.
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
    addIntersection("capitol", "Capitol Square", 43.0747, -89.3844);
    addIntersection("king_st", "King St & Main St", 43.0721, -89.3812);
    addIntersection("willy_st", "Williamson St", 43.0782, -89.3739);
    addIntersection("john_nolen", "John Nolen Dr & Broom St", 43.0679, -89.3852);
    addIntersection("state_frances", "State St & Frances St", 43.0745, -89.3891);
    addIntersection("state_gilman", "State St & Gilman St", 43.0752, -89.3936);
    addIntersection("library_mall", "Library Mall", 43.0736, -89.3988);
    addIntersection("memorial_union", "Memorial Union", 43.0760, -89.4013);
    addIntersection("bascom_hill", "Bascom Hill", 43.0755, -89.4062);
    addIntersection("camp_randall", "Camp Randall Stadium", 43.0697, -89.4126);
    addIntersection("regent_park", "Regent St & Park St", 43.0672, -89.3993);
    addIntersection("monroe_edgewood", "Monroe St & Edgewood Ave", 43.0619, -89.4176);
    addIntersection("east_wash", "East Washington Ave & Blair St", 43.0762, -89.3695);
    addIntersection("tenney_park", "Tenney Park", 43.0862, -89.3661);
    addIntersection("atwood_schenks", "Atwood Ave & Schenk's Corners", 43.0782, -89.3562);
    addIntersection("olbrich_gardens", "Olbrich Gardens", 43.0794, -89.3428);
    addIntersection("arboretum", "UW Arboretum", 43.0489, -89.4257);
    addIntersection("hilldale", "Hilldale", 43.0759, -89.4536);
    addIntersection("morgridge_hall", "Morgridge Hall", 43.0728, -89.4033);
    addIntersection("union_south", "Union South", 43.0703, -89.4104);
    addIntersection("kohl_center", "Kohl Center", 43.0704, -89.4133);
    addIntersection("engineering_hall", "Engineering Mall", 43.0718, -89.4083);
    addIntersection("grainger_hall", "Grainger Hall", 43.0757, -89.4008);
    addIntersection("chazen_museum", "Chazen Museum of Art", 43.0745, -89.3925);
    addIntersection("college_library", "College Library (Helen C. White Hall)", 43.0762, -89.3979);
    addIntersection("witte_hall", "Witte Hall", 43.0777, -89.4090);
    addIntersection("sellery_hall", "Sellery Hall", 43.0779, -89.4075);
    addIntersection("ogg_hall", "Ogg Hall", 43.0800, -89.4145);
    addIntersection("chadbourne_hall", "Chadbourne Residential College", 43.0729, -89.4020);
    addIntersection("memorial_library", "Memorial Library", 43.0742, -89.3969);
    addIntersection("humanities_building", "Humanities Building", 43.0742, -89.3999);
    addIntersection("social_sciences", "Social Science Building", 43.0745, -89.4056);
    addIntersection("education_building", "Education Building", 43.0745, -89.4045);
    addIntersection("van_hise_hall", "Van Hise Hall", 43.0762, -89.4070);
    addIntersection("science_hall", "Science Hall", 43.0739, -89.4009);
    addIntersection("van_vleck_hall", "Van Vleck Hall", 43.0765, -89.4037);
    addIntersection("discovery_building", "Wisconsin Institutes for Discovery", 43.0733, -89.4114);
    addIntersection("nicholas_rec", "Nicholas Recreation Center", 43.0743, -89.4116);
    addIntersection("dejope_hall", "Dejope Residence Hall", 43.0784, -89.4200);

    addRoad("capitol", "king_st", 0.30);
    addRoad("king_st", "capitol", 0.30);
    // State St from the Square to Library Mall: served by Metro Transit's
    // Route A (the State St corridor).
    addRoad("capitol", "state_frances", 0.35, "Route A");
    addRoad("state_frances", "capitol", 0.35, "Route A");
    addRoad("capitol", "john_nolen", 0.60);
    // Williamson St ("Willy St"): served by Route C.
    addRoad("king_st", "willy_st", 0.50, "Route C");
    addRoad("willy_st", "king_st", 0.50, "Route C");
    // One-way: no direct return leg from Williamson St to the square.
    addRoad("willy_st", "capitol", 0.55);
    addRoad("state_frances", "state_gilman", 0.35, "Route A");
    addRoad("state_gilman", "state_frances", 0.35, "Route A");
    addRoad("state_gilman", "library_mall", 0.30, "Route A");
    addRoad("library_mall", "state_gilman", 0.30, "Route A");
    addRoad("library_mall", "memorial_union", 0.25);
    addRoad("memorial_union", "library_mall", 0.25);
    addRoad("library_mall", "regent_park", 0.50);
    addRoad("regent_park", "library_mall", 0.50);
    addRoad("memorial_union", "bascom_hill", 0.35);
    addRoad("bascom_hill", "memorial_union", 0.35);
    // One-way: Bascom Hill down to Camp Randall, no direct uphill return.
    addRoad("bascom_hill", "camp_randall", 0.80);
    addRoad("camp_randall", "regent_park", 0.60);
    addRoad("regent_park", "camp_randall", 0.60);
    // Monroe St runs past Camp Randall: served by Route D.
    addRoad("camp_randall", "monroe_edgewood", 0.70, "Route D");
    addRoad("monroe_edgewood", "camp_randall", 0.70, "Route D");
    addRoad("regent_park", "monroe_edgewood", 0.60);
    addRoad("monroe_edgewood", "regent_park", 0.60);
    addRoad("regent_park", "john_nolen", 0.90);
    addRoad("john_nolen", "regent_park", 0.90);
    addRoad("john_nolen", "capitol", 0.60);

    // East Washington Ave: served by Route L.
    addRoad("king_st", "east_wash", 0.45, "Route L");
    addRoad("east_wash", "king_st", 0.45, "Route L");
    addRoad("east_wash", "tenney_park", 0.55);
    addRoad("tenney_park", "east_wash", 0.55);
    addRoad("willy_st", "tenney_park", 0.50);
    addRoad("tenney_park", "willy_st", 0.50);
    // One-way: this stretch of East Wash only runs outbound toward Atwood.
    // The way back to the rest of the network is via Olbrich -> Tenney Park
    // instead, not a straight reversal -- same pattern as the other one-ways.
    addRoad("east_wash", "atwood_schenks", 0.65, "Route L");
    // Atwood Ave: served by Route 38.
    addRoad("atwood_schenks", "olbrich_gardens", 0.50, "Route 38");
    addRoad("olbrich_gardens", "atwood_schenks", 0.50, "Route 38");
    addRoad("olbrich_gardens", "tenney_park", 0.75);
    addRoad("monroe_edgewood", "arboretum", 0.90);
    addRoad("arboretum", "monroe_edgewood", 0.90);
    addRoad("bascom_hill", "hilldale", 0.85);
    addRoad("hilldale", "bascom_hill", 0.85);

    addRoad("bascom_hill", "morgridge_hall", 0.25);
    addRoad("morgridge_hall", "bascom_hill", 0.25);
    addRoad("camp_randall", "union_south", 0.45);
    addRoad("union_south", "camp_randall", 0.45);
    addRoad("camp_randall", "kohl_center", 0.20);
    addRoad("kohl_center", "camp_randall", 0.20);
    addRoad("union_south", "engineering_hall", 0.30);
    addRoad("engineering_hall", "union_south", 0.30);
    // University Ave, like State St, is a Route A corridor.
    addRoad("state_frances", "grainger_hall", 0.30, "Route A");
    addRoad("grainger_hall", "state_frances", 0.30, "Route A");
    addRoad("state_gilman", "chazen_museum", 0.35, "Route A");
    addRoad("chazen_museum", "state_gilman", 0.35, "Route A");
    addRoad("memorial_union", "college_library", 0.30);
    addRoad("college_library", "memorial_union", 0.30);

    // Lakeshore residence halls, chained off Memorial Union along the
    // lakeshore path (a pedestrian/bike path in reality, not a bus route).
    addRoad("memorial_union", "witte_hall", 0.55);
    addRoad("witte_hall", "memorial_union", 0.55);
    addRoad("witte_hall", "sellery_hall", 0.15);
    addRoad("sellery_hall", "witte_hall", 0.15);
    addRoad("witte_hall", "ogg_hall", 0.30);
    addRoad("ogg_hall", "witte_hall", 0.30);
    addRoad("ogg_hall", "dejope_hall", 0.50);
    addRoad("dejope_hall", "ogg_hall", 0.50);

    addRoad("bascom_hill", "chadbourne_hall", 0.35);
    addRoad("chadbourne_hall", "bascom_hill", 0.35);
    addRoad("bascom_hill", "social_sciences", 0.30);
    addRoad("social_sciences", "bascom_hill", 0.30);
    addRoad("morgridge_hall", "education_building", 0.15);
    addRoad("education_building", "morgridge_hall", 0.15);

    // Memorial Library fronts State St itself: also a Route A stop.
    addRoad("state_frances", "memorial_library", 0.20, "Route A");
    addRoad("memorial_library", "state_frances", 0.20, "Route A");
    addRoad("memorial_library", "humanities_building", 0.15);
    addRoad("humanities_building", "memorial_library", 0.15);
    addRoad("memorial_library", "science_hall", 0.10);
    addRoad("science_hall", "memorial_library", 0.10);

    addRoad("college_library", "van_hise_hall", 0.40);
    addRoad("van_hise_hall", "college_library", 0.40);
    addRoad("college_library", "van_vleck_hall", 0.35);
    addRoad("van_vleck_hall", "college_library", 0.35);
    addRoad("engineering_hall", "discovery_building", 0.50);
    addRoad("discovery_building", "engineering_hall", 0.50);
    addRoad("union_south", "nicholas_rec", 0.25);
    addRoad("nicholas_rec", "union_south", 0.25);
  }

  private void addIntersection(String id, String name, double lat, double lon) {
    intersections.put(id, new Intersection(id, name, lat, lon));
    graph.insertNode(id);
  }

  private void addRoad(String from, String to, double miles) {
    addRoad(from, to, miles, null);
  }

  private void addRoad(String from, String to, double miles, String busRoute) {
    Road road = new Road(from, to, miles, busRoute);
    roads.add(road);
    roadIndex.put(from + "->" + to, road);
    graph.insertEdge(from, to, miles);
  }

  /** The Road record for a direct leg from `from` to `to`, or null if there isn't one. */
  public Road roadBetween(String from, String to) {
    return roadIndex.get(from + "->" + to);
  }

  // Rough average speeds used to turn a leg's distance into an estimated
  // travel time: a city bus (including stops) is faster than walking, but
  // nowhere near highway speed.
  private static final double WALK_MPH = 3.0;
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
}
