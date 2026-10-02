package eu.wohlben.qits.maintenance.sbomcheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.maintenance.error.SbomCheckFailedException;
import eu.wohlben.qits.maintenance.peer.PeerAnswer;
import eu.wohlben.qits.maintenance.peer.PeerCall;
import eu.wohlben.qits.maintenance.peer.PeerClient;
import eu.wohlben.qits.maintenance.peer.PeerExchange;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The presence probe's four routes — each a LISTING the store derives without recording an access,
 * so the daily probe never keeps alive the versions it reports — and its one rule: 404 is
 * "collected", anything else that is not a listing fails the run.
 */
class ArtifactPresenceTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** qits-artifacts at no port, scripted by "TARGET path"; an unscripted path is a 404. */
  private static final class Stub extends PeerClient {
    final Map<String, PeerAnswer> script = new HashMap<>();
    final List<String> asked = new ArrayList<>();

    @Override
    public String url(PeerTarget target, String path) {
      return "http://qits-artifacts:8080" + path;
    }

    @Override
    public PeerExchange get(PeerTarget target, String path) {
      asked.add(target + " " + path);
      return new PeerExchange(
          new PeerCall("GET", url(target, path), null),
          script.getOrDefault(target + " " + path, new PeerAnswer(404, "", null, Map.of(), null)));
    }

    void answer(PeerTarget target, String path, int status, String body) {
      com.fasterxml.jackson.databind.JsonNode parsed;
      try {
        parsed = JSON.readTree(body);
      } catch (Exception notJson) {
        parsed = null;
      }
      script.put(target + " " + path, new PeerAnswer(status, body, parsed, Map.of(), null));
    }
  }

  private Stub peers;
  private ArtifactPresence presence;

  @BeforeEach
  void setUp() {
    peers = new Stub();
    presence = new ArtifactPresence();
    presence.peers = peers;
  }

  @Test
  void mavenReadsTheDerivedMetadataNotThePom() {
    peers.answer(
        PeerTarget.MAVEN_REGISTRY,
        "/eu/wohlben/qits/qits-ci/maven-metadata.xml",
        200,
        "<metadata><versioning><versions><version>1</version><version>2</version></versions>"
            + "</versioning></metadata>");

    assertEquals(Set.of("1", "2"), presence.versions("maven", "eu.wohlben.qits:qits-ci"));
  }

  @Test
  void npmReadsThePackumentsVersionsWithTheScopeSlashEncoded() {
    peers.answer(
        PeerTarget.NPM_REGISTRY,
        "/@qits%2fui-components",
        200,
        "{\"versions\":{\"1.0.0\":{},\"1.1.0\":{}}}");

    assertEquals(Set.of("1.0.0", "1.1.0"), presence.versions("npm", "@qits/ui-components"));
  }

  @Test
  void dockerReadsTheTagListingNotAManifest() {
    peers.answer(
        PeerTarget.OCI_REGISTRY,
        "/qits/qits-ci/tags/list?n=1000",
        200,
        "{\"name\":\"qits/qits-ci\",\"tags\":[\"2026.1001.1\",\"latest\"]}");

    assertEquals(Set.of("2026.1001.1", "latest"), presence.versions("docker", "qits/qits-ci"));
    assertTrue(peers.asked.stream().noneMatch(path -> path.contains("/manifests/")));
  }

  @Test
  void aDaemonReadsTheBrowseListingNotTheBinary() {
    peers.answer(
        PeerTarget.ARTIFACTS_SBOM,
        "/artifacts/api/repositories/daemons/daemons/qits-platform-access-cli/versions",
        200,
        "{\"versions\":[{\"version\":\"2026.1001.1\",\"digest\":\"abc\"}]}");

    assertEquals(Set.of("2026.1001.1"), presence.versions("daemon", "qits-platform-access-cli"));
  }

  @Test
  void aFourOhFourIsNothingLeftAndAnythingElseFailsTheRun() {
    assertEquals(Set.of(), presence.versions("maven", "eu.wohlben.qits:collected"));

    peers.answer(PeerTarget.NPM_REGISTRY, "/broken", 500, "");
    assertThrows(SbomCheckFailedException.class, () -> presence.versions("npm", "broken"));

    peers.answer(PeerTarget.MAVEN_REGISTRY, "/g/a/maven-metadata.xml", 200, "<html>proxy</html>");
    assertThrows(SbomCheckFailedException.class, () -> presence.versions("maven", "g:a"));
  }
}
