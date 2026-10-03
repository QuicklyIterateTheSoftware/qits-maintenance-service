package eu.wohlben.qits.maintenance.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.bump.BumpDispatcher;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.scan.ScanService;
import eu.wohlben.qits.maintenance.scan.ScanTrigger;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * THE ONE THING THAT STILL SAYS "NOT NOW", AND IT SAYS IT BY NAME.
 *
 * <p>Dispatch arms itself on debt, so the hour no longer decides whether the estate's own releases
 * travel. What an hour may still decide is whether a {@code maintenance/dependencies} branch is
 * welcome to arrive in somebody's working afternoon, and {@code
 * qits.maintenance.bump.dispatch.quiet-hours} is where that is written down — rather than being an
 * implication of a cron, which is how it came to stop everything.
 *
 * <p><b>The quiet range is computed around the moment the suite starts</b>, an hour either side of
 * it in UTC. A fixed range would be a test that passes twenty-two hours a day, which is the kind of
 * clock dependence the suite turns the scheduler off to avoid.
 */
@QuarkusTest
@TestProfile(BumpQuietHoursTest.RightNowIsQuiet.class)
class BumpQuietHoursTest {

  /** Quiet from an hour ago to an hour from now, so every method in this class runs inside it. */
  public static class RightNowIsQuiet implements io.quarkus.test.junit.QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
      DateTimeFormatter hhmm = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC);
      Instant now = Instant.now();
      return Map.of(
          "qits.maintenance.bump.dispatch.quiet-hours",
          hhmm.format(now.minus(Duration.ofHours(1))) + "-" + hhmm.format(now.plus(Duration.ofHours(1))),
          "qits.maintenance.bump.dispatch.release-state-ttl",
          "0");
    }
  }

  @Inject BumpDispatcher dispatcher;

  @Inject ScanService scans;

  @Inject MaintenanceStore store;

  @Inject FakePeers peers;

  @Inject InventoryReset inventory;

  @Inject WorkQueue queue;

  @BeforeEach
  void scriptThePeers() {
    queue.awaitIdle(Duration.ofSeconds(30));
    inventory.clear();
    peers.reset();
    Fixture.scriptScan(peers);
    Fixture.scriptBranchAbsent(peers);
    Fixture.scriptCiAccepts(peers, "run-quiet");
    Fixture.scriptReleaseRequestAccepted(peers, "rr-quiet");
    Fixture.scriptCiQueueEmpty(peers);
    scans.request(ScanScope.ALL, null, ScanTrigger.MANUAL);
    queue.awaitIdle(Duration.ofSeconds(60));
    dispatcher.close("a fresh test");
  }

  private boolean bumped() {
    queue.awaitIdle(Duration.ofSeconds(30));
    return !store.bumps(Fixture.REPOSITORY, 50).isEmpty();
  }

  /**
   * Everything the dispatch needs is true — owed work, an idle queue, nothing in flight — and the
   * hour is the one thing saying no. <b>It says so as its own outcome</b> rather than as silence:
   * the whole failure this replaces was a gate that declined and reported nothing.
   */
  @Test
  void aQuietHourDispatchesNothingAndSaysWhy() {
    BumpDispatcher.Decision decision = dispatcher.explain(Instant.now());
    assertEquals("QUIET_HOURS", decision.outcome());
    assertEquals(1, decision.owed(), "the work is owed and reported, it is simply not sent");
    assertEquals(1, decision.queue().size());

    assertTrue(dispatcher.tick().isEmpty());
    assertFalse(bumped());
    assertTrue(store.bumpWindow().isEmpty(), "and no window was opened to be honoured later");
  }

  /**
   * <b>And a person can still say "now".</b> {@code POST /bumps/window} is not quiet-hours gated,
   * because pressing it is the statement the quiet hours exist to be an exception to. The tick that
   * follows finds a window already open and never reaches the hour at all.
   */
  @Test
  void theDoorOverridesTheQuietHour() {
    dispatcher.open(Instant.now());

    assertTrue(!dispatcher.tick().isEmpty(), "the window was opened by hand and it is honoured");
    assertTrue(bumped());
  }
}
