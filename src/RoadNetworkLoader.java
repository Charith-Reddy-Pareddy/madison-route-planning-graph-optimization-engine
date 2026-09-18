import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads RoadNetwork's intersections and roads from data/locations.csv and
 * data/roads.csv, replacing what used to be ~180 lines of hardcoded
 * addIntersection/addRoad calls in RoadNetwork itself. See
 * data/sources.md for where each coordinate and distance comes from.
 */
public class RoadNetworkLoader {

  public record NetworkData(List<RoadNetwork.Intersection> intersections, List<RoadNetwork.Road> roads) {}

  private static final Path DEFAULT_DATA_DIR = Path.of("data");

  public static NetworkData load() {
    return load(DEFAULT_DATA_DIR);
  }

  public static NetworkData load(Path dataDir) {
    try {
      return new NetworkData(
          loadIntersections(dataDir.resolve("locations.csv")),
          loadRoads(dataDir.resolve("roads.csv")));
    } catch (IOException e) {
      throw new UncheckedIOException("failed to load road network data from " + dataDir, e);
    }
  }

  private static List<RoadNetwork.Intersection> loadIntersections(Path csv) throws IOException {
    List<RoadNetwork.Intersection> result = new ArrayList<>();
    for (String line : dataLines(csv)) {
      String[] cols = splitCsvLine(line);
      result.add(new RoadNetwork.Intersection(
          cols[0], cols[1], Double.parseDouble(cols[2]), Double.parseDouble(cols[3])));
    }
    return result;
  }

  private static List<RoadNetwork.Road> loadRoads(Path csv) throws IOException {
    List<RoadNetwork.Road> result = new ArrayList<>();
    for (String line : dataLines(csv)) {
      String[] cols = splitCsvLine(line);
      String busRoute = cols.length > 3 && !cols[3].isBlank() ? cols[3] : null;
      result.add(new RoadNetwork.Road(cols[0], cols[1], Double.parseDouble(cols[2]), busRoute));
    }
    return result;
  }

  // Skips the header row and blank lines.
  private static List<String> dataLines(Path csv) throws IOException {
    List<String> lines = Files.readAllLines(csv);
    List<String> result = new ArrayList<>();
    for (int i = 1; i < lines.size(); i++) {
      String line = lines.get(i);
      if (!line.isBlank()) {
        result.add(line);
      }
    }
    return result;
  }

  // A plain split(",") isn't enough for locations.csv's `query` column
  // (e.g. "Target, State Street, Madison, WI"), which legitimately
  // contains commas and so gets double-quoted by the CSV writer that
  // produces this file (scripts/geocode.py, via Python's csv module).
  // Minimal RFC4180-style handling: a field wrapped in double quotes can
  // contain commas (not treated as separators) and an escaped "" for a
  // literal quote character.
  private static String[] splitCsvLine(String line) {
    List<String> fields = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean inQuotes = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (inQuotes) {
        if (c == '"') {
          if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
            field.append('"');
            i++;
          } else {
            inQuotes = false;
          }
        } else {
          field.append(c);
        }
      } else if (c == '"') {
        inQuotes = true;
      } else if (c == ',') {
        fields.add(field.toString());
        field.setLength(0);
      } else {
        field.append(c);
      }
    }
    fields.add(field.toString());
    return fields.toArray(new String[0]);
  }
}
