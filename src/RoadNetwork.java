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

  /** A directed road segment between two intersections, weighted by miles. */
  public record Road(String from, String to, double miles) {}

  private final DijkstraGraph<String, Double> graph = new DijkstraGraph<>();
  private final Map<String, Intersection> intersections = new LinkedHashMap<>();
  private final List<Road> roads = new ArrayList<>();

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

    addRoad("capitol", "king_st", 0.30);
    addRoad("king_st", "capitol", 0.30);
    addRoad("capitol", "state_frances", 0.35);
    addRoad("state_frances", "capitol", 0.35);
    addRoad("capitol", "john_nolen", 0.60);
    addRoad("king_st", "willy_st", 0.50);
    addRoad("willy_st", "king_st", 0.50);
    // One-way: no direct return leg from Williamson St to the square.
    addRoad("willy_st", "capitol", 0.55);
    addRoad("state_frances", "state_gilman", 0.35);
    addRoad("state_gilman", "state_frances", 0.35);
    addRoad("state_gilman", "library_mall", 0.30);
    addRoad("library_mall", "state_gilman", 0.30);
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
    addRoad("camp_randall", "monroe_edgewood", 0.70);
    addRoad("monroe_edgewood", "camp_randall", 0.70);
    addRoad("regent_park", "monroe_edgewood", 0.60);
    addRoad("monroe_edgewood", "regent_park", 0.60);
    addRoad("regent_park", "john_nolen", 0.90);
    addRoad("john_nolen", "regent_park", 0.90);
    addRoad("john_nolen", "capitol", 0.60);

    addRoad("king_st", "east_wash", 0.45);
    addRoad("east_wash", "king_st", 0.45);
    addRoad("east_wash", "tenney_park", 0.55);
    addRoad("tenney_park", "east_wash", 0.55);
    addRoad("willy_st", "tenney_park", 0.50);
    addRoad("tenney_park", "willy_st", 0.50);
    // One-way: this stretch of East Wash only runs outbound toward Atwood.
    // The way back to the rest of the network is via Olbrich -> Tenney Park
    // instead, not a straight reversal -- same pattern as the other one-ways.
    addRoad("east_wash", "atwood_schenks", 0.65);
    addRoad("atwood_schenks", "olbrich_gardens", 0.50);
    addRoad("olbrich_gardens", "atwood_schenks", 0.50);
    addRoad("olbrich_gardens", "tenney_park", 0.75);
    addRoad("monroe_edgewood", "arboretum", 0.90);
    addRoad("arboretum", "monroe_edgewood", 0.90);
    addRoad("bascom_hill", "hilldale", 0.85);
    addRoad("hilldale", "bascom_hill", 0.85);
  }

  private void addIntersection(String id, String name, double lat, double lon) {
    intersections.put(id, new Intersection(id, name, lat, lon));
    graph.insertNode(id);
  }

  private void addRoad(String from, String to, double miles) {
    roads.add(new Road(from, to, miles));
    graph.insertEdge(from, to, miles);
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
