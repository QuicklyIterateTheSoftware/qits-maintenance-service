package eu.wohlben.qits.maintenance.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.bump.BumpDispatcher;
import eu.wohlben.qits.maintenance.bump.BumpService;
import eu.wohlben.qits.maintenance.config.MaintenanceConfig;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.model.BumpStatus;
import eu.wohlben.qits.maintenance.model.BumpTrigger;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.scan.ScanService;
import eu.wohlben.qits.maintenance.scan.ScanTrigger;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The clock's WRITING half: the nightly internal bump.
 *
 * <p>It used to be the tail of a SCHEDULED scan and is now its own cron, which is what these tests
 * are really pinning — <b>the selection is made against the inventory, not against whatever a scan
 * happened to have just read.</b> Every method here fills the store with a manual scan and then fires
 * the schedule by hand: the suite's scheduler is off (see {@code src/test/resources/
 * application.properties}), so nothing else can start this and a test that waited for 02:00 would be
 * indistinguishable from a test that hung.
 *
 * <p><b>The cron opens a window and {@code BumpDispatcher} sends</b>, so every method here drives
 * both halves through {@link #night()}. What the two of them decide TOGETHER is what these tests
 * are about — which groups get a bump — and splitting the assertions across the seam would leave
 * the interesting answer untested on either side of it.
 *
 * <p><b>Only the row is asserted, never the CI trigger.</b> What the schedule decides is WHICH groups
 * get a bump; what a bump then does with qits-ci is {@code MaintenanceApiTest}'s subject, and
 * asserting it twice would make one change fail in two places for one reason.
 */
@QuarkusTest
class BumpScheduleTest {

  @Inject BumpSchedule schedule;

  @Inject ScanService scans;

  @Inject BumpService bumps;

  @Inject BumpDispatcher dispatcher;

  @Inject MaintenanceStore store;

  @Inject FakePeers peers;

  @Inject InventoryReset inventory;

  @Inject WorkQueue queue;

  @Inject MaintenanceConfig config;

  /** The real bean behind the injected proxy, so {@link #restoreTheConfig} can put it back. */
  private MaintenanceConfig realConfig;

  @BeforeEach
  void scriptThePeers() {
    realConfig = ClientProxy.unwrap(config);
    queue.awaitIdle(Duration.ofSeconds(30));
    inventory.clear();
    peers.reset();
    Fixture.scriptScan(peers);
    Fixture.scriptBranchAbsent(peers);
    Fixture.scriptCiAccepts(peers, "run-scheduled");
    Fixture.scriptCiQueueEmpty(peers);
    Fixture.scriptReleaseRequestAccepted(peers, "rr-scheduled");
  }

  /**
   * A mock installed on {@link MaintenanceConfig} lives for the whole RUN, not the method — so the
   * gate test below would silence every class that ran after it, and the symptom would be a
   * schedule that mysteriously stopped asking for anything. The real bean is captured before each
   * method and put back after it, unconditionally.
   */
  @AfterEach
  void restoreTheConfig() {
    queue.awaitIdle(Duration.ofSeconds(30));
    QuarkusMock.installMockForType(realConfig, MaintenanceConfig.class);
  }

  /** Fills the inventory the way a scheduled scan would have, and waits for it. */
  private void scan() {
    scans.request(ScanScope.ALL, null, ScanTrigger.MANUAL);
    queue.awaitIdle(Duration.ofSeconds(60));
  }

  /**
   * A whole night: the cron opens the window and the dispatcher ticks until it stops asking.
   *
   * <p><b>The cron no longer sends anything</b>, so a test that called it alone would assert
   * against a window rather than against a bump. The loop is bounded — the fixture holds one
   * repository, so a second tick is already the "nothing is owed" ending — and a dispatcher that
   * kept finding work would fail here rather than spin.
   */
  private void night() {
    schedule.onInternalSchedule();
    for (int tick = 0; tick < 5 && !dispatcher.tick().isEmpty(); tick++) {
      queue.awaitIdle(Duration.ofSeconds(30));
    }
    queue.awaitIdle(Duration.ofSeconds(30));
  }

  private List<MtBump> bumpsOf(String group) {
    return store.bumps(Fixture.REPOSITORY, 50).stream()
        .filter(bump -> group.equals(bump.groupName))
        .toList();
  }

  @Test
  void theNightlyBumpAsksForTheInternalHalfAndForNothingElse() {
    scan();

    night();

    // The INTERNAL half, and it is the clock's: SCHEDULED, not MANUAL.
    List<MtBump> internal = bumpsOf("dependencies");
    assertEquals(1, internal.size(), "the internal group has five pending changes and no bump");
    assertEquals(BumpTrigger.SCHEDULED.name(), internal.getFirst().trigger);

    // And nothing else was asked for. `external` has the quarkus BOM pending and `angular` has
    // @angular/core — both are somebody else's release and both are a person's decision.
    assertTrue(bumpsOf("external").isEmpty(), "the external half is manual-only");
    assertTrue(
        bumpsOf("angular").isEmpty(),
        "a group the repository configured for itself is a person's press, not the clock's");
  }

  /** ONE BRANCH, ONE WRITER — and the clock is the caller most likely to collide with itself. */
  @Test
  void aGroupThatIsAlreadyBeingBumpedIsNotAskedAgain() {
    scan();
    bumps.request(Fixture.REPOSITORY, "dependencies", BumpTrigger.MANUAL);

    night();

    List<MtBump> internal = bumpsOf("dependencies");
    assertEquals(1, internal.size(), "a group with an active bump must not get a second one");
    assertEquals(
        BumpTrigger.MANUAL.name(),
        internal.getFirst().trigger,
        "and the one that survives is the one that was already going");
  }

  /**
   * A repository the git host could not be read for keeps the pins it had, and those pins are
   * yesterday's. Bumping against them would push a branch for versions that may not be pending at
   * all any more.
   */
  @Test
  void aRepositoryThatIsNotOkIsSkipped() {
    scan();
    store.markRepository(
        Fixture.REPOSITORY,
        Fixture.PROJECT,
        null,
        null,
        RepositoryStatus.UNREACHABLE,
        "the git host said nothing",
        Instant.now());

    night();

    assertTrue(bumpsOf("dependencies").isEmpty(), "only an OK repository is bumped by the clock");
  }

  /**
   * <b>THE GHOST TEST, AND IT IS THE ONE THIS SCHEDULE WAS TAUGHT BY.</b> The first live run asked
   * for 30 bumps and 23 came back FAILED with {@code no run recorded for MaintenanceBump} — every
   * one of them a pre-rename name whose row a scan had upserted months ago and nothing had ever
   * reconciled away. This filter was already correct; what was wrong was that a repository the
   * catalog had dropped still said OK.
   *
   * <p>So this drives the real seam rather than writing an ABSENT row by hand: the catalog stops
   * listing the repository, a full scan reconciles it, and the clock finds nothing to ask for.
   */
  @Test
  void aRepositoryTheCatalogDroppedIsNeverBumped() {
    scan();

    // The catalog now names somebody else entirely — a rename, which is what the live outage was.
    peers.answer(
        eu.wohlben.qits.maintenance.peer.PeerTarget.PROJECTS,
        eu.wohlben.qits.maintenance.catalog.CatalogReader.PATH,
        FakePeers.Scripted.ok(
            "{\"repositories\":[{\"id\":\"r9\",\"projectId\":\"" + Fixture.PROJECT
                + "\",\"name\":\"qits-ci-service\",\"mainBranch\":\"main\"}]}"));
    scan();

    night();

    assertEquals(
        RepositoryStatus.ABSENT.name(),
        store.repository(Fixture.REPOSITORY).orElseThrow().status,
        "the scan dropped it");
    assertTrue(
        bumpsOf("dependencies").isEmpty(),
        "a repository the catalog no longer lists has nothing the clock may bump");
  }

  /**
   * <b>THE SECOND NIGHT, AND THE RELEASE HAS NOT LANDED.</b> The first night pushed the branch and
   * opened a release request; the pins on main are untouched until that lands and a scan re-reads
   * them, so the inventory still says the group is owed exactly these changes. A cron that asked
   * again would push the same branch a second time and burn a run that can only find nothing.
   *
   * <p>Held is what stops it, and the window expiring is the only backstop it needs: nothing here
   * unwinds, because the release landing removes the repository from the candidate list on its own.
   */
  @Test
  void aBranchStillWaitingOnItsReleaseIsNotAskedForAgainTheNextNight() {
    scan();
    night();
    MtBump first = bumpsOf("dependencies").getFirst();
    store.bumpFinished(
        first.id, BumpStatus.SUCCEEDED, "SUCCESS", "pushed, release open", Instant.now());

    night();

    assertEquals(
        1,
        bumpsOf("dependencies").size(),
        "the changes have already been asked for and are waiting on a release");
  }

  /** A group with nothing pending is not a bump that finds nothing — it is no bump at all. */
  @Test
  void aGroupWithNothingPendingIsNotAskedFor() {
    scan();
    // Wipe the latest versions: every pin is then at the newest this service knows of.
    inventory.clearLatest();

    night();

    assertTrue(bumpsOf("dependencies").isEmpty(), "nothing is pending, so nothing is asked for");
  }

  /**
   * THE GATE THE LIVE DEPLOYMENT HOLDS DOWN. {@code bump.internal.auto} is the clock's switch and the
   * jar ships it true; the platform keeps it false until the pre-split branches are drained. It must
   * stop the schedule and leave the button alone, which is why the second half of this asserts that a
   * person can still ask.
   */
  @Test
  void theGateStopsTheClockAndLeavesTheButtonAlone() {
    scan();
    QuarkusMock.installMockForType(
        new MaintenanceConfig() {
          @Override
          public boolean bumpInternalAuto() {
            return false;
          }

          @Override
          public boolean bumpDispatchGated() {
            // The gate under test is the clock's, not the dispatch mode's: this must hold for the
            // shipped path, so the mock says what the jar says.
            return true;
          }

          @Override
          public boolean bumpEnabled() {
            return true;
          }

          @Override
          public String environment() {
            // The one other method a bump request reads, and mt_bump.environment is not null.
            return "dev";
          }
        },
        MaintenanceConfig.class);

    night();
    assertTrue(bumpsOf("dependencies").isEmpty(), "the gate is off, so the clock asked for nothing");

    bumps.request(Fixture.REPOSITORY, "dependencies", BumpTrigger.MANUAL);
    assertEquals(
        1, bumpsOf("dependencies").size(), "the gate is about the schedule and never about a press");
  }
}
