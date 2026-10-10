package eu.wohlben.qits.maintenance.bump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.model.BumpStatus;
import eu.wohlben.qits.maintenance.model.BumpTrigger;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.scan.ScanService;
import eu.wohlben.qits.maintenance.scan.ScanTrigger;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A GROUP BUMP CARRIES THE CHANGELOGS IT PULLS IN, AND A MISSING ONE STOPS IT (qits-893, qits-1143).
 *
 * <p>The fixture's {@code qits-eventstream} pin moves {@code 2026.811.1 → 2026.821.3}. The rules
 * themselves are {@code ChangelogRangesTest}'s; what is pinned here is the wiring a unit test cannot
 * see — that the dispatch asks the docs store at the real address, that a missing changelog becomes
 * the bump row's sentence with no trigger sent, that an unreadable store leaves the bump REQUESTED,
 * and that a resolved range reaches qits-ci on the change it belongs to.
 */
@QuarkusTest
class BumpChangelogTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String EVENTSTREAM = "eu.wohlben.qits:qits-eventstream";

  @Inject BumpService bumps;

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
    Fixture.scriptCiAccepts(peers, "run-changelog");
    Fixture.scriptReleaseRequestAccepted(peers, "rr-changelog");
    Fixture.seedProducers(store);
    scans.request(ScanScope.ALL, null, ScanTrigger.MANUAL);
    queue.awaitIdle(Duration.ofSeconds(60));
  }

  private MtBump bump() {
    UUID id = bumps.request(Fixture.REPOSITORY, "dependencies", BumpTrigger.MANUAL);
    queue.awaitIdle(Duration.ofSeconds(30));
    return store.bump(id).orElseThrow();
  }

  private List<String> triggers() {
    return peers.bodiesFor(CiClient.TRIGGER_PATH);
  }

  /** The release between the pins is cut and its publish never completed: no commit without it. */
  @Test
  void aMissingChangelogFailsTheBumpAndTriggersNothing() {
    // The floor is 2026.811.1, so 2026.821.3 — the new pin — is a release that published nothing.
    Fixture.scriptChangelogs(peers, "qits-eventstream", "2026.811.1");

    MtBump done = bump();

    assertEquals(BumpStatus.FAILED.name(), done.status, done.message);
    assertTrue(
        done.message.contains(
            "no changelog for qits-eventstream 2026.821.3 (@changelog/qits-eventstream): every"
                + " release publishes one, so this release's publish did not complete"),
        done.message);
    assertTrue(triggers().isEmpty(), "nothing is sent to qits-ci: " + triggers());
    assertTrue(
        peers.called(PeerTarget.ARTIFACTS_DOCS, Fixture.CHANGELOG_PATH + "qits-eventstream"));
  }

  /** An unreadable docs store says nothing about the changelogs: the bump waits, it does not fail. */
  @Test
  void anUnreadableDocsStoreLeavesTheBumpRequested() {
    peers.answer(
        PeerTarget.ARTIFACTS_DOCS,
        Fixture.CHANGELOG_PATH + "qits-eventstream",
        FakePeers.Scripted.status(503, "busy"));

    MtBump done = bump();

    assertEquals(BumpStatus.REQUESTED.name(), done.status, done.message);
    assertEquals(BumpService.CHANGELOGS_UNREADABLE, done.message);
    assertTrue(triggers().isEmpty());
  }

  /**
   * The range rides the change it belongs to — including a release the store published that this
   * service's ledger never heard of — and every other change carries no {@code changelog} key.
   */
  @Test
  void aResolvedRangeRidesItsChangeInTheTriggerPayload() throws Exception {
    Fixture.scriptChangelogs(peers, "qits-eventstream", "2026.821.3", "2026.811.1", "2026.815.1");

    MtBump done = bump();

    assertEquals(BumpStatus.RUNNING.name(), done.status, done.message);
    assertEquals(1, triggers().size());
    JsonNode changes = JSON.readTree(triggers().get(0)).path("payload").path("changes");
    assertTrue(changes.size() > 1, "the fixture's group bumps more than one pin: " + changes);
    boolean seen = false;
    for (JsonNode change : changes) {
      if (EVENTSTREAM.equals(change.path("name").asText())) {
        seen = true;
        assertEquals(
            JSON.readTree(
                "{\"repository\":\"qits-eventstream\",\"versions\":[\"2026.815.1\",\"2026.821.3\"]}"),
            change.get("changelog"));
      } else {
        assertFalse(change.has("changelog"), "no range, no key: " + change);
      }
    }
    assertTrue(seen, "the eventstream change was sent: " + changes);
  }
}
