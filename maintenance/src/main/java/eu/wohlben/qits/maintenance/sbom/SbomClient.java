package eu.wohlben.qits.maintenance.sbom;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.peer.PeerAnswer;
import eu.wohlben.qits.maintenance.peer.PeerClient;
import eu.wohlben.qits.maintenance.peer.PeerExchange;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Locale;
import java.util.Set;

/**
 * "Does qits-artifacts hold a bill of materials for this release, and what does it say."
 *
 * <p><b>Through {@link PeerClient} rather than a second HttpClient of its own.</b> That is the one
 * seam this repository talks through: it already owns the shared client instance (an INSTANCE field
 * — a static one is a native-image hazard), the shipped {@code call-timeout}, the forward-auth pair
 * every outbound call carries, the optional bearer, and the response bound. A second client here
 * would be a second place for each of those, and it would put this call outside {@code FakePeers} —
 * the alternative the whole suite replaces the network with.
 *
 * <p><b>The read needs no token today</b>, exactly like the registry reads beside it: qits-artifacts'
 * routes are unguarded on qits-net. It goes out under the {@code artifacts} credential anyway, which
 * ships disabled, so the day the edge's rule reaches the inside is three environment variables and
 * not a release.
 *
 * <p><b>404 is the ORDINARY answer and it is not an error.</b> The SBOM route is newer than most of
 * the platform's releases, so most coordinates have no document and never will. It comes back as
 * {@link Outcome#MISSING}, which nothing retries.
 *
 * <p><b>Nothing here throws.</b> An unreachable qits-artifacts costs one artifact row its status,
 * the same rule every other outbound call in this service follows.
 */
@ApplicationScoped
public class SbomClient {

  /** The route's own prefix. It is code rather than configuration — see {@code ARTIFACTS_SBOM}. */
  static final String PREFIX = "/artifacts/sboms/";

  @Inject PeerClient peers;

  /** What one read of the SBOM store came to. */
  public enum Outcome {
    /** A document was returned. */
    FOUND,

    /** qits-artifacts has none for this coordinate. Terminal, and not a failure. */
    MISSING,

    /** It answered something else, or could not be reached. */
    FAILED
  }

  /**
   * One answer.
   *
   * @param outcome which of the three
   * @param document the parsed document, only on {@link Outcome#FOUND}
   * @param url what was read, so a surprising answer can be reproduced by hand
   * @param reason one line, on {@link Outcome#FAILED}
   */
  public record SbomAnswer(Outcome outcome, JsonNode document, String url, String reason) {}

  /**
   * The released-artifact types this route is keyed by, in qits-ci's {@code packageType} spelling —
   * the same word {@code mt_artifact.ecosystem} stores. Three of them are {@link Ecosystem}s; {@code
   * daemon} is not and never becomes one ({@link Ecosystem#DAEMON_WIRE_NAME} says why), and it is a
   * type here all the same, because qits-artifacts stores a bill of materials for every released
   * daemon binary under exactly that segment.
   */
  public static final Set<String> TYPES =
      Set.of(
          Ecosystem.MAVEN.wireName(),
          Ecosystem.NPM.wireName(),
          Ecosystem.DOCKER.wireName(),
          Ecosystem.DAEMON_WIRE_NAME);

  /** Whether a stored {@code mt_artifact.ecosystem} word is one this route can address. */
  public static boolean addressable(String type) {
    return type != null && TYPES.contains(type.trim().toLowerCase(Locale.ROOT));
  }

  /**
   * The document for one released coordinate.
   *
   * @param type the artifact's wire type — {@code maven}, {@code npm}, {@code docker} or {@code
   *     daemon}, exactly as {@code mt_artifact.ecosystem} holds it
   */
  public SbomAnswer fetch(String type, String name, String version) {
    if (!addressable(type)) {
      return new SbomAnswer(
          Outcome.FAILED, null, null, "'" + type + "' is not an artifact type the sbom route keys");
    }
    if (name == null || name.isBlank() || version == null || version.isBlank()) {
      return new SbomAnswer(
          Outcome.FAILED, null, null, "an artifact needs a name and a version to be asked about");
    }
    String path = path(type, name, version);
    PeerExchange exchange = peers.get(PeerTarget.ARTIFACTS_SBOM, path);
    String url = exchange.call().url();
    PeerAnswer answer = exchange.answer();
    if (answer.notFound()) {
      return new SbomAnswer(Outcome.MISSING, null, url, null);
    }
    if (!answer.ok()) {
      return new SbomAnswer(Outcome.FAILED, null, url, answer.failure());
    }
    JsonNode document = answer.json();
    if (document == null || !document.isObject()) {
      return new SbomAnswer(Outcome.FAILED, null, url, "the sbom answer did not parse as json");
    }
    return new SbomAnswer(Outcome.FOUND, document, url, null);
  }

  /**
   * {@code /artifacts/sboms/<type>/<name>/-/<version>}, where {@code <type>} is qits-ci's {@code
   * packageType} — {@code maven}, {@code npm}, {@code docker} or {@code daemon} — which is also the
   * word {@code mt_artifact.ecosystem} stores, so a row is addressed by its own column.
   *
   * <p><b>The name goes in LITERALLY, slashes and all.</b> {@code qits/build-images/maven-base} is
   * three path segments on this route and the {@code /-/} separator is what tells the name from the
   * version — which is why it exists. Encoding the slashes, as the npm packument read has to, would
   * address a package with a per-cent sign in its name.
   *
   * <p><b>A daemon is addressed like any other type, and that is a correction.</b> This signature
   * used to take an {@link Ecosystem}, which made a daemon's document unreachable — {@code daemon}
   * is deliberately not one — so the daemon rows written to carry a GC keep (see {@code
   * control/CarriedDaemons}) were written terminal FAILED while qits-artifacts held a document for
   * every one of them. The route is keyed by the RELEASED ARTIFACT's type, not by an ecosystem this
   * service inventories pins in, and so is this method now.
   */
  static String path(String type, String name, String version) {
    return PREFIX
        + type.trim().toLowerCase(Locale.ROOT)
        + "/"
        + name.trim()
        + "/-/"
        + version.trim();
  }
}
