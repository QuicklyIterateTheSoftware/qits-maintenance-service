package eu.wohlben.qits.maintenance.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.bump.BumpDispatcher;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * THE ONE THING THAT STILL SAYS "NOT NOW", AND IT SAYS IT BY NAME.
 *
 * <p>Dispatch is armed by debt, so the hour does not decide whether the estate's own releases
 * travel. What an hour may still decide is whether a release request full of bumps is welcome to
 * arrive in somebody's working afternoon, and {@code qits.maintenance.bump.dispatch.quiet-hours} is
 * where that is written down. Since qits-1133 R5 there is no window and no door to override it.
 *
 * <p><b>The quiet range is computed around the moment the suite starts</b>, an hour either side of
 * it in UTC. A fixed range would be a test that passes twenty-two hours a day.
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
    Fixture.scriptCiQueueEmpty(peers);
    peers.answer(
        PeerTarget.PROJECTS,
        Fixture.RELEASE_REQUESTS_PATH,
        FakePeers.Scripted.ok(
            "{\"requests\":[],\"request\":{\"id\":\"8e1f0c3a-2b4d-4e6f-8a9b-0c1d2e3f4a5b\","
                + "\"state\":\"PENDING\"}}"));
    scans.request(ScanScope.ALL, null, ScanTrigger.MANUAL);
    queue.awaitIdle(Duration.ofSeconds(60));
  }

  @AfterEach
  void drain() {
    queue.awaitIdle(Duration.ofSeconds(30));
  }

  /**
   * Everything the dispatch needs is true — owed work, an idle queue, no open request — and the hour
   * is the one thing saying no. <b>It says so as its own outcome</b> rather than as silence.
   */
  @Test
  void aQuietHourOpensNothingAndSaysWhy() {
    BumpDispatcher.Decision decision = dispatcher.explain(Instant.now());
    assertEquals("QUIET_HOURS", decision.outcome());
    assertEquals(1, decision.owed(), "the work is owed and reported, it is simply not sent");
    assertTrue(decision.summary().contains("quiet-hours"), decision.summary());

    assertTrue(dispatcher.tick().isEmpty());
    assertTrue(
        peers.bodiesFor(Fixture.RELEASE_REQUESTS_PATH).stream()
            .allMatch(java.util.Objects::isNull),
        "no release request was opened");
    assertTrue(store.lastDispatchedAt().isEmpty());
  }
}
