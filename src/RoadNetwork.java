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
    addIntersection("capitol", "Capitol Square", 43.0747, -89.3842);
    addIntersection("king_st", "King St & Main St", 43.0747, -89.381);
    addIntersection("willy_st", "Williamson St", 43.0767, -89.3739);
    addIntersection("john_nolen", "John Nolen Dr & Broom St", 43.0528, -89.3787);
    addIntersection("state_frances", "State St & Frances St", 43.0751, -89.3957);
    addIntersection("state_gilman", "State St & Gilman St", 43.0748, -89.3908);
    addIntersection("library_mall", "Library Mall", 43.0753, -89.3991);
    addIntersection("memorial_union", "Memorial Union", 43.0765, -89.4003);
    addIntersection("bascom_hill", "Bascom Hill", 43.0753, -89.4038);
    addIntersection("camp_randall", "Camp Randall Stadium", 43.0701, -89.4127);
    addIntersection("regent_park", "Regent St & Park St", 43.0679, -89.4014);
    addIntersection("monroe_edgewood", "Monroe St & Edgewood Ave", 43.0564, -89.4301);
    addIntersection("east_wash", "East Washington Ave & Blair St", 43.0805, -89.3761);
    addIntersection("tenney_park", "Tenney Park", 43.0932, -89.3698);
    addIntersection("atwood_schenks", "Atwood Ave & Schenk's Corners", 43.0923, -89.3551);
    addIntersection("olbrich_gardens", "Olbrich Gardens", 43.0926, -89.3346);
    addIntersection("arboretum", "UW Arboretum", 43.0409, -89.431);
    addIntersection("hilldale", "Hilldale", 43.0732, -89.4531);
    addIntersection("morgridge_hall", "Morgridge Hall", 43.0728, -89.4069);
    addIntersection("union_south", "Union South", 43.0718, -89.4081);
    addIntersection("kohl_center", "Kohl Center", 43.0694, -89.3969);
    addIntersection("engineering_hall", "Engineering Mall", 43.0748, -89.4107);
    addIntersection("grainger_hall", "Grainger Hall", 43.0727, -89.4016);
    addIntersection("chazen_museum", "Chazen Museum of Art", 43.0739, -89.3987);
    addIntersection("college_library", "College Library (Helen C. White Hall)", 43.0767, -89.4013);
    addIntersection("witte_hall", "Witte Residence Hall", 43.0714, -89.397);
    addIntersection("sellery_hall", "Sellery Residence Hall", 43.0716, -89.4002);
    addIntersection("ogg_hall", "Ogg Residence Hall", 43.0706, -89.3999);
    addIntersection("chadbourne_hall", "Chadbourne Residential College", 43.0738, -89.4013);
    addIntersection("memorial_library", "Memorial Library", 43.0754, -89.398);
    addIntersection("humanities_building", "Humanities Building", 43.0742, -89.4001);
    addIntersection("social_sciences", "Social Science Building", 43.0766, -89.4052);
    addIntersection("education_building", "Education Building", 43.0731, -89.4074);
    addIntersection("van_hise_hall", "Van Hise Hall", 43.0756, -89.4069);
    addIntersection("science_hall", "Science Hall", 43.0759, -89.4011);
    addIntersection("van_vleck_hall", "Van Vleck Hall", 43.0748, -89.4049);
    addIntersection("discovery_building", "Wisconsin Institutes for Discovery", 43.0728, -89.4081);
    addIntersection("nicholas_rec", "Nicholas Recreation Center", 43.0704, -89.3983);
    addIntersection("dejope_hall", "Dejope Residence Hall", 43.0778, -89.4178);
    addIntersection("atmosphere_apts", "Atmosphere Madison", 43.0684, -89.3973);
    addIntersection("x01_apts", "X01", 43.0729, -89.4027);
    addIntersection("lucky_apts", "Lucky Apartments", 43.073, -89.398);
    addIntersection("business_school", "Wisconsin School of Business", 43.073, -89.4019);
    addIntersection("red_gym", "Red Gym", 43.0763, -89.3985);
    addIntersection("vilas_hall", "Vilas Hall", 43.0727, -89.3998);
    addIntersection("target_state_st", "Target (State St)", 43.0752, -89.396);
    addIntersection("chipotle_state_st", "Chipotle (State St)", 43.0752, -89.3966);
    addIntersection("popeyes_state_st", "Popeyes (State St)", 43.0751, -89.3964);
    addIntersection("ians_pizza", "Ian's Pizza (State St)", 43.0749, -89.3871);
    addIntersection("middleton_building", "Middleton Building", 43.0748, -89.4082);
    addIntersection("vilas_zoo", "Henry Vilas Zoo", 43.0598, -89.4105);
    addIntersection("elizabeth_waters", "Elizabeth Waters Residence Hall", 43.077, -89.4065);
    addIntersection("slichter_hall", "Slichter Residence Hall", 43.0771, -89.4122);
    addIntersection("kronshage_halls", "Kronshage Residence Halls", 43.0779, -89.4159);
    addIntersection("bradley_hall", "Bradley Residence Hall", 43.078, -89.4164);
    addIntersection("bakke_rec_center", "Bakke Recreation & Wellbeing Center", 43.0769, -89.4198);
    addIntersection("lakeshore_preserve", "Lakeshore Nature Preserve", 43.0833, -89.4303);
    addIntersection("eagle_heights", "Eagle Heights", 43.0884, -89.4364);

    addRoad("capitol", "king_st", 0.16);
    addRoad("king_st", "capitol", 0.16);
    addRoad("capitol", "state_frances", 0.58, "Route A");
    addRoad("state_frances", "capitol", 0.58, "Route A");
    addRoad("capitol", "john_nolen", 1.54);
    addRoad("king_st", "willy_st", 0.38, "Route C");
    addRoad("willy_st", "king_st", 0.38, "Route C");
    addRoad("willy_st", "capitol", 0.54);
    addRoad("state_frances", "state_gilman", 0.25, "Route A");
    addRoad("state_gilman", "state_frances", 0.25, "Route A");
    addRoad("state_gilman", "library_mall", 0.42, "Route A");
    addRoad("library_mall", "state_gilman", 0.42, "Route A");
    addRoad("library_mall", "memorial_union", 0.1);
    addRoad("memorial_union", "library_mall", 0.1);
    addRoad("library_mall", "regent_park", 0.52);
    addRoad("regent_park", "library_mall", 0.52);
    addRoad("memorial_union", "bascom_hill", 0.19);
    addRoad("bascom_hill", "memorial_union", 0.19);
    addRoad("bascom_hill", "camp_randall", 0.57);
    addRoad("camp_randall", "regent_park", 0.59);
    addRoad("regent_park", "camp_randall", 0.59);
    addRoad("camp_randall", "monroe_edgewood", 1.29, "Route D");
    addRoad("monroe_edgewood", "camp_randall", 1.29, "Route D");
    addRoad("regent_park", "monroe_edgewood", 1.65);
    addRoad("monroe_edgewood", "regent_park", 1.65);
    addRoad("regent_park", "john_nolen", 1.55);
    addRoad("john_nolen", "regent_park", 1.55);
    addRoad("john_nolen", "capitol", 1.54);
    addRoad("king_st", "east_wash", 0.47, "Route L");
    addRoad("east_wash", "king_st", 0.47, "Route L");
    addRoad("east_wash", "tenney_park", 0.93);
    addRoad("tenney_park", "east_wash", 0.93);
    addRoad("willy_st", "tenney_park", 1.16);
    addRoad("tenney_park", "willy_st", 1.16);
    addRoad("east_wash", "atwood_schenks", 1.34, "Route L");
    addRoad("atwood_schenks", "olbrich_gardens", 1.03, "Route 38");
    addRoad("olbrich_gardens", "atwood_schenks", 1.03, "Route 38");
    addRoad("olbrich_gardens", "tenney_park", 1.77);
    addRoad("monroe_edgewood", "arboretum", 1.07);
    addRoad("arboretum", "monroe_edgewood", 1.07);
    addRoad("bascom_hill", "hilldale", 2.49);
    addRoad("hilldale", "bascom_hill", 2.49);
    addRoad("bascom_hill", "morgridge_hall", 0.23);
    addRoad("morgridge_hall", "bascom_hill", 0.23);
    addRoad("camp_randall", "union_south", 0.26);
    addRoad("union_south", "camp_randall", 0.26);
    addRoad("camp_randall", "kohl_center", 0.8);
    addRoad("kohl_center", "camp_randall", 0.8);
    addRoad("union_south", "engineering_hall", 0.24);
    addRoad("engineering_hall", "union_south", 0.24);
    addRoad("state_frances", "grainger_hall", 0.34, "Route A");
    addRoad("grainger_hall", "state_frances", 0.34, "Route A");
    addRoad("state_gilman", "chazen_museum", 0.4, "Route A");
    addRoad("chazen_museum", "state_gilman", 0.4, "Route A");
    addRoad("memorial_union", "college_library", 0.05);
    addRoad("college_library", "memorial_union", 0.05);
    addRoad("witte_hall", "sellery_hall", 0.16, "Route B");
    addRoad("sellery_hall", "witte_hall", 0.16, "Route B");
    addRoad("witte_hall", "ogg_hall", 0.16, "Route B");
    addRoad("ogg_hall", "witte_hall", 0.16, "Route B");
    addRoad("bascom_hill", "chadbourne_hall", 0.16);
    addRoad("chadbourne_hall", "bascom_hill", 0.16);
    addRoad("bascom_hill", "social_sciences", 0.11);
    addRoad("social_sciences", "bascom_hill", 0.11);
    addRoad("morgridge_hall", "education_building", 0.05);
    addRoad("education_building", "morgridge_hall", 0.05);
    addRoad("state_frances", "memorial_library", 0.12, "Route A");
    addRoad("memorial_library", "state_frances", 0.12, "Route A");
    addRoad("memorial_library", "humanities_building", 0.13);
    addRoad("humanities_building", "memorial_library", 0.13);
    addRoad("memorial_library", "science_hall", 0.16);
    addRoad("science_hall", "memorial_library", 0.16);
    addRoad("college_library", "van_hise_hall", 0.29);
    addRoad("van_hise_hall", "college_library", 0.29);
    addRoad("college_library", "van_vleck_hall", 0.22);
    addRoad("van_vleck_hall", "college_library", 0.22);
    addRoad("engineering_hall", "discovery_building", 0.19);
    addRoad("discovery_building", "engineering_hall", 0.19);
    addRoad("union_south", "nicholas_rec", 0.5);
    addRoad("nicholas_rec", "union_south", 0.5);
    addRoad("camp_randall", "atmosphere_apts", 0.79);
    addRoad("atmosphere_apts", "camp_randall", 0.79);
    addRoad("kohl_center", "x01_apts", 0.38);
    addRoad("x01_apts", "kohl_center", 0.38);
    addRoad("grainger_hall", "lucky_apts", 0.18);
    addRoad("lucky_apts", "grainger_hall", 0.18);
    addRoad("grainger_hall", "business_school", 0.05);
    addRoad("business_school", "grainger_hall", 0.05);
    addRoad("library_mall", "red_gym", 0.08);
    addRoad("red_gym", "library_mall", 0.08);
    addRoad("grainger_hall", "vilas_hall", 0.09);
    addRoad("vilas_hall", "grainger_hall", 0.09);
    addRoad("state_frances", "target_state_st", 0.05, "Route A");
    addRoad("target_state_st", "state_frances", 0.05, "Route A");
    addRoad("state_frances", "chipotle_state_st", 0.05, "Route A");
    addRoad("chipotle_state_st", "state_frances", 0.05, "Route A");
    addRoad("state_frances", "popeyes_state_st", 0.05, "Route A");
    addRoad("popeyes_state_st", "state_frances", 0.05, "Route A");
    addRoad("capitol", "ians_pizza", 0.15, "Route A");
    addRoad("ians_pizza", "capitol", 0.15, "Route A");
    addRoad("van_hise_hall", "middleton_building", 0.09);
    addRoad("middleton_building", "van_hise_hall", 0.09);
    addRoad("camp_randall", "vilas_zoo", 0.72);
    addRoad("vilas_zoo", "camp_randall", 0.72);
    addRoad("bascom_hill", "elizabeth_waters", 0.18);
    addRoad("elizabeth_waters", "bascom_hill", 0.18);
    addRoad("library_mall", "witte_hall", 0.29, "Route B");
    addRoad("witte_hall", "library_mall", 0.29, "Route B");
    addRoad("memorial_union", "elizabeth_waters", 0.31, "Route 80");
    addRoad("elizabeth_waters", "memorial_union", 0.31, "Route 80");
    addRoad("elizabeth_waters", "slichter_hall", 0.29, "Route 80");
    addRoad("slichter_hall", "elizabeth_waters", 0.29, "Route 80");
    addRoad("slichter_hall", "kronshage_halls", 0.19, "Route 80");
    addRoad("kronshage_halls", "slichter_hall", 0.19, "Route 80");
    addRoad("kronshage_halls", "bradley_hall", 0.05);
    addRoad("bradley_hall", "kronshage_halls", 0.05);
    addRoad("kronshage_halls", "dejope_hall", 0.1);
    addRoad("dejope_hall", "kronshage_halls", 0.1);
    addRoad("dejope_hall", "bakke_rec_center", 0.12);
    addRoad("bakke_rec_center", "dejope_hall", 0.12);
    addRoad("bakke_rec_center", "lakeshore_preserve", 0.69, "Route 80");
    addRoad("lakeshore_preserve", "bakke_rec_center", 0.69, "Route 80");
    addRoad("lakeshore_preserve", "eagle_heights", 0.47, "Route 80");
    addRoad("eagle_heights", "lakeshore_preserve", 0.47, "Route 80");
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
}
