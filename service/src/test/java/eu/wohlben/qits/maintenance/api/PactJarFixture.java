package eu.wohlben.qits.maintenance.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A consumer pact jar as the platform publishes one (qits-2702): {@code META-INF/MANIFEST.MF} and
 * {@code pacts/<file>.json} entries, built in memory for a fake maven registry to serve.
 */
public final class PactJarFixture {

  private PactJarFixture() {}

  /** The maven repository path of {@code eu.wohlben.qits:<artifactId>:<version>}'s jar. */
  public static String path(String artifactId, String version) {
    return "/eu/wohlben/qits/" + artifactId + "/" + version + "/" + artifactId + "-" + version + ".jar";
  }

  /** A jar holding exactly these entries, by name under the jar root. */
  public static byte[] jar(Map<String, String> entries) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
      zip.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      for (Map.Entry<String, String> entry : new java.util.TreeMap<>(entries).entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return bytes.toByteArray();
  }

  /**
   * A V4 pact of {@code consumer} on {@code provider} with one GET interaction per path, named by
   * the operation and verified in the state, as the golden-master helper writes them.
   *
   * @param calls path to {operationId, state name, param name, param value}
   */
  public static String pact(String consumer, String provider, Map<String, String[]> calls) {
    StringBuilder interactions = new StringBuilder();
    for (Map.Entry<String, String[]> call : new java.util.TreeMap<>(calls).entrySet()) {
      String[] c = call.getValue();
      if (interactions.length() > 0) {
        interactions.append(',');
      }
      interactions
          .append("{\"type\":\"Synchronous/HTTP\",\"description\":\"")
          .append(c[0])
          .append("\",\"comments\":{\"references\":{\"qits-call\":{\"app\":\"")
          .append(provider)
          .append("\",\"operationId\":\"")
          .append(c[0])
          .append("\"}}},\"providerStates\":[{\"name\":\"")
          .append(c[1])
          .append("\",\"params\":{\"")
          .append(c[2])
          .append("\":\"")
          .append(c[3])
          .append("\"}}],\"request\":{\"method\":\"GET\",\"path\":\"")
          .append(call.getKey())
          .append("\"},\"response\":{\"status\":200}}");
    }
    return "{\"consumer\":{\"name\":\""
        + consumer
        + "\"},\"provider\":{\"name\":\""
        + provider
        + "\"},\"interactions\":["
        + interactions
        + "],\"metadata\":{\"pactSpecification\":{\"version\":\"4.0\"}}}";
  }
}
