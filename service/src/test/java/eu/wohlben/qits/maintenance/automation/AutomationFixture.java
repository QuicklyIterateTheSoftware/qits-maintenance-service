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

  /**
   * A fold's root pom pinning one INTERNAL and one EXTERNAL dependency behind the latest the
   * fixture's scan records — {@code qits-eventstream} 2026.811.1 (latest 2026.821.3) and {@code
   * quarkus-bom} 3.34.5 (latest 3.34.6).
   */
  static final String STALE_POM =
      "<project>\n"
          + "  <groupId>eu.wohlben.qits</groupId>\n"
          + "  <artifactId>qits-ci</artifactId>\n"
          + "  <version>2026.821.1</version>\n"
          + "  <dependencies>\n"
          + "    <dependency>\n"
          + "      <groupId>eu.wohlben.qits</groupId>\n"
          + "      <artifactId>qits-eventstream</artifactId>\n"
          + "      <version>2026.811.1</version>\n"
          + "    </dependency>\n"
          + "    <dependency>\n"
          + "      <groupId>io.quarkus.platform</groupId>\n"
          + "      <artifactId>quarkus-bom</artifactId>\n"
          + "      <version>3.34.5</version>\n"
          + "    </dependency>\n"
          + "  </dependencies>\n"
          + "</project>\n";

  /** The same pom with both pins at their latest — what the fold after the bump's commit carries. */
  static final String CURRENT_POM =
      STALE_POM.replace("2026.811.1", "2026.821.3").replace("3.34.5", "3.34.6");

  /** The INTERNAL pin {@link #STALE_POM} holds behind. */
  static final String EVENTSTREAM = "eu.wohlben.qits:qits-eventstream";

  /** The EXTERNAL one. */
  static final String QUARKUS_BOM = "io.quarkus.platform:quarkus-bom";

  private AutomationFixture() {}

  /**
   * A fold whose root lists a {@code pom.xml} with this content — what {@code dependency-bump} plans
   * from — and, when {@code maintenanceYml} is not null, the repository's own config at the fold.
   * Layered over {@link #scriptFold}, which answers the root with nothing in it.
   */
  static void scriptManifests(FakePeers peers, String fold, String pom, String maintenanceYml) {
    Map<String, String> sha = Map.of("Git-Commit-Sha", fold);
    peers.answer(
        PeerTarget.GITHOST,
        TREE + fold,
        FakePeers.Scripted.ok("{\"entries\":[{\"name\":\"pom.xml\",\"type\":\"blob\"}]}", sha));
    peers.answer(PeerTarget.GITHOST, BLOB + fold + "/pom.xml", FakePeers.Scripted.ok(pom, sha));
    if (maintenanceYml != null) {
      peers.answer(
          PeerTarget.GITHOST,
          BLOB + fold + "/.config/qits/maintenance.yml",
          FakePeers.Scripted.ok(maintenanceYml, sha));
    }
  }

  /** A fold whose root holds a {@code .gitmodules} and the {@code webui} gitlink at this sha. */
  static void scriptGitlinkFold(FakePeers peers, String fold, String gitlinkSha) {
    Map<String, String> sha = Map.of("Git-Commit-Sha", fold);
    peers.answer(
        PeerTarget.GITHOST,
        TREE + fold,
        FakePeers.Scripted.ok(
            "{\"entries\":[{\"name\":\".gitmodules\",\"type\":\"blob\"},"
                + "{\"name\":\"webui\",\"type\":\"commit\",\"mode\":\"160000\",\"sha\":\""
                + gitlinkSha
                + "\"}]}",
            sha));
    peers.answer(
        PeerTarget.GITHOST,
        BLOB + fold + "/.gitmodules",
        FakePeers.Scripted.ok(
            "[submodule \"qits-ci-frontend\"]\n\tpath = webui\n\turl = ../qits-ci-frontend.git\n",
            sha));
  }

  /** Whether an answer's entry is the screenshot-baselines kind's — the one most suites drive. */
  static boolean screenshots(
      eu.wohlben.qits.maintenance.dto.ReleaseRequestAutomationsDto.AutomationDto entry) {
    return ScreenshotBaselinesAutomation.KIND.equals(entry.kind());
  }

  /** One kind's entry in an answer. */
  static eu.wohlben.qits.maintenance.dto.ReleaseRequestAutomationsDto.AutomationDto entry(
      eu.wohlben.qits.maintenance.dto.ReleaseRequestAutomationsDto answer, String kind) {
    return answer.automations().stream()
        .filter(candidate -> kind.equals(candidate.kind()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no " + kind + " entry in " + answer));
  }

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
