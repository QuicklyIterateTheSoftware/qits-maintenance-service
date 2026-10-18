package eu.wohlben.qits.maintenance.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.catalog.CatalogReader;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.model.ScanStatus;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>THE INVENTORY FOLLOWS THE CATALOG OUT, NOT ONLY IN.</b>
 *
 * <p>A scan used to only ever UPSERT what the catalog listed, so a repository that was renamed or
 * removed kept the status its last successful scan wrote — OK — along with its pins, its groups and
 * therefore its pending count, for ever: nothing else writes those rows. What it cost was measured
 * on the first live nightly bump, 2026-09-03. Thirty bumps asked for, twenty-three FAILED with
 * {@code no run recorded for MaintenanceBump}, every one of them against a pre-rename ghost —
 * qits-spa-artifacts, qits-stt, qits-projects, qits-platform-spa-*. The catalog held 48
 * repositories; this inventory held 96 and roughly 800 pending changes against lines that no longer
 * exist anywhere.
 *
 * <p>So the subject of this class is the reconciliation and — at least as much — <b>the three
 * occasions on which it must NOT happen</b>, because reconciling against a listing that is not the
 * whole catalog marks the whole platform absent in one transaction.
 *
 * <p><b>The ghost here is named after a real one.</b> {@code qits-spa-artifacts} is one of the
 * twenty-three, which is worth more in a failure message than {@code repo-2}.
 */
@QuarkusTest
class ScanReconciliationTest {

  /** A real pre-rename name from the 2026-09-03 outage. */
  private static final String GHOST = "qits-spa-artifacts";

  private static final String GHOST_SHA = "c0ffee11223344556677889900aabbccddeeff01";

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

  // --- what reconciliation does ----------------------------------------------------------------

  @Test
  void aFullScanMarksEveryRowTheCatalogNoLongerListsAbsentAndDropsItsPins() {
    seedTheGhost("2026.811.1");
    assertEquals(RepositoryStatus.OK.name(), row(GHOST).status, "the ghost was scanned in first");

    // The rename happened: the catalog lists the other repository and no longer this one.
    catalogListing(Fixture.REPOSITORY);
    fullScan(ScanStatus.SUCCEEDED);

    MtRepository ghost = row(GHOST);
    assertEquals(RepositoryStatus.ABSENT.name(), ghost.status);
    assertEquals(ScanService.DROPPED_FROM_THE_CATALOG, ghost.message);
    assertTrue(store.pins(GHOST).isEmpty(), "a repository the catalog dropped pins nothing");
    assertTrue(store.groups(GHOST).isEmpty(), "and has no group for the clock to bump");
    // THE ROW IS KEPT. The name answers honestly instead of 404ing, and catalog_id — the
    // translation the graph reads another context's spelling through — survives with it.
    assertEquals("r-" + GHOST, ghost.catalogId);

    // And the repository that IS listed is untouched by any of it.
    assertEquals(RepositoryStatus.OK.name(), row(Fixture.REPOSITORY).status);
    assertTrue(store.pins(Fixture.REPOSITORY).size() > 0);
  }

  /** The other direction, and it needs nothing of its own — the ordinary upsert path is the fix. */
  @Test
  void aRepositoryThatReturnsToTheCatalogIsScannedBackToOkWithFreshPins() {
    seedTheGhost("2026.811.1");
    catalogListing(Fixture.REPOSITORY);
    fullScan(ScanStatus.SUCCEEDED);
    assertEquals(RepositoryStatus.ABSENT.name(), row(GHOST).status);

    // It is back in the catalog, and its pom has moved on since.
    scriptTheGhostsManifests("2026.821.3");
    catalogListing(Fixture.REPOSITORY, GHOST);
    fullScan(ScanStatus.SUCCEEDED);

    MtRepository ghost = row(GHOST);
    assertEquals(RepositoryStatus.OK.name(), ghost.status);
    assertEquals(GHOST_SHA, ghost.headSha);
    assertEquals(
        java.util.List.of("2026.821.3"),
        store.pins(GHOST).stream().map(pin -> pin.version).toList(),
        "the pins are the ones this scan read, not the ones it was dropped with");
    assertTrue(store.groups(GHOST).size() > 0, "and the groups are back too");
  }

  // --- the three occasions on which it must not happen -----------------------------------------

  /**
   * <b>A SCAN OF ONE REPOSITORY IS A LISTING OF ONE.</b> It says nothing about the other
   * forty-seven, and this is not a corner: {@code ScanTrigger.EVENT} queues exactly this scan on
   * every push to a main branch, several times an hour. One that reconciled would empty the
   * inventory on somebody's merge.
   */
  @Test
  void aScanOfOneRepositoryReconcilesNothing() {
    seedTheGhost("2026.811.1");
    catalogListing(Fixture.REPOSITORY);

    UUID id = scans.request(ScanScope.ALL, Fixture.REPOSITORY, ScanTrigger.EVENT);
    queue.awaitIdle(Duration.ofSeconds(60));
    assertEquals(ScanStatus.SUCCEEDED.name(), store.scan(id).orElseThrow().status);

    assertEquals(
        RepositoryStatus.OK.name(),
        row(GHOST).status,
        "a scan of one repository is not evidence about any other");
    assertEquals(1, store.pins(GHOST).size(), "and it wipes nobody's pins");
  }

  /**
   * <b>A CATALOG THAT COULD NOT BE READ SAYS NOTHING ABOUT ANY REPOSITORY.</b> The failed read
   * carries an empty entry list, and reconciling against it would mark every row on the platform
   * absent and wipe every pin — turning one peer's restart into an inventory that has to be rebuilt.
   */
  @Test
  void aCatalogReadThatFailedReconcilesNothing() {
    seedTheGhost("2026.811.1");
    peers.answer(
        PeerTarget.PROJECTS,
        CatalogReader.PATH,
        FakePeers.Scripted.unreachable("connection refused"));

    fullScan(ScanStatus.FAILED);

    assertEquals(
        RepositoryStatus.OK.name(),
        row(GHOST).status,
        "an unreadable catalog is not a catalog that lists nothing");
    assertEquals(1, store.pins(GHOST).size());
    assertEquals(RepositoryStatus.OK.name(), row(Fixture.REPOSITORY).status);
  }

  /**
   * <b>AND A CATALOG THAT ANSWERED NOTHING IS THE SAME REFUSAL.</b> A 200 with an empty array — an
   * emptied database, a filter somebody deployed by mistake — is a successful read whose listing
   * cannot be believed. The scan closes FAILED before the reconciliation is reached.
   */
  @Test
  void aCatalogThatListsNothingReconcilesNothingEither() {
    seedTheGhost("2026.811.1");
    catalogListing();

    fullScan(ScanStatus.FAILED);

    assertEquals(RepositoryStatus.OK.name(), row(GHOST).status);
    assertEquals(1, store.pins(GHOST).size());
  }

  // --- a manifest the tree lists and the git host does not serve (qits-1172) --------------------

  /**
   * <b>AN UNSERVED MANIFEST IS UNREACHABLE, NOT ABSENT.</b> The root lists {@code pom.xml}, the
   * blob read fails, and the scan used to write the repository's pins without the maven half —
   * {@code mt_pin} and {@code mt_main_pin} both — which {@code GET /pins} then served to the GC as
   * the whole truth. The same rule as an unreachable repository now: the row says why, and the pins
   * the last good scan read stay.
   */
  @Test
  void aListedPomTheGitHostFailsToServeKeepsThePinsAndMarksTheRowUnreachable() {
    catalogListing(Fixture.REPOSITORY);
    fullScan(ScanStatus.SUCCEEDED);
    java.util.List<String> pins = pinLines();
    java.util.List<String> mainPins = mainPinLines();
    assertTrue(
        pins.stream().anyMatch(line -> line.contains("qits-eventstream")),
        "the control: the good scan read the pom");
    assertTrue(mainPins.stream().anyMatch(line -> line.contains("qits-eventstream")));

    peers.answer(
        PeerTarget.GITHOST,
        "/git/" + Fixture.PROJECT + "/" + Fixture.REPOSITORY + "/blob/" + Fixture.HEAD_SHA
            + "/pom.xml",
        FakePeers.Scripted.status(502, "bad gateway"));
    UUID id = scans.request(ScanScope.ALL, Fixture.REPOSITORY, ScanTrigger.EVENT);
    queue.awaitIdle(Duration.ofSeconds(60));
    assertEquals(ScanStatus.SUCCEEDED.name(), store.scan(id).orElseThrow().status);

    MtRepository row = row(Fixture.REPOSITORY);
    assertEquals(RepositoryStatus.UNREACHABLE.name(), row.status);
    assertTrue(row.message.contains("pom.xml"), row.message);
    assertEquals(pins, pinLines(), "the last good scan's pins, every one of them");
    assertEquals(mainPins, mainPinLines(), "and main's, which GET /pins serves");
  }

  private java.util.List<String> pinLines() {
    return store.pins(Fixture.REPOSITORY).stream()
        .map(pin -> pin.ecosystem + " " + pin.name + " " + pin.version + " " + pin.manifestPath)
        .sorted()
        .toList();
  }

  private java.util.List<String> mainPinLines() {
    return store.allMainPins().stream()
        .filter(pin -> pin.repository.equals(Fixture.REPOSITORY))
        .map(pin -> pin.sha + " " + pin.ecosystem + " " + pin.name + " " + pin.version)
        .sorted()
        .toList();
  }

  // --- the fixture -----------------------------------------------------------------------------

  /** Scans the ghost in as a repository the catalog holds, so a later scan can drop it. */
  private void seedTheGhost(String version) {
    scriptTheGhostsManifests(version);
    catalogListing(Fixture.REPOSITORY, GHOST);
    fullScan(ScanStatus.SUCCEEDED);
  }

  private void fullScan(ScanStatus expected) {
    UUID id = scans.request(ScanScope.ALL, null, ScanTrigger.MANUAL);
    queue.awaitIdle(Duration.ofSeconds(60));
    assertEquals(expected.name(), store.scan(id).orElseThrow().status, "the scan's own ending");
  }

  private MtRepository row(String name) {
    return store
        .repository(name)
        .orElseThrow(() -> new AssertionError(name + " has no row at all — it must be KEPT"));
  }

  /** The catalog, listing exactly these names. Nothing else in the peers changes. */
  private void catalogListing(String... names) {
    StringBuilder json = new StringBuilder("{\"repositories\":[");
    for (int i = 0; i < names.length; i++) {
      if (i > 0) {
        json.append(',');
      }
      json.append("{\"id\":\"r-")
          .append(names[i])
          .append("\",\"projectId\":\"")
          .append(Fixture.PROJECT)
          .append("\",\"name\":\"")
          .append(names[i])
          .append("\",\"mainBranch\":\"main\"}");
    }
    peers.answer(
        PeerTarget.PROJECTS, CatalogReader.PATH, FakePeers.Scripted.ok(json.append("]}").toString()));
  }

  /**
   * The ghost's whole tree: a root with one pom, and a pom with one internal pin whose latest the
   * shared fixture already scripts a registry for.
   */
  private void scriptTheGhostsManifests(String version) {
    String tree = "/git/" + Fixture.PROJECT + "/" + GHOST + "/tree/";
    String blob = "/git/" + Fixture.PROJECT + "/" + GHOST + "/blob/" + GHOST_SHA + "/";
    String root = "{\"entries\":[{\"name\":\"pom.xml\",\"type\":\"blob\"}]}";
    Map<String, String> sha = Map.of("Git-Commit-Sha", GHOST_SHA);
    peers.answer(PeerTarget.GITHOST, tree + "main", FakePeers.Scripted.ok(root, sha));
    peers.answer(PeerTarget.GITHOST, tree + GHOST_SHA, FakePeers.Scripted.ok(root, sha));
    peers.answer(
        PeerTarget.GITHOST,
        blob + "pom.xml",
        FakePeers.Scripted.ok(
            """
            <project>
              <groupId>eu.wohlben.qits</groupId>
              <artifactId>%s</artifactId>
              <version>2026.800.1</version>
              <dependencies>
                <dependency>
                  <groupId>eu.wohlben.qits</groupId>
                  <artifactId>qits-eventstream</artifactId>
                  <version>%s</version>
                </dependency>
              </dependencies>
            </project>
            """
                .formatted(GHOST, version),
            sha));
  }
}
