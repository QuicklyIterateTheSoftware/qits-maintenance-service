package eu.wohlben.qits.maintenance.bus;

import static eu.wohlben.qits.maintenance.bus.ForeignEventContractTest.frame;
import static eu.wohlben.qits.maintenance.bus.ForeignEventContractTest.scmPublishCommitPayload;
import static eu.wohlben.qits.maintenance.bus.ForeignEventContractTest.scmReleasePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.manifest.GroupConfig;
import eu.wohlben.qits.maintenance.manifest.ParsedPin;
import eu.wohlben.qits.maintenance.model.BranchState;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.GroupSource;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>THE CLAIM-TRANSACTION SANDWICH — the shape that wedged this consumer twice.</b>
 *
 * <p>A durable frame is handed to a listener from INSIDE the eventstream datasource's claim
 * transaction: the library has already written the claim row and has not committed it, so that
 * datasource's connection is enlisted in a live JTA transaction when {@code onFrame} is entered.
 * Neither datasource here is XA, and Narayana admits exactly one last resource — so a store method
 * that reads on the {@code maintenance} datasource with that transaction still active tries to
 * enlist a second one and is refused. In production that surfaced as "failed to enlist / exception
 * in association of connection to existing transaction" out of the NEXT {@code DbRetry.inNewTx},
 * the frame was classified retryable, and the consumer offered the same frame for ever with its
 * watermark stuck behind it. Twice: 2026-09-02 through {@code repositoryName}, 2026-09-03 through
 * {@code groups}.
 *
 * <p><b>The other bus tests cannot see this and that is not a gap in them.</b> {@code
 * ScmEventListenerTest} and {@code SoftwareReleaseListenerTest} drive {@code onFrame} against a
 * store whose tables are maps, with no database, no transaction and nothing to enlist — which is
 * precisely why both stayed green through both outages. This class is the one that opens the claim
 * first.
 *
 * <p><b>The first test is deliberately about EVERY method rather than the two that wedged.</b> Spot-
 * auditing the store for listener-reachable reads failed twice; the doctrine is now uniform, and an
 * assertion that walks one path would go on passing the day somebody adds the forty-fifth method.
 */
@QuarkusTest
class ClaimTransactionTest {

  private static final String REPOSITORY = "qits-claim-tx";
  private static final String GROUP = "dependencies";
  private static final String BRANCH = "maintenance/" + GROUP;
  private static final String MAIN = "main";
  private static final String RELEASE_SHA = "0011223344556677889900aabbccddeeff001122";

  @Inject MaintenanceStore store;

  /** The peers, faked at the client — the git host has to answer the released tag. */
  @Inject FakePeers peers;

  @Inject ScmEventListener scm;

  @Inject SoftwareReleaseListener releases;

  @Inject ReleaseFinalizedListener finalizations;

  @Inject InventoryReset reset;

  /** The bus jar's own datasource — its outbox, its claims and its watermarks. */
  @Inject
  @DataSource("eventstream")
  AgroalDataSource eventstream;

  @BeforeEach
  void anInventoryWithOneGroup() {
    reset.clear();
    store.replaceInventory(
        REPOSITORY,
        "qits",
        UUID.randomUUID().toString(),
        null,
        "main",
        RepositoryStatus.OK,
        "sha1",
        null,
        List.of(ParsedPin.of(Ecosystem.MAVEN, "pom.xml", "g:a", "1.0.0", null, "dependency:g:a")),
        List.of(GroupConfig.Group.ofKind(GROUP, PinKind.INTERNAL)),
        GroupSource.DEFAULT,
        candidate -> PinKind.INTERNAL,
        Instant.now());
  }

  /**
   * A transaction with the eventstream datasource enlisted in it, which is what a claim is.
   *
   * <p>One borrowed connection and one statement is the whole simulation: enlistment happens when
   * Agroal hands a connection out inside an active transaction, and it is the enlistment — not the
   * claim row's contents — that the next datasource collides with.
   */
  private void insideAClaim(Runnable body) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              try (Connection claim = eventstream.getConnection();
                  Statement statement = claim.createStatement()) {
                statement.execute("select 1");
              } catch (SQLException unreachable) {
                throw new IllegalStateException("the claim datasource did not answer", unreachable);
              }
              body.run();
            });
  }

  @Test
  void everyStoreReadRunsInsideAClaimTransactionWithoutEnlistingThisDatasourceIntoIt() {
    UUID scanId = store.openScan(ScanScope.ALL, null, "MANUAL", Instant.now());
    UUID artifactId =
        store.upsertArtifact(
            Ecosystem.MAVEN, "eu.wohlben.qits:qits-ci", "1.0.0", REPOSITORY, Instant.now());

    insideAClaim(
        () -> {
          // Every read the store offers, in one claim, in the order they are declared. A bare one
          // anywhere in this list is the wedge.
          store.repositories();
          store.repository(REPOSITORY);
          store.repositoryName(REPOSITORY);
          store.pins(REPOSITORY);
          store.allPins();
          store.gitlinkPins(REPOSITORY);
          store.allGitlinkPins();
          store.gitlinkTree(REPOSITORY, "0000000000000000000000000000000000000000");
          store.groups(REPOSITORY);
          store.allLatest();
          store.latestOf(Ecosystem.GITLINK);
          store.latest(Ecosystem.MAVEN, "g:a");
          store.scanPending(REPOSITORY);
          store.scan(scanId);
          store.scans(20);
          store.branches(REPOSITORY);
          store.branch(REPOSITORY, GROUP);
          store.bump(UUID.randomUUID());
          store.bumps(REPOSITORY, 20);
          store.bumps(null, 20);
          store.activeBumps();
          store.lastDispatchedAt();
          store.pendingArtifacts();
          store.artifact(artifactId);
          store.artifact(Ecosystem.MAVEN, "eu.wohlben.qits:qits-ci", "1.0.0");
          store.dependents(Ecosystem.MAVEN, "g:a", true);
          store.newestArtifactPerName();
          store.artifactsOfRepository(REPOSITORY);
          store.artifactsOfRepository(List.of(REPOSITORY));
          store.components(artifactId);
          store.edges(artifactId);
          store.releasesOf(REPOSITORY);
          store.releaseRecorded(REPOSITORY, "1.0.0");
          store.releasePinCarriers(Ecosystem.GITLINK, REPOSITORY);
          store.adoptionFacts(Ecosystem.MAVEN, "g:a");
          store.publishedArtifacts(REPOSITORY);
          store.finalizedReleases(REPOSITORY);

          // AND THEN A WRITE, which is where the production failure actually surfaced: the bare
          // read enlists, and the inNewTx behind it is the one that dies.
          store.recordBranch(
              REPOSITORY, GROUP, BRANCH, BranchState.PUSHED, "sha-in-claim", Instant.now());
        });

    assertEquals(
        BranchState.PUSHED.name(),
        store.branch(REPOSITORY, GROUP).orElseThrow().state,
        "the write behind the reads must have committed");
  }

  /**
   * The same sandwich on the one arm {@code SCMRelease} still has.
   *
   * <p>The 2026-09-03 wedge came through {@code onMaintenanceBranchReleased -> group -> groups},
   * which is gone: a release names its request's fold, never a {@code maintenance/} branch, so that
   * arm was removed with the release door. What is left is the ledger half — and, since qits-1257,
   * the main-head gitlink latest on the push that finalizes it — and both are the same shape: a
   * store read ({@code repository}), a peer call, and a store WRITE behind them, all inside the
   * claim. The read is what enlists and the write is what dies, so these are the paths that have
   * to be pinned now.
   */
  @Test
  void aReleaseAndItsFinalizeMergeAreRecordedWhenTheFramesArriveInsideTheirClaims() {
    String version = "2026.903.1";
    // The tag, spelled the way GitHostReader asks for it: one path segment, slashes encoded.
    peers.answer(
        PeerTarget.GITHOST,
        "/git/qits/" + REPOSITORY + "/tree/refs%2Ftags%2F" + version,
        FakePeers.Scripted.ok("{\"entries\":[]}", Map.of("Git-Commit-Sha", RELEASE_SHA)));

    // And main, once the finalize merge has made the release's commit its head (qits-1257).
    peers.answer(
        PeerTarget.GITHOST,
        "/git/qits/" + REPOSITORY + "/tree/" + MAIN,
        FakePeers.Scripted.ok("{\"entries\":[]}", Map.of("Git-Commit-Sha", RELEASE_SHA)));

    insideAClaim(
        () -> scm.onFrame(frame("SCMRelease", scmReleasePayload(REPOSITORY, MAIN, version))));
    assertTrue(
        store.releaseRecorded(REPOSITORY, version),
        "the release ledger row is what must survive a release handled inside a claim");
    assertTrue(
        store.latest(Ecosystem.GITLINK, REPOSITORY).isEmpty(),
        "and a cut release moves no gitlink latest (qits-1257)");

    insideAClaim(
        () ->
            scm.onFrame(
                frame(
                    "SCMPublishCommit", scmPublishCommitPayload(REPOSITORY, MAIN, RELEASE_SHA))));
    assertEquals(
        version,
        store.latest(Ecosystem.GITLINK, REPOSITORY).orElseThrow().latest,
        "the main-head gitlink latest is what must survive a push handled inside a claim");
  }

  /**
   * <b>The finalization join, both halves inside their claims</b> (qits-1472). Each frame is a
   * store write in its own transaction — with an advisory lock and, for FINALIZED, a native insert —
   * reached from inside the eventstream datasource's claim, which is exactly the shape that wedged
   * this consumer twice. The announcement arrives first, adopts nothing; the finalization adopts it.
   */
  @Test
  void aReleaseAndItsFinalizationAreAdoptedWhenTheFramesArriveInsideTheirClaims() {
    String repoId = UUID.randomUUID().toString();
    String version = "2026.903.1";

    insideAClaim(
        () ->
            releases.onFrame(
                frame(
                    "SoftwareRelease",
                    ForeignEventContractTest.softwareReleasePayload(
                        repoId, "maven", "eu.wohlben.qits:qits-claim-tx", version))));
    assertTrue(
        store.latest(Ecosystem.MAVEN, "eu.wohlben.qits:qits-claim-tx").isEmpty(),
        "published is not usable");

    insideAClaim(
        () ->
            finalizations.onFrame(
                frame(
                    "ReleaseRequestStateChanged",
                    ForeignEventContractTest.releaseRequestStateChangedPayload(
                        repoId, "FINALIZED", version))));
    assertEquals(
        version,
        store.latest(Ecosystem.MAVEN, "eu.wohlben.qits:qits-claim-tx").orElseThrow().latest,
        "the adoption is what must survive a finalization handled inside a claim");
  }

  @Test
  void theClaimTransactionIsStillTheCallersAfterTheStoreHasRunInIt() {
    // inNewTx SUSPENDS the claim rather than joining or ending it, so the library still owns the
    // transaction it is about to commit the claim row in. A store that ended it would settle
    // frames nothing had handled.
    insideAClaim(
        () -> {
          store.groups(REPOSITORY);
          assertEquals(
              Status.STATUS_ACTIVE,
              QuarkusTransaction.getStatus(),
              "the claim transaction is still the caller's");
        });
  }
}
