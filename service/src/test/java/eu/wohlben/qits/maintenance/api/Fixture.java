package eu.wohlben.qits.maintenance.api;

import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.manifest.GitmodulesParser;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import java.util.Map;

/**
 * One repository as the peers would describe it.
 *
 * <p>It is deliberately a WHOLE repository rather than a minimal one: a pom with a property and a
 * literal, a package.json with a lock, a Dockerfile with an internal and an external image, a
 * {@code .gitmodules} with the tree entry that pins it, and a grouping file. The point of the suite
 * is the seams between those, and a fixture that carried one pin would exercise none of them.
 */
public final class Fixture {

  public static final String PROJECT = "qits";
  public static final String REPOSITORY = "qits-ci";

  /**
   * The catalog row's own id, as {@link #scriptScan} answers it — and the ONE thing the release ask
   * is addressed by. A fixture whose listing carried no {@code id} would leave every pushed branch
   * recording "has no catalog id on its inventory row" instead of asking.
   */
  public static final String CATALOG_ID = "r1";

  public static final String HEAD_SHA = "3f1a9c0b7d2e4f5a6b8c9d0e1f2a3b4c5d6e7f80";
  public static final String BRANCH = "maintenance/dependencies";
  public static final String BUMPED_SHA = "aa11bb22cc33dd44ee55ff6677889900aabbccdd";

  /**
   * The release-request route AS THIS SERVICE SPELLS IT. {@code FakePeers} keys on the target and
   * the whole path, so a fixture that armed a different one would leave the route unscripted and the
   * call would read the unregistered-path 404 — which is a refusal shape, and the test would fail
   * somewhere else entirely.
   */
  public static final String RELEASE_REQUESTS_PATH =
      eu.wohlben.qits.maintenance.bump.ReleaseRequestClient.REQUESTS_PATH_PREFIX
          + CATALOG_ID
          + eu.wohlben.qits.maintenance.bump.ReleaseRequestClient.REQUESTS_PATH_SUFFIX;

  private static final String TREE = "/git/" + PROJECT + "/" + REPOSITORY + "/tree/";
  private static final String BLOB = "/git/" + PROJECT + "/" + REPOSITORY + "/blob/" + HEAD_SHA + "/";

  /**
   * A REAL REACTOR, because a single-module pom exercises none of what broke live: a parent that is
   * the repository's own root, a groupId written as an expression, a module pinned at
   * {@code ${project.version}}, and a property nobody declared.
   */
  private static final String POM =
      """
      <project>
        <parent>
          <groupId>eu.wohlben.qits</groupId>
          <artifactId>qits-parent</artifactId>
          <version>2026.800.1</version>
        </parent>
        <groupId>eu.wohlben.qits</groupId>
        <artifactId>qits-ci</artifactId>
        <version>2026.821.1</version>
        <properties>
          <qits.eventstream.version>2026.811.1</qits.eventstream.version>
          <qits.arch-rules.version>2026.817.175344</qits.arch-rules.version>
          <quarkus.platform.group-id>io.quarkus.platform</quarkus.platform.group-id>
          <quarkus.platform.artifact-id>quarkus-bom</quarkus.platform.artifact-id>
          <quarkus.platform.version>3.34.5</quarkus.platform.version>
        </properties>
        <modules>
          <module>service</module>
        </modules>
        <dependencies>
          <dependency>
            <groupId>eu.wohlben.qits</groupId>
            <artifactId>qits-eventstream</artifactId>
            <version>${qits.eventstream.version}</version>
          </dependency>
          <dependency>
            <groupId>${quarkus.platform.group-id}</groupId>
            <artifactId>${quarkus.platform.artifact-id}</artifactId>
            <version>${quarkus.platform.version}</version>
          </dependency>
        </dependencies>
      </project>
      """;

  /** The module: its parent IS this repository's root, and one of its dependencies is a sibling. */
  private static final String MODULE_POM =
      """
      <project>
        <parent>
          <groupId>eu.wohlben.qits</groupId>
          <artifactId>qits-ci</artifactId>
          <version>2026.821.1</version>
        </parent>
        <artifactId>qits-ci-service</artifactId>
        <dependencies>
          <dependency>
            <groupId>${project.groupId}</groupId>
            <artifactId>qits-ci-domain</artifactId>
            <version>${project.version}</version>
          </dependency>
          <dependency>
            <groupId>${project.groupId}</groupId>
            <artifactId>qits-arch-rules</artifactId>
            <version>${qits.arch-rules.version}</version>
          </dependency>
          <dependency>
            <groupId>g</groupId>
            <artifactId>mystery</artifactId>
            <version>${nobody.declared.this}</version>
          </dependency>
        </dependencies>
      </project>
      """;

  private static final String PACKAGE_JSON =
      """
      {"name":"client",
       "dependencies":{"@qits/ui-components":"2026.8.1","@angular/core":"^21.0.0"}}
      """;

  private static final String PACKAGE_LOCK =
      """
      {"lockfileVersion":3,
       "packages":{"":{"name":"client"},
                   "node_modules/@qits/ui-components":{"version":"2026.8.1"},
                   "node_modules/@angular/core":{"version":"21.0.4"}}}
      """;

  private static final String DOCKERFILE =
      """
      FROM mirror.dev.localhost:8080/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
      FROM qits/build-images/maven-base:2026.813.1
      """;

  /**
   * A submodule, because a gitlink is a pin like the others and is like none of them: it is INTERNAL
   * by construction and its VERSION is a commit sha. The tree entry below is what pins it — the file
   * names it and never says what is checked out — and a git host that reports no {@code sha} leaves
   * it uninventoried, which is the other arm {@code ManifestScannerTest} holds.
   */
  private static final String GITMODULES =
      """
      [submodule "qits-ci-frontend"]
      \tpath = webui
      \turl = ../qits-ci-frontend.git
      """;

  /** What the gitlink is pinned at: a commit, which no registry has ever heard of. */
  public static final String GITLINK_SHA = "c0ffee11d00d2233445566778899aabbccddeeff";

  private static final String MAINTENANCE_YML =
      """
      groups:
        - name: angular
          deps: ["@angular/*"]
      """;

  private Fixture() {}

  /** Everything a scan of one repository reads, answered. */
  public static void scriptScan(FakePeers peers) {
    peers.answer(
        PeerTarget.PROJECTS,
        "/projects/api/repositories",
        FakePeers.Scripted.ok(
            "{\"repositories\":[{\"id\":\"" + CATALOG_ID + "\",\"projectId\":\""
                + PROJECT
                + "\",\"name\":\""
                + REPOSITORY
                + "\",\"mainBranch\":\"main\"},"
                // A row with no alias has no address, so a scan must skip it rather than fail on it.
                + "{\"id\":\"r2\",\"projectId\":\"qits\",\"name\":null,\"mainBranch\":\"main\"}]}"));

    scriptGitlinkAt(peers, GITLINK_SHA);
    Map<String, String> sha = Map.of("Git-Commit-Sha", HEAD_SHA);

    peers.answer(PeerTarget.GITHOST, BLOB + "pom.xml", FakePeers.Scripted.ok(POM, sha));
    peers.answer(PeerTarget.GITHOST, BLOB + "service/pom.xml", FakePeers.Scripted.ok(MODULE_POM, sha));
    peers.answer(PeerTarget.GITHOST, BLOB + "package.json", FakePeers.Scripted.ok(PACKAGE_JSON, sha));
    peers.answer(
        PeerTarget.GITHOST, BLOB + "package-lock.json", FakePeers.Scripted.ok(PACKAGE_LOCK, sha));
    peers.answer(PeerTarget.GITHOST, BLOB + "Dockerfile", FakePeers.Scripted.ok(DOCKERFILE, sha));
    peers.answer(
        PeerTarget.GITHOST,
        BLOB + GitmodulesParser.PATH,
        FakePeers.Scripted.ok(GITMODULES, sha));
    peers.answer(
        PeerTarget.GITHOST,
        BLOB + ".config/qits/maintenance.yml",
        FakePeers.Scripted.ok(MAINTENANCE_YML, sha));

    // The registries. Internal names go to qits-artifacts, external ones through the mirror.
    peers.answer(
        PeerTarget.MAVEN_REGISTRY,
        "/eu/wohlben/qits/qits-eventstream/maven-metadata.xml",
        FakePeers.Scripted.ok(metadata("2026.811.1", "2026.821.3", "2026.900.1-SNAPSHOT")));
    peers.answer(
        PeerTarget.MAVEN_MIRROR,
        "/io/quarkus/platform/quarkus-bom/maven-metadata.xml",
        FakePeers.Scripted.ok(metadata("3.34.5", "3.34.6", "3.35.0.CR1")));
    peers.answer(
        PeerTarget.MAVEN_REGISTRY,
        "/eu/wohlben/qits/qits-parent/maven-metadata.xml",
        FakePeers.Scripted.ok(metadata("2026.800.1", "2026.820.1")));
    peers.answer(
        PeerTarget.MAVEN_REGISTRY,
        "/eu/wohlben/qits/qits-arch-rules/maven-metadata.xml",
        FakePeers.Scripted.ok(metadata("2026.817.175344", "2026.822.1")));
    peers.answer(
        PeerTarget.NPM_REGISTRY,
        "/@qits%2fui-components",
        FakePeers.Scripted.ok(packument("2026.8.1", "2026.8.4")));
    peers.answer(
        PeerTarget.NPM_MIRROR,
        "/@angular%2fcore",
        FakePeers.Scripted.ok(packument("21.0.4", "21.1.0")));
    peers.answer(
        PeerTarget.OCI_REGISTRY,
        "/qits/build-images/maven-base/tags/list?n=1000",
        FakePeers.Scripted.ok(
            "{\"name\":\"qits/build-images/maven-base\",\"tags\":"
                + "[\"latest\",\"2026.813.1\",\"2026.821.2\"]}"));
  }

  /**
   * The root listing, with the {@code webui} gitlink recording {@code gitlinkSha} — what a gitlink
   * bump landing on main looks like to the next scan. {@link #scriptScan} answers it at {@link
   * #GITLINK_SHA}.
   */
  public static void scriptGitlinkAt(FakePeers peers, String gitlinkSha) {
    String root =
        "{\"entries\":["
            + "{\"name\":\"pom.xml\",\"type\":\"blob\"},"
            + "{\"name\":\"package.json\",\"type\":\"blob\"},"
            + "{\"name\":\"package-lock.json\",\"type\":\"blob\"},"
            + "{\"name\":\"Dockerfile\",\"type\":\"blob\"},"
            + "{\"name\":\".gitmodules\",\"type\":\"blob\"},"
            // The gitlink itself, as a git host that reports the mode and the object name answers
            // it. Both spellings are read; this is the one qits-githost 33b0ccf serves.
            + "{\"name\":\"webui\",\"type\":\"commit\",\"mode\":\"160000\",\"sha\":\""
            + gitlinkSha
            + "\"},"
            + "{\"name\":\"service\",\"type\":\"tree\"}]}";
    Map<String, String> sha = Map.of("Git-Commit-Sha", HEAD_SHA);
    peers.answer(PeerTarget.GITHOST, TREE + "main", FakePeers.Scripted.ok(root, sha));
    // The same listing at the sha: it is what a 404 on a blob is checked against, and answering it
    // is the difference between ABSENT and GONE.
    peers.answer(PeerTarget.GITHOST, TREE + HEAD_SHA, FakePeers.Scripted.ok(root, sha));
  }

  /** The branch does not exist yet — the ordinary state before a first bump. */
  public static void scriptBranchAbsent(FakePeers peers) {
    peers.answer(PeerTarget.GITHOST, TREE + "maintenance%2Fdependencies", FakePeers.Scripted.status(404, ""));
  }

  /** The branch exists at that sha — what the git host says after a bump ran. */
  public static void scriptBranchAt(FakePeers peers, String sha) {
    peers.answer(
        PeerTarget.GITHOST,
        TREE + "maintenance%2Fdependencies",
        FakePeers.Scripted.ok("{\"entries\":[]}", Map.of("Git-Commit-Sha", sha)));
  }

  /**
   * A branch this service does NOT own, at a sha — a workspace branch carrying a release request.
   *
   * <p>Its own helper rather than a parameter on {@link #scriptBranchAt}: that one is keyed on the
   * fixture's one maintenance branch, and the point of a targeted bump is a ref whose name came from
   * a caller. The revision is percent-encoded exactly as {@code GitHostReader} encodes it, so a
   * branch with a slash in it — which is every branch either side of this ever uses — is armed at
   * the address the reader will actually ask for.
   */
  public static void scriptForeignBranchAt(FakePeers peers, String branch, String sha) {
    peers.answer(
        PeerTarget.GITHOST,
        TREE + branch.replace("/", "%2F"),
        FakePeers.Scripted.ok("{\"entries\":[]}", Map.of("Git-Commit-Sha", sha)));
  }

  /** …and the same branch with the git host away, which must never change a verdict. */
  public static void scriptForeignBranchUnreachable(FakePeers peers, String branch) {
    peers.answer(
        PeerTarget.GITHOST,
        TREE + branch.replace("/", "%2F"),
        FakePeers.Scripted.unreachable("connection refused"));
  }

  /** qits-ci accepts the trigger and names one run. */
  public static void scriptCiAccepts(FakePeers peers, String runId) {
    peers.answer(
        PeerTarget.CI,
        "/ci/api/events/trigger",
        FakePeers.Scripted.ok(
            "{\"eventId\":\"e1\",\"runIds\":[\"" + runId + "\"],\"repositoriesRead\":1,"
                + "\"repositoriesSkipped\":[]}"));
  }

  /**
   * qits-ci has nothing queued and nothing running — the state the dispatch gate waits for.
   *
   * <p>Every test that expects the clock to hand a bump out has to say this: an UNSCRIPTED listing
   * is a 404, and the gate reads a listing it could not read as BUSY.
   */
  public static void scriptCiQueueEmpty(FakePeers peers) {
    peers.answer(PeerTarget.CI, CiClient.ACTIVE_RUNS_PATH, FakePeers.Scripted.ok("{\"runs\":[]}"));
  }

  /** qits-ci is busy: {@code active} runs queued or running, across every repository. */
  public static void scriptCiQueue(FakePeers peers, int active) {
    StringBuilder runs = new StringBuilder("{\"runs\":[");
    for (int index = 0; index < active; index++) {
      runs.append(index == 0 ? "" : ",")
          .append("{\"id\":\"busy-")
          .append(index)
          .append("\",\"status\":\"")
          // One QUEUED and the rest RUNNING: the gate counts the listing rather than matching on a
          // status, and a fixture that only ever said QUEUED would let a filter pass unnoticed.
          .append(index == 0 ? "QUEUED" : "RUNNING")
          .append("\"}");
    }
    peers.answer(
        PeerTarget.CI, CiClient.ACTIVE_RUNS_PATH, FakePeers.Scripted.ok(runs.append("]}").toString()));
  }

  public static void scriptRun(FakePeers peers, String runId, String status) {
    peers.answer(
        PeerTarget.CI,
        "/ci/api/runs/" + runId,
        FakePeers.Scripted.ok("{\"id\":\"" + runId + "\",\"status\":\"" + status + "\"}"));
  }

  /**
   * qits-projects opens (or converges onto) a release request and names it.
   *
   * <p>The wrapper is the point of the shape: the controller answers {@code {"request": {…}}}, so a
   * client reading a flat body would find no id and record a convergence against a service that
   * answered perfectly well.
   */
  public static void scriptReleaseRequestAccepted(FakePeers peers, String requestId) {
    peers.answer(
        PeerTarget.PROJECTS,
        RELEASE_REQUESTS_PATH,
        FakePeers.Scripted.ok(
            "{\"request\":{\"id\":\"" + requestId + "\",\"repoId\":\"" + CATALOG_ID
                + "\",\"backingBranch\":\"release/" + requestId + "\",\"state\":\"PENDING\","
                + "\"mergedSha\":null,\"summary\":\"bump(dependencies): 5 dependencies\","
                + "\"detail\":null,\"version\":null,\"retryable\":false}}"));
  }

  /**
   * qits-projects answers what became of one request — the read the dispatch hold rests on.
   *
   * <p>Keyed on the request's own path, which is the collection's plus the id: the POST that opens a
   * request and the GET that asks after it are two routes, and a fixture arming only the first is
   * what a hold that never asked looked like.
   */
  public static void scriptReleaseRequestState(
      FakePeers peers, String requestId, String state, String detail) {
    peers.answer(
        PeerTarget.PROJECTS,
        RELEASE_REQUESTS_PATH + "/" + requestId,
        FakePeers.Scripted.ok(
            "{\"request\":{\"id\":\"" + requestId + "\",\"repoId\":\"" + CATALOG_ID
                + "\",\"state\":\"" + state + "\",\"detail\":"
                + (detail == null ? "null" : "\"" + detail + "\"")
                + ",\"version\":null,\"retryable\":false}}"));
  }

  /** …and does not answer at all, which must never be read as "the release has stopped". */
  public static void scriptReleaseRequestStateUnreachable(FakePeers peers, String requestId) {
    peers.answer(
        PeerTarget.PROJECTS,
        RELEASE_REQUESTS_PATH + "/" + requestId,
        FakePeers.Scripted.unreachable("connection refused"));
  }

  /** qits-projects answers something else — a 5xx, a refusal, an auth failure. */
  public static void scriptReleaseRequestAnswers(FakePeers peers, int status, String body) {
    peers.answer(
        PeerTarget.PROJECTS, RELEASE_REQUESTS_PATH, FakePeers.Scripted.status(status, body));
  }

  /** qits-projects is not there at all: no status, a transport failure. */
  public static void scriptReleaseRequestUnreachable(FakePeers peers) {
    peers.answer(
        PeerTarget.PROJECTS,
        RELEASE_REQUESTS_PATH,
        FakePeers.Scripted.unreachable("connection refused"));
  }

  private static String metadata(String... versions) {
    StringBuilder xml =
        new StringBuilder("<metadata><versioning><versions>");
    for (String version : versions) {
      xml.append("<version>").append(version).append("</version>");
    }
    return xml.append("</versions></versioning></metadata>").toString();
  }

  private static String packument(String... versions) {
    StringBuilder json = new StringBuilder("{\"versions\":{");
    for (int i = 0; i < versions.length; i++) {
      if (i > 0) {
        json.append(',');
      }
      json.append('"').append(versions[i]).append("\":{}");
    }
    return json.append("},\"dist-tags\":{\"latest\":\"")
        .append(versions[versions.length - 1])
        .append("\"}}")
        .toString();
  }
}
