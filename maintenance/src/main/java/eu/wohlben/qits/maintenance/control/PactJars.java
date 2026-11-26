package eu.wohlben.qits.maintenance.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.maintenance.dto.DeployedPactInteractionsDto.InteractionDto;
import eu.wohlben.qits.maintenance.dto.DeployedPactInteractionsDto.ProviderStateDto;
import eu.wohlben.qits.maintenance.peer.PeerBytes;
import eu.wohlben.qits.maintenance.peer.PeerClient;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * <b>The interactions one consumer pact jar holds against one provider</b> (qits-2702), read off
 * qits-artifacts' hosted maven repository — the same jar {@code qits artifacts fetch
 * deployed-pacts} downloads, and the same {@code pacts/*_<provider>.json} entries it keeps.
 *
 * <p><b>Nothing throws</b>, the rule every peer client here follows: a jar that cannot be read comes
 * back as a {@link Result} saying why. Whether that fails the caller is the caller's decision —
 * {@code DeployedPactInteractions} fails closed.
 *
 * <p><b>What an interaction is reduced to</b>: its method, its concrete path, the {@code
 * operationId} the consumer named ({@code comments.references.qits-call.operationId}, absent on a
 * pact not written through the golden-master helper) and its provider states with their params. An
 * interaction with no HTTP request (a message pact) is skipped; identical ones are kept once.
 */
@ApplicationScoped
public class PactJars {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** Where a pact file sits inside a pact jar. */
  static final String DIRECTORY = "pacts/";

  @Inject PeerClient peers;

  /** How one read of a pact jar came out. */
  public enum Outcome {
    /** The jar was read and holds at least one pact file against the provider. */
    READ,

    /** The registry holds no such jar (404). */
    MISSING,

    /** The registry could not be asked, or answered something that is not a pact jar. */
    FAILED
  }

  /**
   * One answer.
   *
   * @param outcome which of the three
   * @param interactions the jar's interactions against the provider, only on {@link Outcome#READ}
   * @param url what was read
   * @param reason one line, on anything but {@link Outcome#READ}
   */
  public record Result(
      Outcome outcome, List<InteractionDto> interactions, String url, String reason) {

    public boolean read() {
      return outcome == Outcome.READ;
    }
  }

  /**
   * The interactions of the jar {@code groupId:artifactId:version} against {@code provider}.
   *
   * @param provider the provider repository; a pact file is {@code pacts/<…>_<provider>.json}
   */
  public Result interactions(String groupId, String artifactId, String version, String provider) {
    String path =
        "/"
            + groupId.replace('.', '/')
            + "/"
            + artifactId
            + "/"
            + version
            + "/"
            + artifactId
            + "-"
            + version
            + ".jar";
    PeerBytes answer = peers.getBytes(PeerTarget.MAVEN_REGISTRY, path);
    if (answer.notFound()) {
      return new Result(Outcome.MISSING, List.of(), answer.url(), "no such jar in the registry");
    }
    if (!answer.ok()) {
      return new Result(Outcome.FAILED, List.of(), answer.url(), answer.failure());
    }
    try {
      List<InteractionDto> interactions = read(answer.body(), provider);
      if (interactions == null) {
        return new Result(
            Outcome.FAILED,
            List.of(),
            answer.url(),
            "the jar holds no " + DIRECTORY + "*_" + provider + ".json");
      }
      return new Result(Outcome.READ, interactions, answer.url(), null);
    } catch (IOException | RuntimeException e) {
      return new Result(
          Outcome.FAILED, List.of(), answer.url(), "not a readable pact jar: " + e.getMessage());
    }
  }

  /**
   * Every interaction in the jar's top-level {@code pacts/*_<provider>.json} files, files in name
   * order; null when the jar holds no such file.
   */
  static List<InteractionDto> read(byte[] jar, String provider) throws IOException {
    if (jar == null || jar.length == 0) {
      throw new IOException("the body is empty");
    }
    String suffix = "_" + provider + ".json";
    Map<String, JsonNode> files = new TreeMap<>();
    boolean anEntry = false;
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        anEntry = true;
        String name = entry.getName();
        if (entry.isDirectory()
            || !name.startsWith(DIRECTORY)
            || name.indexOf('/', DIRECTORY.length()) >= 0
            || !name.endsWith(suffix)) {
          continue;
        }
        files.put(name, JSON.readTree(zip.readAllBytes()));
      }
    }
    if (!anEntry) {
      throw new IOException("the body is not a zip");
    }
    if (files.isEmpty()) {
      return null;
    }
    Set<InteractionDto> interactions = new LinkedHashSet<>();
    for (JsonNode pact : files.values()) {
      for (JsonNode interaction : pact.path("interactions")) {
        JsonNode request = interaction.path("request");
        String method = text(request.path("method"));
        String path = text(request.path("path"));
        if (method == null || path == null) {
          continue;
        }
        String operationId =
            text(
                interaction
                    .path("comments")
                    .path("references")
                    .path("qits-call")
                    .path("operationId"));
        List<ProviderStateDto> states = new ArrayList<>();
        for (JsonNode state : interaction.path("providerStates")) {
          Map<String, String> params = new TreeMap<>();
          Iterator<Map.Entry<String, JsonNode>> fields = state.path("params").fields();
          while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            params.put(field.getKey(), value.isValueNode() ? value.asText() : value.toString());
          }
          states.add(
              new ProviderStateDto(
                  text(state.path("name")), java.util.Collections.unmodifiableMap(params)));
        }
        interactions.add(
            new InteractionDto(
                method.toUpperCase(java.util.Locale.ROOT), path, operationId, List.copyOf(states)));
      }
    }
    return List.copyOf(interactions);
  }

  private static String text(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    String value = node.asText();
    return value.isBlank() ? null : value;
  }
}
