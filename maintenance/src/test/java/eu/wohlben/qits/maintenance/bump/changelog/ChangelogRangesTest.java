package eu.wohlben.qits.maintenance.bump.changelog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.maintenance.bump.ReleaseRequestClient;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.pending.Change;
import eu.wohlben.qits.maintenance.peer.TestPeerClient;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The rules {@link ChangelogRanges} applies to one change, one case each (qits-1143).
 *
 * <p><b>The docs store is a real HTTP server and the client a real {@link ChangelogClient}</b>,
 * through {@link TestPeerClient}: the path is the production one, so a slash that got encoded or a
 * prefix that drifted fails here. The graph, the ledger and the name rule are the {@link
 * ChangelogRanges.Sources} seam, because each is a one-line fact a case states.
 */
class ChangelogRangesTest {

  private static final String REPO = "qits-eventstream";
  private static final String COORDINATE = "eu.wohlben.qits:qits-eventstream";

  private HttpServer server;

  /** Repository → (status, body). Unscripted is 404, which is "never published a changelog". */
  private final Map<String, Object[]> listings = new ConcurrentHashMap<>();

  /** Every path the docs store was asked, in order. */
  private final List<String> asked = new CopyOnWriteArrayList<>();

  private final Map<String, String> producers = new HashMap<>();
  private final Map<String, List<ChangelogRanges.Release>> ledger = new HashMap<>();
  private ChangelogClient client;

  /** Repository → what qits-projects' history says of its cut requests. */
  private final Map<String, List<ReleaseRequestClient.Cut>> history = new HashMap<>();

  /** Repositories whose history cannot be read. */
  private final List<String> historyUnreadable = new ArrayList<>();

  /** Every repository whose history was asked, in order. */
  private final List<String> historyAsked = new ArrayList<>();

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String path = exchange.getRequestURI().getRawPath();
          asked.add(path);
          Object[] scripted = null;
          if (path.startsWith(ChangelogClient.PREFIX)) {
            scripted = listings.get(path.substring(ChangelogClient.PREFIX.length()));
          }
          int status = scripted == null ? 404 : (Integer) scripted[0];
          byte[] body =
              (scripted == null ? "" : (String) scripted[1]).getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
          if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
              out.write(body);
            }
          }
          exchange.close();
        });
    server.start();
    client = new ChangelogClient();
    client.peers = new TestPeerClient("http://127.0.0.1:" + server.getAddress().getPort());
    producers.put("maven " + COORDINATE, REPO);
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  /** The listing, in the order given — which is publish order, and deliberately not version order. */
  private void published(String repository, String... versions) {
    StringBuilder body = new StringBuilder("{\"name\":\"@changelog/" + repository + "\",\"versions\":[");
    for (int i = 0; i < versions.length; i++) {
      body.append(i == 0 ? "" : ",")
          .append("{\"version\":\"")
          .append(versions[i])
          .append("\",\"publishedAt\":\"2026-10-0")
          .append(i + 1)
          .append("T00:00:00Z\",\"files\":[\"CHANGELOG.md\"]}");
    }
    listings.put(repository, new Object[] {200, body.append("]}").toString()});
  }

  private void released(String repository, String version, String sha) {
    ledger.computeIfAbsent(repository, key -> new ArrayList<>())
        .add(new ChangelogRanges.Release(version, sha));
  }

  private ChangelogRanges.Result resolve(Change... changes) {
    return ChangelogRanges.resolve(
        List.of(changes),
        new ChangelogRanges.Sources() {
          @Override
          public boolean internal(Ecosystem ecosystem, String name) {
            return ecosystem == Ecosystem.GITLINK || name.startsWith("eu.wohlben.qits");
          }

          @Override
          public Map<String, String> producers() {
            return producers;
          }

          @Override
          public List<ChangelogRanges.Release> releasesOf(String repository) {
            return ledger.getOrDefault(repository, List.of());
          }

          @Override
          public ChangelogClient.Result published(String repository) {
            return client.versions(repository);
          }

          @Override
          public ChangelogRanges.Unpublished unpublished(String repository) {
            historyAsked.add(repository);
            if (historyUnreadable.contains(repository)) {
              return new ChangelogRanges.Unpublished(java.util.Set.of(), "HTTP 503");
            }
            return new ChangelogRanges.Unpublished(
                ChangelogRanges.neverPublished(history.getOrDefault(repository, List.of())), null);
          }
        });
  }

  /** A request that cut {@code version}, in {@code state}, whose newest publish run ended {@code publish}. */
  private void cut(String repository, String version, String state, String publish) {
    history
        .computeIfAbsent(repository, key -> new ArrayList<>())
        .add(new ReleaseRequestClient.Cut("rr-" + version, version, state, publish));
  }

  private static Change maven(String from, String to) {
    return Change.of(
        Ecosystem.MAVEN, "pom.xml", COORDINATE, from, to, "property:qits.eventstream.version");
  }

  private static Change gitlink(String fromSha, String to) {
    return Change.of(
        Ecosystem.GITLINK, "webui", "qits-ci-frontend", fromSha, to, "gitlink:webui");
  }

  private static final String SHA_A = "aa11bb22cc33dd44ee55ff6677889900aabbccdd";
  private static final String SHA_B = "bb11bb22cc33dd44ee55ff6677889900aabbccdd";
  private static final String SHA_C = "cc11bb22cc33dd44ee55ff6677889900aabbccdd";

  @Test
  void anExternalChangeHasNoRangeAndAsksNothing() {
    ChangelogRanges.Result result =
        resolve(Change.of(Ecosystem.MAVEN, "pom.xml", "io.quarkus:quarkus-bom", "3.34.5", "3.34.6", "property:q"));

    assertTrue(result.ranges().isEmpty());
    assertTrue(result.problems().isEmpty());
    assertFalse(result.transientFailure());
    assertTrue(asked.isEmpty(), "an external dependency has no changelog to look for");
  }

  /**
   * THE ORDINARY CASE: every release after the old pin up to and including the new one, in CALVER
   * order — 61854 is 06:18:54 and older than 171656, which a string sort gets backwards.
   */
  @Test
  void aMavenRangeSpansEveryReleaseAfterFromUpToAndIncludingTo() {
    released(REPO, "2026.1006.90000", SHA_A);
    released(REPO, "2026.1007.61854", SHA_B);
    released(REPO, "2026.1007.171656", SHA_C);
    released(REPO, "2026.1008.1", SHA_C);
    published(REPO, "2026.1007.171656", "2026.1006.90000", "2026.1008.1", "2026.1007.61854");

    Change change = maven("2026.1006.90000", "2026.1008.1");
    ChangelogRanges.Result result = resolve(change);

    assertEquals(List.of(), result.problems());
    assertEquals(
        new ChangelogRange(REPO, List.of("2026.1007.61854", "2026.1007.171656", "2026.1008.1")),
        result.ranges().get(change));
    assertEquals(
        List.of("/artifacts/docs/docs/@changelog/" + REPO),
        asked,
        "the site name goes into the path literally, @ and slash included");
  }

  @Test
  void aGitlinkShaMappedThroughTheLedgerBoundsTheRange() {
    released("qits-ci-frontend", "2026.1001.1", SHA_A);
    released("qits-ci-frontend", "2026.1002.1", SHA_B);
    released("qits-ci-frontend", "2026.1003.1", SHA_C);
    published("qits-ci-frontend", "2026.1001.1", "2026.1002.1", "2026.1003.1");

    Change change = gitlink(SHA_A.substring(0, 12), "2026.1003.1");
    ChangelogRanges.Result result = resolve(change);

    assertEquals(List.of(), result.problems());
    assertEquals(
        new ChangelogRange("qits-ci-frontend", List.of("2026.1002.1", "2026.1003.1")),
        result.ranges().get(change),
        "the gitlink's name is its repository, and an abbreviated sha still maps");
  }

  @Test
  void aGitlinkShaNoReleaseWasCutFromCarriesOnlyTheNewVersion() {
    released("qits-ci-frontend", "2026.1001.1", SHA_A);
    released("qits-ci-frontend", "2026.1002.1", SHA_B);
    published("qits-ci-frontend", "2026.1001.1", "2026.1002.1");

    Change change = gitlink("dead00000000000000000000000000000000beef", "2026.1002.1");
    ChangelogRanges.Result result = resolve(change);

    assertEquals(List.of(), result.problems());
    assertEquals(
        new ChangelogRange("qits-ci-frontend", List.of("2026.1002.1")), result.ranges().get(change));
  }

  /** Releases cut before the epic have no changelog, and they are left out — not reported. */
  @Test
  void theOldestPublishedChangelogIsTheFloor() {
    released(REPO, "2026.1001.1", SHA_A);
    released(REPO, "2026.1002.1", SHA_B);
    released(REPO, "2026.1003.1", SHA_C);
    published(REPO, "2026.1002.1", "2026.1003.1");

    Change change = maven("2026.1000.1", "2026.1003.1");
    ChangelogRanges.Result result = resolve(change);

    assertEquals(List.of(), result.problems());
    assertEquals(
        new ChangelogRange(REPO, List.of("2026.1002.1", "2026.1003.1")), result.ranges().get(change));
  }

  /** The ledger knows a release above the floor that the store does not: its publish failed. */
  @Test
  void aHoleInThePublishedListIsAProblemNamingTheRelease() {
    released(REPO, "2026.1001.1", SHA_A);
    released(REPO, "2026.1002.1", SHA_B);
    released(REPO, "2026.1003.1", SHA_C);
    published(REPO, "2026.1001.1", "2026.1003.1");

    ChangelogRanges.Result result = resolve(maven("2026.1001.1", "2026.1003.1"));

    assertEquals(
        List.of(
            "no changelog for qits-eventstream 2026.1002.1 (@changelog/qits-eventstream): every"
                + " release publishes one, so this release's publish did not complete"),
        result.problems());
    assertFalse(result.transientFailure());
  }

  /**
   * The new pin is a release whether or not the ledger recorded it — it is what the bump writes —
   * so its missing changelog is found even when neither source names it.
   */
  @Test
  void theNewPinIsACandidateEvenWhenNeitherSourceNamesIt() {
    published(REPO, "2026.1001.1");

    ChangelogRanges.Result result = resolve(maven("2026.1001.1", "2026.1002.1"));

    assertEquals(
        List.of(
            "no changelog for qits-eventstream 2026.1002.1 (@changelog/qits-eventstream): every"
                + " release publishes one, so this release's publish did not complete"),
        result.problems());
  }

  /** The listing covers a release the ledger never recorded — a frame this service missed. */
  @Test
  void aVersionTheStoreHasAndTheLedgerLacksIsIncluded() {
    released(REPO, "2026.1001.1", SHA_A);
    released(REPO, "2026.1003.1", SHA_C);
    published(REPO, "2026.1001.1", "2026.1002.1", "2026.1003.1");

    Change change = maven("2026.1001.1", "2026.1003.1");
    ChangelogRanges.Result result = resolve(change);

    assertEquals(List.of(), result.problems());
    assertEquals(
        new ChangelogRange(REPO, List.of("2026.1002.1", "2026.1003.1")), result.ranges().get(change));
  }

  @Test
  void anInternalCoordinateNobodyPublishesIsAProblem() {
    Change change =
        Change.of(Ecosystem.MAVEN, "pom.xml", "eu.wohlben.qits:orphan", "2026.1.1", "2026.2.1", "property:o");
    ChangelogRanges.Result result = resolve(change);

    assertEquals(
        List.of(
            "no source repository is known for maven eu.wohlben.qits:orphan, so its changelogs"
                + " cannot be found"),
        result.problems());
    assertTrue(result.ranges().isEmpty());
  }

  /** An unreadable store says nothing about the changelogs: retry, never fail. */
  @Test
  void aServerErrorIsATransientFailureAndNotAProblem() {
    listings.put(REPO, new Object[] {503, "busy"});

    ChangelogRanges.Result result = resolve(maven("2026.1001.1", "2026.1003.1"));

    assertTrue(result.transientFailure());
    assertTrue(result.problems().isEmpty());
    assertTrue(result.ranges().isEmpty());
  }

  /** A repository whose every release predates changelogs is listed as today. */
  @Test
  void aRepositoryWithNoChangelogAtAllHasNoRangeAndNoProblem() {
    released(REPO, "2026.1001.1", SHA_A);
    released(REPO, "2026.1002.1", SHA_B);

    ChangelogRanges.Result result = resolve(maven("2026.1001.1", "2026.1002.1"));

    assertTrue(result.ranges().isEmpty());
    assertTrue(result.problems().isEmpty());
    assertFalse(result.transientFailure());
  }

  /** Two coordinates of one reactor share a repository, and the store is asked about it once. */
  @Test
  void oneRepositoryIsAskedOncePerResolve() {
    producers.put("maven eu.wohlben.qits:qits-eventstream-bom", REPO);
    published(REPO, "2026.1001.1", "2026.1002.1");

    Change one = maven("2026.1001.1", "2026.1002.1");
    Change two =
        Change.of(
            Ecosystem.MAVEN,
            "pom.xml",
            "eu.wohlben.qits:qits-eventstream-bom",
            "2026.1001.1",
            "2026.1002.1",
            "dependency:eu.wohlben.qits:qits-eventstream-bom");
    ChangelogRanges.Result result = resolve(one, two);

    assertEquals(2, result.ranges().size());
    assertEquals(1, asked.size());
  }

  // --- tagged, and published nothing (qits-1156) ------------------------------------------------

  private static final String HOLE =
      "no changelog for qits-eventstream 2026.1002.1 (@changelog/qits-eventstream): every"
          + " release publishes one, so this release's publish did not complete";

  /**
   * THE LIVE CASE: 2026.1002.1 was tagged, then a later request superseded its request mid-publish
   * and the run was cancelled. It published nothing, so it is skipped rather than reported.
   */
  @Test
  void aTaggedReleaseWhoseRequestWentObsoleteMidPublishIsSkipped() {
    released(REPO, "2026.1001.1", SHA_A);
    released(REPO, "2026.1002.1", SHA_B);
    released(REPO, "2026.1003.1", SHA_C);
    published(REPO, "2026.1001.1", "2026.1003.1");
    cut(REPO, "2026.1002.1", "OBSOLETE", "CANCELLED");
    cut(REPO, "2026.1003.1", "FINALIZED", "SUCCESS");

    Change change = maven("2026.1001.1", "2026.1003.1");
    ChangelogRanges.Result result = resolve(change);

    assertEquals(List.of(), result.problems());
    assertFalse(result.transientFailure());
    assertEquals(new ChangelogRange(REPO, List.of("2026.1003.1")), result.ranges().get(change));
  }

  /** A request withdrawn after its tag, before any publish run began, published nothing too. */
  @Test
  void aTaggedReleaseWithdrawnBeforeItsPublishBeganIsSkipped() {
    released(REPO, "2026.1002.1", SHA_B);
    published(REPO, "2026.1001.1", "2026.1003.1");
    cut(REPO, "2026.1002.1", "WITHDRAWN", null);

    Change change = maven("2026.1001.1", "2026.1003.1");
    ChangelogRanges.Result result = resolve(change);

    assertEquals(List.of(), result.problems());
    assertEquals(new ChangelogRange(REPO, List.of("2026.1003.1")), result.ranges().get(change));
  }

  /** The new pin itself published nothing: no changelog to carry, and no problem. */
  @Test
  void aNewPinThatPublishedNothingLeavesNoRange() {
    published(REPO, "2026.1001.1");
    cut(REPO, "2026.1002.1", "OBSOLETE", "CANCELLED");

    ChangelogRanges.Result result = resolve(maven("2026.1001.1", "2026.1002.1"));

    assertEquals(List.of(), result.problems());
    assertTrue(result.ranges().isEmpty());
  }

  /** A publish run that FINISHED without the changelog broke, whether it went red or green. */
  @Test
  void aPublishRunThatFinishedWithoutTheChangelogStillFails() {
    for (String[] shape :
        List.of(
            new String[] {"FINALIZED", "FAILED"},
            new String[] {"OBSOLETE", "FAILED"},
            new String[] {"OBSOLETE", "SUCCESS"},
            new String[] {"FINALIZED", "SUCCESS"})) {
      history.clear();
      released(REPO, "2026.1002.1", SHA_B);
      published(REPO, "2026.1001.1", "2026.1003.1");
      cut(REPO, "2026.1002.1", shape[0], shape[1]);

      ChangelogRanges.Result result = resolve(maven("2026.1001.1", "2026.1003.1"));

      assertEquals(List.of(HOLE), result.problems(), String.join(" ", shape));
      assertFalse(result.transientFailure());
    }
  }

  /** A RELEASED request whose run was cancelled can still be retried, so its hole stays a problem. */
  @Test
  void aCancelledRunOfAStillReleasedRequestStillFails() {
    released(REPO, "2026.1002.1", SHA_B);
    published(REPO, "2026.1001.1", "2026.1003.1");
    cut(REPO, "2026.1002.1", "RELEASED", "CANCELLED");

    ChangelogRanges.Result result = resolve(maven("2026.1001.1", "2026.1003.1"));

    assertEquals(List.of(HOLE), result.problems());
  }

  /** A version no request is known to have cut is a hole, as it always was. */
  @Test
  void aVersionTheHistoryDoesNotNameStillFails() {
    released(REPO, "2026.1002.1", SHA_B);
    published(REPO, "2026.1001.1", "2026.1003.1");
    cut(REPO, "2026.1003.1", "FINALIZED", "SUCCESS");

    ChangelogRanges.Result result = resolve(maven("2026.1001.1", "2026.1003.1"));

    assertEquals(List.of(HOLE), result.problems());
  }

  /** One request of a version published, another published nothing: the version ran, so it fails. */
  @Test
  void aVersionOneOfWhoseRequestsRanStillFails() {
    released(REPO, "2026.1002.1", SHA_B);
    published(REPO, "2026.1001.1", "2026.1003.1");
    cut(REPO, "2026.1002.1", "OBSOLETE", "CANCELLED");
    cut(REPO, "2026.1002.1", "FINALIZED", "FAILED");

    ChangelogRanges.Result result = resolve(maven("2026.1001.1", "2026.1003.1"));

    assertEquals(List.of(HOLE), result.problems());
  }

  /** An unreadable qits-projects says nothing about the hole: retry, never fail and never skip. */
  @Test
  void anUnreadableHistoryIsATransientFailure() {
    released(REPO, "2026.1002.1", SHA_B);
    published(REPO, "2026.1001.1", "2026.1003.1");
    historyUnreadable.add(REPO);

    ChangelogRanges.Result result = resolve(maven("2026.1001.1", "2026.1003.1"));

    assertTrue(result.transientFailure());
    assertTrue(result.problems().isEmpty());
    assertTrue(result.ranges().isEmpty());
  }

  /** With no changelog missing, qits-projects is never asked. */
  @Test
  void theHistoryIsReadOnlyWhenAChangelogIsMissing() {
    published(REPO, "2026.1001.1", "2026.1002.1");

    resolve(maven("2026.1001.1", "2026.1002.1"));

    assertTrue(historyAsked.isEmpty(), "asked: " + historyAsked);
  }
}
