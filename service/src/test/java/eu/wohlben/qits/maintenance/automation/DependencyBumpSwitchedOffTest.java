package eu.wohlben.qits.maintenance.automation;

import static eu.wohlben.qits.maintenance.automation.AutomationFixture.FOLD_A;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.REQUEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.model.BumpStatus;
import eu.wohlben.qits.maintenance.model.BumpTrigger;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.scan.ScanService;
import eu.wohlben.qits.maintenance.scan.ScanTrigger;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The {@code dependency-bump} switch in its emergency position</b> (qits-1133). It ships ON since
 * the R2 cutover; this class is the one launch that turns it off, to pin what off means.
 */
@QuarkusTest
@TestProfile(DependencyBumpSwitchedOffTest.DependencyBumpOff.class)
class DependencyBumpSwitchedOffTest {

  /** The {@code dependency-bump} switch off; nothing else changed. */
  public static class DependencyBumpOff implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(DependencyBumpAutomation.SWITCH, "false");
    }
  }

  @Inject AutomationService automations;

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
    Fixture.scriptCiAccepts(peers, "run-switched-off");
    AutomationFixture.scriptFold(peers, FOLD_A, false);
    AutomationFixture.scriptRequest(peers, REQUEST, "PENDING", FOLD_A);
    scans.request(ScanScope.ALL, null, ScanTrigger.MANUAL);
    queue.awaitIdle(Duration.ofSeconds(60));
  }

  private int triggers() {
    return peers.bodiesFor(CiClient.TRIGGER_PATH).size();
  }

  /**
   * A dependency-bump row opened before the switch went off (qits-1133) is not run: it ends FRESH
   * (NOTHING_TO_DO), so it holds no request, and nothing is sent to qits-ci.
   */
  @Test
  void aRowOfASwitchedOffKindEndsFreshWithoutARun() {
    UUID id =
        store.openAutomation(
            new MaintenanceStore.AutomationOpening(
                Fixture.REPOSITORY,
                DependencyBumpAutomation.KIND,
                REQUEST,
                FOLD_A,
                null,
                false,
                "maintenance/automations/dependency-bump/" + REQUEST,
                null,
                "test",
                BumpTrigger.FOLD,
                List.of(),
                java.util.Map.of(),
                BumpStatus.REQUESTED,
                null),
            false,
            java.time.Instant.now());

    automations.dispatch(id);

    MtBump row = store.bump(id).orElseThrow();
    assertEquals(BumpStatus.NOTHING_TO_DO.name(), row.status, row.message);
    assertTrue(row.message.contains("switched off"), row.message);
    assertEquals(0, triggers(), "and no run was asked for");
  }

  /** Off, the kind is not listed at all — a fold with no browser tests lists nothing. */
  @Test
  void aSwitchedOffKindIsNotListed() {
    var answer =
        automations.trigger(
            REQUEST,
            new AutomationService.Fold(
                Fixture.REPOSITORY, FOLD_A, null, null, List.of("main", "work"), null));
    queue.awaitIdle(Duration.ofSeconds(30));

    assertEquals(0, answer.automations().size(), answer.toString());
    assertEquals(0, triggers());
  }
}
