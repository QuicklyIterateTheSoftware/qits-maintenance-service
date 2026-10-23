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
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * THE CAP ITSELF CAN REFUSE EVERYTHING: {@code qits.maintenance.bump.ci-active-cap=0} answers
 * {@code NO_SLOTS} even with an idle queue, because {@link BumpDispatcher} is configured to let no
 * run reach qits-ci at all — a platform's emergency brake, now that qits-ci reports no runner of
 * its own to be disconnected or quarantined (epic qits-1481; qits-882's {@code NO_SLOTS} used to
 * mean "no runner is connected").
 *
 * <p>A separate {@code @QuarkusTest} because the cap is an {@code @ConfigProperty} read once at
 * boot: {@code BumpDispatchTest} runs with the shipped default (4) throughout, and overriding it
 * mid-suite is not how MicroProfile config works.
 */
@QuarkusTest
@TestProfile(BumpDispatchCapZeroTest.NoCiCapAtAll.class)
class BumpDispatchCapZeroTest {

  public static class NoCiCapAtAll implements io.quarkus.test.junit.QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.maintenance.bump.ci-active-cap", "0",
          "qits.maintenance.bump.dispatch.release-state-ttl", "0");
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
   * Everything else the dispatch needs is true — owed work, a readable and idle queue, no open
   * request — and the cap is the one thing saying no, by name, not as a busy queue would.
   */
  @Test
  void aZeroCapOpensNothingAndSaysSo() {
    BumpDispatcher.Decision decision = dispatcher.explain(Instant.now());
    assertEquals("NO_SLOTS", decision.outcome());
    assertEquals(1, decision.owed(), "the work is owed and reported, it is simply never sent");
    assertTrue(decision.summary().contains("ci-active-cap"), decision.summary());

    assertTrue(dispatcher.tick().isEmpty());
    assertTrue(
        peers.bodiesFor(Fixture.RELEASE_REQUESTS_PATH).stream().allMatch(java.util.Objects::isNull),
        "no release request was opened");
    assertTrue(store.lastDispatchedAt().isEmpty());
  }
}
