package eu.wohlben.qits.maintenance.api;

import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.manifest.GitmodulesParser;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import java.time.Instant;
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

  /**
   * A {@code groups:} key from before qits-1133 R5, kept on purpose: it is ignored with a WARN now,
   * and every suite scanning this fixture pins that it does not turn the repository into a
   * CONFIG_ERROR.
   */
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

  /**
   * The repositories that publish the fixture's internal coordinates, as the release events would
   * have recorded them — the {@code ArtifactGraph.producers()} a bump's changelog ranges read
   * (qits-893). Without them every internal change of {@link #scriptScan}'s pins has no source
   * repository, and a bump of them FAILS before it is sent, which is the rule and not what a test of
   * anything else means to say. The gitlink needs none: its name is its repository.
   *
   * <p>No changelog is scripted for any of them, so the docs store answers 404 — every one of these
   * repositories predates changelogs — and the payload is the one it always was.
   */
  public static void seedProducers(MaintenanceStore store) {
    Instant at = Instant.parse("2026-08-01T00:00:00Z");
    store.upsertArtifact(
        Ecosystem.MAVEN, "eu.wohlben.qits:qits-eventstream", "2026.811.1", "qits-eventstream", at);
    store.upsertArtifact(
        Ecosystem.MAVEN, "eu.wohlben.qits:qits-parent", "2026.800.1", "qits-parent", at);
    store.upsertArtifact(
        Ecosystem.MAVEN, "eu.wohlben.qits:qits-arch-rules", "2026.817.175344", "qits-arch-rules", at);
    store.upsertArtifact(
        Ecosystem.NPM, "@qits/ui-components", "2026.8.1", "qits-ui-components-jslib", at);
    store.upsertArtifact(
        Ecosystem.DOCKER, "qits/build-images/maven-base", "2026.813.1", "qits-build-images", at);
  }

  /** The docs store's listing of {@code @changelog/<repository>}: these versions, in this order. */
  public static void scriptChangelogs(FakePeers peers, String repository, String... versions) {
    StringBuilder body =
        new StringBuilder("{\"name\":\"@changelog/" + repository + "\",\"versions\":[");
    for (int i = 0; i < versions.length; i++) {
      body.append(i == 0 ? "" : ",")
          .append("{\"version\":\"")
          .append(versions[i])
          .append("\",\"publishedAt\":\"2026-10-01T00:00:00Z\"}");
    }
    peers.answer(
        PeerTarget.ARTIFACTS_DOCS,
        CHANGELOG_PATH + repository,
        FakePeers.Scripted.ok(body.append("]}").toString()));
  }

  /** The docs store's changelog listing route, up to the repository name. */
  public static final String CHANGELOG_PATH = "/artifacts/docs/docs/@changelog/";

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
   * qits-ci has nothing queued and nothing running, and one connected runner with ONE slot — room
   * for exactly one bump, the state the dispatch gate waits for.
   *
   * <p>Every test that expects the clock to hand a bump out has to say this: an UNSCRIPTED snapshot
   * is a 404, and the gate reads a snapshot it could not read as BUSY.
   */
  public static void scriptCiQueueEmpty(FakePeers peers) {
    scriptCiQueue(peers, 0, runner("qits-ci", 1, true, false));
  }

  /** qits-ci is full: {@code active} runs queued or running on a runner with that many slots. */
  public static void scriptCiQueue(FakePeers peers, int active) {
    scriptCiQueue(peers, active, runner("qits-ci", Math.max(1, active), true, false));
  }

  /**
   * qits-ci's queue snapshot — {@code GET /ci/api/runs/queue} — with {@code active} runs and these
   * runners.
   *
   * <p>The first active run is QUEUED and the rest RUNNING: the gate counts both halves rather than
   * matching on a status, and a fixture that only ever filled one half would let a gate that read
   * only the other pass unnoticed.
   *
   * @param runners JSON objects, see {@link #runner}
   */
  public static void scriptCiQueue(FakePeers peers, int active, String... runners) {
    StringBuilder running = new StringBuilder();
    StringBuilder queued = new StringBuilder();
    for (int index = 0; index < active; index++) {
      StringBuilder half = index == 0 ? queued : running;
      half.append(half.length() == 0 ? "" : ",")
          .append("{\"id\":\"busy-")
          .append(index)
          .append("\",\"status\":\"")
          .append(index == 0 ? "QUEUED" : "RUNNING")
          .append("\"}");
    }
    peers.answer(
        PeerTarget.CI,
        CiClient.QUEUE_PATH,
        FakePeers.Scripted.ok(
            "{\"generatedAt\":\"2026-10-03T12:00:00Z\",\"running\":["
                + running
                + "],\"queued\":["
                + queued
                + "],\"runners\":["
                + String.join(",", runners)
                + "]}"));
  }

  /** One runner as qits-ci's queue snapshot lists it. */
  public static String runner(String name, int slots, boolean connected, boolean quarantined) {
    return "{\"id\":\"00000000-0000-0000-0000-"
        + String.format("%012d", Math.abs(name.hashCode()))
        + "\",\"name\":\""
        + name
        + "\",\"slots\":"
        + slots
        + ",\"held\":0,\"connected\":"
        + connected
        + ",\"quarantined\":"
        + quarantined
        + "}";
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

  /**
   * The repository's whole release-request history — {@code GET …/release-requests?state=all}, the
   * read a bump's base is chosen from (qits-1081). A different path from the collection's POST, so
   * arming it never answers the release ask.
   */
  public static final String RELEASE_LISTING_PATH = RELEASE_REQUESTS_PATH + "?state=all";

  /** The commit an unmerged release tag points at — on no branch the fixture otherwise names. */
  public static final String TAG_SHA = "7a9e5c3b1d0f2e4a6c8b0d1f3e5a7c9b2d4f6e80";

  /** The tag {@link #TAG_SHA} is. */
  public static final String TAG_VERSION = "2026.1007.171656";

  /**
   * qits-projects lists one release that is cut and has not reached main, {@link #TAG_VERSION} at
   * {@link #TAG_SHA} — beside the shapes that must NOT be chosen: an older unmerged release that
   * sorts AFTER it as text ({@code 2026.1007.61854}), a newer one that has merged, and a request
   * that never released.
   */
  public static void scriptUnmergedRelease(FakePeers peers) {
    peers.answer(
        PeerTarget.PROJECTS,
        RELEASE_LISTING_PATH,
        FakePeers.Scripted.ok(
            "{\"requests\":["
                + "{\"id\":\"rr-open\",\"state\":\"PENDING\",\"version\":null,\"releasedSha\":null,"
                + "\"mergedToMainAt\":null},"
                + "{\"id\":\"rr-merged\",\"state\":\"FINALIZED\",\"version\":\"2026.1008.10000\","
                + "\"releasedSha\":\"1111111111111111111111111111111111111111\","
                + "\"mergedToMainAt\":\"2026-10-08T01:00:00Z\"},"
                + "{\"id\":\"rr-newest\",\"state\":\"RELEASED\",\"version\":\"" + TAG_VERSION + "\","
                + "\"releasedSha\":\"" + TAG_SHA + "\",\"mergedToMainAt\":null},"
                + "{\"id\":\"rr-older\",\"state\":\"OBSOLETE\",\"version\":\"2026.1007.61854\","
                + "\"releasedSha\":\"2222222222222222222222222222222222222222\","
                + "\"mergedToMainAt\":null}]}"));
  }

  /**
   * The git host's ancestry door — {@code GET /githost/api/repositories/<catalog id>/contains} — for
   * one (commit, in) pair. Unscripted, the pair is a 404: a failed answer, never a false.
   */
  public static void scriptContains(FakePeers peers, String commit, String in, boolean contains) {
    peers.answer(
        PeerTarget.GITHOST,
        containsPath(commit, in),
        FakePeers.Scripted.ok(
            "{\"repoId\":\"" + CATALOG_ID + "\",\"commit\":\"" + commit + "\",\"in\":\"" + in
                + "\",\"contains\":" + contains + "}"));
  }

  /** The ancestry door's path for one pair, as {@code GitHostReader} spells it. */
  public static String containsPath(String commit, String in) {
    return "/githost/api/repositories/" + CATALOG_ID + "/contains?commit=" + commit + "&in=" + in;
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
