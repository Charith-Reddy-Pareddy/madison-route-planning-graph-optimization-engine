import java.io.IOException;
import java.nio.file.Path;

/** Entry point: starts the Madison Route Planning HTTP server on $PORT (default 8080). */
public class Main {
  public static void main(String[] args) throws IOException {
    int port = 8080;
    String portEnv = System.getenv("PORT");
    if (portEnv != null && !portEnv.isBlank()) {
      port = Integer.parseInt(portEnv);
    }

    RoadNetwork network = new RoadNetwork();
    PathFinderServer server = new PathFinderServer(network, Path.of("web"));
    server.start(port);
    System.out.println("Madison Route Planning running at http://localhost:" + port);
  }
}
