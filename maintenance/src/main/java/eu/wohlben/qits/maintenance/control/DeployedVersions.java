package eu.wohlben.qits.maintenance.control;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.maintenance.error.DeploymentsUnansweredException;
import eu.wohlben.qits.maintenance.peer.PeerAnswer;
import eu.wohlben.qits.maintenance.peer.PeerClient;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import java.util.TreeSet;

/**
 * <b>The versions qits-deployments serves, and the ones a rollback would restore</b> — {@code GET
 * /deployments/api/pins}, {@code {"pins":[{"applicationName","shas":[…]}]}}.
 *
 * <p>Each entry of {@code shas} is a deployment's image tag, which is the released version (the
 * calver the tag {@code refs/tags/<version>} is named after), or a commit sha on rows older than the
 * deployer's V7. {@code GET /pins} keeps what the ledger says those releases declared: the deployed
 * qits-ci downloads the CLI version its own release pins, whatever main pins today (qits-1172).
 *
 * <p>Fail-closed: anything but a readable 200 throws, and {@code GET /pins} answers 503.
 */
@ApplicationScoped
public class DeployedVersions {

  /** The deployer's pin route, below its host. */
  public static final String PATH = "/deployments/api/pins";

  @Inject PeerClient peers;

  /** Every version that serves or would be rolled back to, across every application. */
  public Set<String> versions() {
    PeerAnswer answer = peers.get(PeerTarget.DEPLOYMENTS, PATH).answer();
    if (!answer.ok()) {
      throw new DeploymentsUnansweredException(
          answer.error() != null ? answer.error() : "qits-deployments answered " + answer.httpStatus());
    }
    return parse(answer.json());
  }

  /** The parsing half, with no transport in it. */
  static Set<String> parse(JsonNode body) {
    JsonNode pins = body == null ? null : body.get("pins");
    if (pins == null || !pins.isArray()) {
      throw new DeploymentsUnansweredException("the answer carries no 'pins' array");
    }
    Set<String> versions = new TreeSet<>();
    for (JsonNode pin : pins) {
      JsonNode shas = pin.get("shas");
      if (shas == null || !shas.isArray()) {
        throw new DeploymentsUnansweredException("a pin carries no 'shas' array");
      }
      for (JsonNode sha : shas) {
        String version = sha.asText("").trim();
        if (!version.isEmpty()) {
          versions.add(version);
        }
      }
    }
    return versions;
  }
}
