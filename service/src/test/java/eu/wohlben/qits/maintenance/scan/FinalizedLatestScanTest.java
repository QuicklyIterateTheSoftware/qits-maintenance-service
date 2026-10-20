package eu.wohlben.qits.maintenance.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.model.ScanStatus;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>THE REGISTRY POLL IS THE SECOND DOOR, and it is gated like the bus</b> (qits-1472).
 *
 * <p>Every INTERNAL scan ends in {@code refreshLatest}, which asked qits-artifacts for the highest
 * PUBLISHED version and wrote it — the 00:30 cron, a manual scan, the main-push scan and the tag
 * scan every {@code SCMRelease} queues. A publish is in the registry long before its request
 * finalizes, so gating the listener alone left the poll to adopt the same unfinalized version a few
 * minutes later. {@link Fixture#scriptScan}'s registry lists {@code qits-eventstream} at {@code
 * 2026.811.1} and {@code 2026.821.3}; here only the first has finalized.
 */
@QuarkusTest
class FinalizedLatestScanTest {

  private static final String EVENTSTREAM = "eu.wohlben.qits:qits-eventstream";

  /** Announced by nothing: no repository to gate it on. */
  private static final String PARENT = "eu.wohlben.qits:qits-parent";

  private static final String REPO_ID = "r-eventstream-javalib";

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
  }

  private void scan() {
    UUID id = scans.request(ScanScope.ALL, Fixture.REPOSITORY, ScanTrigger.EVENT);
    queue.awaitIdle(Duration.ofSeconds(60));
    assertEquals(ScanStatus.SUCCEEDED.name(), store.scan(id).orElseThrow().status);
  }

  private String latest(String name) {
    return store.latest(Ecosystem.MAVEN, name).orElseThrow().latest;
  }

  @Test
  void aScanWhoseRegistryHoldsAnUnfinalizedVersionDoesNotMoveTheLatestToIt() {
    // 2026.821.3 is published — announced, its request RELEASED — and only 2026.811.1 finalized.
    store.recordPublished(
        REPO_ID, Ecosystem.MAVEN, EVENTSTREAM, "2026.821.3", "event:sr", Instant.now(), Instant.now());
    store.adoptFinalized(REPO_ID, "2026.811.1", "event:fin", Instant.now(), Instant.now());

    scan();

    assertEquals(
        "2026.811.1",
        latest(EVENTSTREAM),
        "the highest registry version its repository finalized, never the unfinalized one above");
    assertEquals(
        "2026.820.1",
        latest(PARENT),
        "a package nothing ever announced has no repository to gate on and is answered as before");
  }

  @Test
  void onceItFinalizesTheScanAgreesWithTheAdoption() {
    store.recordPublished(
        REPO_ID, Ecosystem.MAVEN, EVENTSTREAM, "2026.821.3", "event:sr", Instant.now(), Instant.now());
    store.adoptFinalized(REPO_ID, "2026.821.3", "event:fin", Instant.now(), Instant.now());
    assertEquals("2026.821.3", latest(EVENTSTREAM), "the finalization adopted it on the bus");

    scan();

    assertEquals("2026.821.3", latest(EVENTSTREAM), "and the poll does not take it back");
  }
}
