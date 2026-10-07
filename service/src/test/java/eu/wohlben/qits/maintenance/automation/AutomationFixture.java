package eu.wohlben.qits.maintenance.automation;

import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import java.util.Map;

/**
 * The peers a release-request automation talks to, scripted on top of {@link Fixture}'s scan: the
 * fold's tree on the git host, the request in qits-projects, and the kind's own branch.
 */
final class AutomationFixture {

  static final String REQUEST = "5e1f0c3a-2b4d-4e6f-8a9b-0c1d2e3f4a5b";

  static final String FOLD_A = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1";

  static final String FOLD_B = "b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2";

  static final String FOLD_C = "c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3";

  static final String FOLD_D = "d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4";

  static final String BEFORE = "1111111111111111111111111111111111111111";

  static final String PUSHED = "2222222222222222222222222222222222222222";

  private static final String TREE = "/git/" + Fixture.PROJECT + "/" + Fixture.REPOSITORY + "/tree/";

  private static final String BLOB = "/git/" + Fixture.PROJECT + "/" + Fixture.REPOSITORY + "/blob/";

  private static final String WITH_SCREENSHOTS =
      "{\"name\":\"app\",\"scripts\":{\"test:browser\":\"vitest --browser\"}}";

  private static final String WITHOUT_SCREENSHOTS = "{\"name\":\"app\",\"scripts\":{\"test\":\"vitest\"}}";

  /** A single-module pom declaring the ORM dependency — enough for {@code entity-diagram} to apply. */
  private static final String POM_WITH_ORM =
      "<project>\n"
          + "  <dependencies>\n"
          + "    <dependency>\n"
          + "      <groupId>io.quarkus</groupId>\n"
          + "      <artifactId>quarkus-hibernate-orm</artifactId>\n"
          + "    </dependency>\n"
          + "  </dependencies>\n"
          + "</project>\n";

  private AutomationFixture() {}

  /** The screenshot-baselines branch of a request. */
  static String branch(String requestId) {
    return branch(ScreenshotBaselinesAutomation.KIND, requestId);
  }

  /** One kind's own branch of a request. */
  static String branch(String kind, String requestId) {
    return AutomationService.BRANCH_PREFIX + kind + "/" + requestId;
  }

  /** The fold's {@code pom.xml}, declaring the ORM dependency — so {@code entity-diagram} applies. */
  static void scriptEntityDiagramApplies(FakePeers peers, String fold) {
    Map<String, String> sha = Map.of("Git-Commit-Sha", fold);
    peers.answer(PeerTarget.GITHOST, BLOB + fold + "/pom.xml", FakePeers.Scripted.ok(POM_WITH_ORM, sha));
  }

  /**
   * One fold of the repository, readable: its root (so a file it lacks is ABSENT rather than GONE),
   * its {@code package.json}, and — when it follows the convention — the renderer record.
   */
  static void scriptFold(FakePeers peers, String fold, boolean screenshots) {
    Map<String, String> sha = Map.of("Git-Commit-Sha", fold);
    peers.answer(PeerTarget.GITHOST, TREE + fold, FakePeers.Scripted.ok("{\"entries\":[]}", sha));
    peers.answer(
        PeerTarget.GITHOST,
        BLOB + fold + "/package.json",
        FakePeers.Scripted.ok(screenshots ? WITH_SCREENSHOTS : WITHOUT_SCREENSHOTS, sha));
    if (screenshots) {
      peers.answer(
          PeerTarget.GITHOST,
          BLOB + fold + "/src/testing/browser/renderer.txt",
          FakePeers.Scripted.ok("chromium 140 / linux\n", sha));
    }
  }

  /** A fold the git host cannot be asked about. */
  static void scriptFoldUnreachable(FakePeers peers, String fold) {
    peers.answer(
        PeerTarget.GITHOST,
        BLOB + fold + "/package.json",
        FakePeers.Scripted.unreachable("connection refused"));
  }

  /** The request as qits-projects answers it: a state, and the fold it stands at. */
  static void scriptRequest(FakePeers peers, String requestId, String state, String mergedSha) {
    peers.answer(
        PeerTarget.PROJECTS,
        Fixture.RELEASE_REQUESTS_PATH + "/" + requestId,
        FakePeers.Scripted.ok(
            "{\"request\":{\"id\":\"" + requestId + "\",\"repoId\":\"" + Fixture.CATALOG_ID
                + "\",\"state\":\"" + state + "\",\"mergedSha\":"
                + (mergedSha == null ? "null" : "\"" + mergedSha + "\"")
                + ",\"sources\":[{\"kind\":\"BRANCH\",\"name\":\"main\"},"
                + "{\"kind\":\"BRANCH\",\"name\":\"work\"}]}}"));
  }

  /** What qits-projects answers to joining a branch to the request. */
  static void scriptJoin(FakePeers peers, String requestId, FakePeers.Scripted answer) {
    peers.answer(PeerTarget.PROJECTS, joinPath(requestId), answer);
  }

  static String joinPath(String requestId) {
    return Fixture.RELEASE_REQUESTS_PATH + "/" + requestId + "/sources";
  }

  static FakePeers.Scripted joined(String requestId) {
    return FakePeers.Scripted.ok(
        "{\"request\":{\"id\":\"" + requestId + "\",\"state\":\"PENDING\"}}");
  }
}
