package eu.wohlben.qits.maintenance.latest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.automation.UpstreamReplan;
import eu.wohlben.qits.maintenance.entity.MtLatest;
import eu.wohlben.qits.maintenance.githost.GitHostReader;
import eu.wohlben.qits.maintenance.githost.TreeLookup;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.GroupSource;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The gitlink latest is the release a repository's {@code main} head carries</b> (qits-1257) —
 * and the reconcile that heals the rows the cut-tag rule wrote.
 *
 * <p>The store is the real one, because the overwrite is the point: the rows qits-qits-rr-137 was
 * planned from named releases that were cut and never finalized, and the forward-only writer they
 * came through could never move them back. The git host and the re-plan hooks are recording
 * stand-ins.
 *
 * <p><b>Assertions are per repository</b>, as in {@code ReleaseLedgerBackfillTest}: this module
 * has no {@code InventoryReset}, and the reconcile walks every repository in the store, which is
 * exactly what it does after a boot.
 */
@QuarkusTest
class MainHeadLatestTest {

  private static final String FINALIZED = "2026.1014.173542";
  private static final String FINALIZED_SHA = "1734217342173421734217342173421734217342";
  private static final String UNFINALIZED = "2026.1014.232301";
  private static final String UNFINALIZED_SHA = "2323023230232302323023230232302323023230";

  @Inject MaintenanceStore store;

  /**
   * A git host that answers main's head for the repositories it is told about, gone otherwise —
   * and containment for the {@code "<catalog id> <commit> <in>"} questions it is told about,
   * unanswered otherwise, recording every one it was asked.
   */
  private static final class RecordingGitHost extends GitHostReader {

    final Map<String, TreeLookup> heads = new LinkedHashMap<>();
    final Map<String, Boolean> containment = new LinkedHashMap<>();
    final List<String> asked = new ArrayList<>();

    @Override
    public TreeLookup head(String project, String repository, String revision) {
      return heads.getOrDefault(repository + " " + revision, TreeLookup.gone());
    }

    @Override
    public Containment contains(String repoId, String commit, String in) {
      String question = repoId + " " + commit + " " + in;
      asked.add(question);
      Boolean answer = containment.get(question);
      return new Containment(answer, answer == null ? "the git host is not there" : null);
    }
  }

  /** The two hooks, recording what they were told instead of queueing a re-plan. */
  private static final class RecordingUpstream extends UpstreamReplan {

    final List<String> moved = new ArrayList<>();
    final List<String> released = new ArrayList<>();

    @Override
    public void latestMoved(Ecosystem ecosystem, String name) {
      moved.add(ecosystem.wireName() + ":" + name);
    }

    @Override
    public void released(String repository) {
      released.add(repository);
    }
  }

  private MainHeadLatest mainHead;
  private RecordingGitHost gitHost;
  private RecordingUpstream upstream;
  private String run;

  @BeforeEach
  void aRunOfItsOwn() {
    run = "-" + UUID.randomUUID().toString().substring(0, 8);
    gitHost = new RecordingGitHost();
    upstream = new RecordingUpstream();
    mainHead = new MainHeadLatest();
    mainHead.store = store;
    mainHead.gitHost = gitHost;
    mainHead.upstream = upstream;
  }

  /** The catalog id a repository is scanned under: what containment is asked by. */
  private static String catalogId(String repository) {
    return "id-" + repository;
  }

  private void scanned(String repository) {
    store.replaceInventory(
        repository, "qits", catalogId(repository), null, "main", RepositoryStatus.OK, "sha", null, List.of(),
        List.of(), GroupSource.DEFAULT, candidate -> PinKind.INTERNAL, Instant.now());
  }

  /** Both of edge's releases in the ledger, as their {@code SCMRelease}s recorded them. */
  private void bothReleasesCut(String repository) {
    store.recordRelease(
        repository, FINALIZED, FINALIZED_SHA, Instant.parse("2026-10-14T17:35:42Z"), List.of());
    store.recordRelease(
        repository, UNFINALIZED, UNFINALIZED_SHA, Instant.parse("2026-10-14T23:23:01Z"), List.of());
  }

  private MtLatest row(String repository) {
    return store.latest(Ecosystem.GITLINK, repository).orElseThrow();
  }

  /**
   * <b>THE REPAIR.</b> The row the old rule wrote names the newest tag CUT, {@code
   * 2026.1014.232301}, which never finalized; edge's {@code main} is the commit of {@code
   * 2026.1014.173542}. The reconcile reads the head and overwrites — a move BACKWARDS in version
   * order, which the forward-only writer could never make — and tells both re-plan hooks, so open
   * wrapper requests stop pinning the unfinalized release.
   */
  @Test
  void theReconcileOverwritesARowNamingAnUnfinalizedTagWithTheReleaseMainCarries() {
    String edge = "qits-edge-service" + run;
    scanned(edge);
    bothReleasesCut(edge);
    store.recordLatestIfNewer(
        Ecosystem.GITLINK, edge, UNFINALIZED, GitlinkSha.of(UNFINALIZED_SHA), Instant.now());
    gitHost.heads.put(edge + " main", TreeLookup.found(FINALIZED_SHA, List.of()));
    // Asked since qits-1823, because the move is backwards: main does not contain the cut tag.
    gitHost.containment.put(catalogId(edge) + " " + UNFINALIZED_SHA + " main", false);

    mainHead.reconcile();

    MtLatest healed = row(edge);
    assertEquals(FINALIZED, healed.latest, "the release main carries, not the newest one cut");
    assertEquals(FINALIZED_SHA, GitlinkSha.read(healed.sourceUrl).orElseThrow());
    assertTrue(upstream.moved.contains("gitlink:" + edge), upstream.moved.toString());
    assertTrue(upstream.released.contains(edge), upstream.released.toString());
    assertEquals(List.of(catalogId(edge) + " " + UNFINALIZED_SHA + " main"), asked(edge));
  }

  /** The containment questions asked about one repository of this run. */
  private List<String> asked(String repository) {
    return gitHost.asked.stream()
        .filter(question -> question.startsWith(catalogId(repository) + " "))
        .toList();
  }

  /**
   * <b>qits-1823: a stale head does not rewind a finalized row.</b> The row names the newer release
   * — finalized, its commit on main — and the git host answers main with the older one's commit, as
   * qits-githost did for qits-platform-access-cli one second after a finalize push. Main contains
   * the row's commit, so the head read was stale: the row stays, and nothing is re-planned.
   */
  @Test
  void aHeadOlderThanARowMainAlreadyContainsLeavesTheRow() {
    String cli = "qits-platform-access-cli" + run;
    scanned(cli);
    bothReleasesCut(cli);
    store.recordGitlinkLatest(cli, UNFINALIZED, GitlinkSha.of(UNFINALIZED_SHA), Instant.now());
    gitHost.containment.put(catalogId(cli) + " " + UNFINALIZED_SHA + " main", true);

    assertEquals(
        MainHeadLatest.Outcome.STALE_HEAD, mainHead.record(cli, FINALIZED_SHA, Instant.now()));

    assertEquals(UNFINALIZED, row(cli).latest, "the release main contains, not the stale head's");
    assertEquals(UNFINALIZED_SHA, GitlinkSha.read(row(cli).sourceUrl).orElseThrow());
    assertEquals(List.of(catalogId(cli) + " " + UNFINALIZED_SHA + " main"), asked(cli),
        "asked of main by NAME, so the git host resolves it afresh");
    assertTrue(upstream.released.stream().noneMatch(cli::equals), upstream.released.toString());
    assertTrue(upstream.moved.stream().noneMatch(moved -> moved.endsWith(cli)));
  }

  /**
   * The rr-137 shape through {@link MainHeadLatest#record} itself: the row names a cut tag main does
   * NOT contain, so the older head is the truth and overwrites it, exactly as before qits-1823.
   */
  @Test
  void aHeadOlderThanARowMainDoesNotContainStillOverwritesIt() {
    String edge = "qits-unfinalized-row" + run;
    scanned(edge);
    bothReleasesCut(edge);
    store.recordGitlinkLatest(edge, UNFINALIZED, GitlinkSha.of(UNFINALIZED_SHA), Instant.now());
    gitHost.containment.put(catalogId(edge) + " " + UNFINALIZED_SHA + " main", false);

    assertEquals(
        MainHeadLatest.Outcome.MOVED, mainHead.record(edge, FINALIZED_SHA, Instant.now()));

    assertEquals(FINALIZED, row(edge).latest);
    assertEquals(FINALIZED_SHA, GitlinkSha.read(row(edge).sourceUrl).orElseThrow());
    assertTrue(upstream.released.contains(edge), upstream.released.toString());
  }

  /**
   * Containment that cannot be read — the git host is not there, or the repository has no catalog
   * id to ask it under — keeps the row: the conservative half, and the next scan or push asks
   * again.
   */
  @Test
  void anUnreadableContainmentKeepsTheRow() {
    String dark = "qits-dark-containment" + run;
    String unaddressed = "qits-no-catalog-id" + run;
    scanned(dark);
    store.replaceInventory(
        unaddressed, "qits", null, null, "main", RepositoryStatus.OK, "sha", null, List.of(),
        List.of(), GroupSource.DEFAULT, candidate -> PinKind.INTERNAL, Instant.now());
    for (String repository : List.of(dark, unaddressed)) {
      bothReleasesCut(repository);
      store.recordGitlinkLatest(
          repository, UNFINALIZED, GitlinkSha.of(UNFINALIZED_SHA), Instant.now());

      assertEquals(
          MainHeadLatest.Outcome.STALE_HEAD,
          mainHead.record(repository, FINALIZED_SHA, Instant.now()),
          repository);
      assertEquals(UNFINALIZED, row(repository).latest, repository);
    }
    assertEquals(1, asked(dark).size(), "the unanswered question was asked once");
    assertTrue(
        gitHost.asked.stream().noneMatch(question -> question.contains(unaddressed)),
        "no catalog id, nothing to ask");
  }

  /** A forward move, and the same version again, never ask the git host anything. */
  @Test
  void aForwardMoveAsksNoContainment() {
    String member = "qits-moving-forward" + run;
    scanned(member);
    bothReleasesCut(member);
    store.recordGitlinkLatest(member, FINALIZED, GitlinkSha.of(FINALIZED_SHA), Instant.now());

    assertEquals(
        MainHeadLatest.Outcome.MOVED, mainHead.record(member, UNFINALIZED_SHA, Instant.now()));
    assertEquals(
        MainHeadLatest.Outcome.UNCHANGED, mainHead.record(member, UNFINALIZED_SHA, Instant.now()));

    assertEquals(UNFINALIZED, row(member).latest);
    assertTrue(asked(member).isEmpty(), asked(member).toString());
  }

  /** A row already at main's release is a read and nothing else: no write, no re-plan. */
  @Test
  void aRowAlreadyAtMainsReleaseIsLeftAndReplansNothing() {
    String member = "qits-current-member" + run;
    scanned(member);
    bothReleasesCut(member);
    gitHost.heads.put(member + " main", TreeLookup.found(FINALIZED_SHA, List.of()));

    assertEquals(
        MainHeadLatest.Outcome.MOVED, mainHead.record(member, FINALIZED_SHA, Instant.now()));
    assertEquals(
        MainHeadLatest.Outcome.UNCHANGED, mainHead.record(member, FINALIZED_SHA, Instant.now()));
    mainHead.reconcile();

    assertEquals(List.of("gitlink:" + member), upstream.moved.stream()
        .filter(moved -> moved.endsWith(member)).toList());
    assertEquals(FINALIZED, row(member).latest);
  }

  /**
   * A head no release was cut from — a direct push to main — leaves the row as it was: a version is
   * never invented for a commit. A head the git host will not answer costs that repository and is
   * the next scan's.
   */
  @Test
  void aHeadThatCarriesNoKnownReleaseOrCannotBeReadLeavesTheRowAlone() {
    String pushed = "qits-pushed-to-main" + run;
    String dark = "qits-dark-host" + run;
    for (String repository : List.of(pushed, dark)) {
      scanned(repository);
      bothReleasesCut(repository);
      store.recordGitlinkLatest(
          repository, FINALIZED, GitlinkSha.of(FINALIZED_SHA), Instant.now());
    }
    gitHost.heads.put(
        pushed + " main", TreeLookup.found("dddddddddddddddddddddddddddddddddddddddd", List.of()));
    gitHost.heads.put(dark + " main", TreeLookup.unreachable("the git host is not there"));

    mainHead.reconcile();

    assertEquals(FINALIZED, row(pushed).latest);
    assertEquals(FINALIZED_SHA, GitlinkSha.read(row(pushed).sourceUrl).orElseThrow());
    assertEquals(FINALIZED, row(dark).latest);
    assertTrue(upstream.released.stream().noneMatch(name -> name.equals(pushed) || name.equals(dark)));
  }

  /** Two tags on one commit name the higher of them, and the head is matched abbreviation-tolerant. */
  @Test
  void twoTagsOnTheHeadNameTheHigherRelease() {
    String member = "qits-retagged" + run;
    store.recordRelease(
        member, "2026.1014.1", FINALIZED_SHA, Instant.parse("2026-10-14T00:00:01Z"), List.of());
    store.recordRelease(
        member, "2026.1014.2", FINALIZED_SHA, Instant.parse("2026-10-14T00:00:02Z"), List.of());

    assertEquals("2026.1014.2", mainHead.releaseAt(member, FINALIZED_SHA).orElseThrow().version);
    assertEquals(
        "2026.1014.2", mainHead.releaseAt(member, FINALIZED_SHA.substring(0, 12)).orElseThrow().version);
    assertTrue(mainHead.releaseAt(member, UNFINALIZED_SHA).isEmpty());
  }
}
