import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for RoadNetworkLoader's CSV parsing -- specifically the
 * quote-aware splitting locations.csv needs now that its `query` column
 * (added for reproducibility, see scripts/geocode.py) legitimately
 * contains commas, e.g. "Target, State Street, Madison, WI".
 */
public class RoadNetworkLoaderTest {

  private Path writeTempCsv(String filename, String content) throws IOException {
    Path dir = Files.createTempDirectory("road-network-loader-test");
    Path file = dir.resolve(filename);
    Files.writeString(file, content, StandardCharsets.UTF_8);
    return dir;
  }

  @Test
  public void parsesLocationsWithoutExtraColumns() throws IOException {
    Path dir = writeTempCsv("locations.csv", "id,name,lat,lon\ncapitol,Capitol Square,43.0747,-89.3842\n");
    Files.writeString(dir.resolve("roads.csv"), "from,to,miles,busRoute\n");

    RoadNetworkLoader.NetworkData data = RoadNetworkLoader.load(dir);
    assertEquals(1, data.intersections().size());
    RoadNetwork.Intersection i = data.intersections().get(0);
    assertEquals("capitol", i.id());
    assertEquals("Capitol Square", i.name());
    assertEquals(43.0747, i.lat());
    assertEquals(-89.3842, i.lon());
  }

  @Test
  public void parsesLocationsWithQuotedCommaContainingQueryColumn() throws IOException {
    // The exact shape scripts/geocode.py's CSV writer produces: a `query`
    // value containing commas gets wrapped in double quotes.
    String content = "id,name,lat,lon,query,source,retrieved_at\n"
        + "target_state_st,Target (State St),43.0752,-89.396,"
        + "\"Target, State Street, Madison, WI\",Nominatim (OpenStreetMap),2026-09-17T12:00:00Z\n";
    Path dir = writeTempCsv("locations.csv", content);
    Files.writeString(dir.resolve("roads.csv"), "from,to,miles,busRoute\n");

    RoadNetworkLoader.NetworkData data = RoadNetworkLoader.load(dir);
    assertEquals(1, data.intersections().size());
    RoadNetwork.Intersection i = data.intersections().get(0);
    assertEquals("target_state_st", i.id());
    assertEquals("Target (State St)", i.name());
    assertEquals(43.0752, i.lat());
    assertEquals(-89.396, i.lon());
  }

  @Test
  public void roadsWithBlankBusRouteParseAsNull() throws IOException {
    Path dir = writeTempCsv("locations.csv", "id,name,lat,lon\na,A,43.0,-89.0\nb,B,43.1,-89.1\n");
    Files.writeString(dir.resolve("roads.csv"), "from,to,miles,busRoute\na,b,0.5,\nb,a,0.5,Route A\n");

    RoadNetworkLoader.NetworkData data = RoadNetworkLoader.load(dir);
    List<RoadNetwork.Road> roads = data.roads();
    assertEquals(2, roads.size());
    assertNull(roads.get(0).busRoute());
    assertEquals("Route A", roads.get(1).busRoute());
  }
}
