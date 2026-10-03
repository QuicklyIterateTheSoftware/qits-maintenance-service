package eu.wohlben.qits.maintenance.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.control.Inventory;
import eu.wohlben.qits.maintenance.dto.PinSourceDto;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.model.ScanStatus;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>THE NPM PINS A SERVICE REACHES THROUGH ITS FRONTEND GITLINK ARE IN THE GC'S KEEP-SET</b>
 * (qits-740).
 *
 * <p>The fixture's {@code qits-ci} carries {@code webui}, a gitlink to {@code qits-ci-frontend}. The
 * lockfile of that frontend AT THE GITLINKED COMMIT pins {@code @qits/x@1.2.3} — a version the
 * frontend's own main may have long moved past, and which {@code qits-ci}'s build installs all the
 * same. Before this, no pin source named it, and a GC that took it broke that build with E404
 * (2026-09-05).
 *
 * <p>Scans here are of ONE repository, {@code qits-ci}, so the frontend's own main is never read and
 * every call to {@code qits-ci-frontend} counted below is the gitlink resolution's.
 */
@QuarkusTest
class GitlinkNpmPinsTest {

  private static final String FRONTEND = "qits-ci-frontend";

  /** Where the gitlink sits in the fixture, and the commit it records there. */
  private static final String GITLINK_PATH = "webui";

  private static final String SHA = Fixture.GITLINK_SHA;

  /** A later commit a gitlink bump moved it to. */
  private static final String MOVED_SHA = "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0";

  private static final String INTERNAL = "@qits/x";

  @Inject ScanService scans;

  @Inject MaintenanceStore store;

  @Inject Inventory inventory;

  @Inject FakePeers peers;

  @Inject InventoryReset reset;

  @Inject WorkQueue queue;

  @BeforeEach
  void scriptThePeers() {
    queue.awaitIdle(Duration.ofSeconds(30));
    reset.clear();
    script();
  }

  /** Every answer from scratch, with the call log emptied. */
  private void script() {
    peers.reset();
    Fixture.scriptScan(peers);
    // The frontend is a catalogued repository — which is what tells the scan which project the git
    // host addresses it under.
    peers.answer(
        PeerTarget.PROJECTS,
        "/projects/api/repositories",
        FakePeers.Scripted.ok(
            "{\"repositories\":["
                + "{\"id\":\"" + Fixture.CATALOG_ID + "\",\"projectId\":\"" + Fixture.PROJECT
                + "\",\"name\":\"" + Fixture.REPOSITORY + "\",\"mainBranch\":\"main\"},"
                + "{\"id\":\"r-front\",\"projectId\":\"" + Fixture.PROJECT
                + "\",\"name\":\"" + FRONTEND + "\",\"mainBranch\":\"main\"}]}"));
  }

  /** The frontend's tree at {@code sha}: a package.json and the lock that resolves it. */
  private void frontendAt(String sha, String internalVersion) {
    String tree = "/git/" + Fixture.PROJECT + "/" + FRONTEND + "/tree/" + sha;
    String blob = "/git/" + Fixture.PROJECT + "/" + FRONTEND + "/blob/" + sha + "/";
    Map<String, String> head = Map.of("Git-Commit-Sha", sha);
    peers.answer(
        PeerTarget.GITHOST,
        tree,
        FakePeers.Scripted.ok(
            "{\"entries\":[{\"name\":\"package.json\",\"type\":\"blob\"},"
                + "{\"name\":\"package-lock.json\",\"type\":\"blob\"}]}",
            head));
    peers.answer(
        PeerTarget.GITHOST,
        blob + "package.json",
        FakePeers.Scripted.ok(
            "{\"name\":\"front\",\"dependencies\":{\"" + INTERNAL + "\":\"^1.0.0\","
                + "\"@angular/core\":\"^21.0.0\"}}",
            head));
    peers.answer(
        PeerTarget.GITHOST,
        blob + "package-lock.json",
        FakePeers.Scripted.ok(
            "{\"lockfileVersion\":3,\"packages\":{\"\":{\"name\":\"front\"},"
                + "\"node_modules/" + INTERNAL + "\":{\"version\":\"" + internalVersion + "\"},"
                + "\"node_modules/@angular/core\":{\"version\":\"21.0.4\"}}}",
            head));
  }

  private void frontendUnreachableAt(String sha) {
    peers.answer(
        PeerTarget.GITHOST,
        "/git/" + Fixture.PROJECT + "/" + FRONTEND + "/tree/" + sha,
        FakePeers.Scripted.unreachable("connection refused"));
  }

  private void scanTheService() {
    UUID id = scans.request(ScanScope.ALL, Fixture.REPOSITORY, ScanTrigger.EVENT);
    queue.awaitIdle(Duration.ofSeconds(60));
    assertEquals(ScanStatus.SUCCEEDED.name(), store.scan(id).orElseThrow().status);
  }

  /** The keep-set's npm rows that came through a gitlink. */
  private List<PinSourceDto.ArtifactPinDto> gitlinkRows() {
    return inventory.pins().pins().stream()
        .filter(pin -> pin.via() != null && pin.via().startsWith("gitlink:"))
        .toList();
  }

  /**
   * Whether the frontend's tree at {@code sha} was asked for. Through a method: {@code peers} is a
   * client proxy, so its {@code calls} field read directly is the proxy's own, always empty.
   */
  private boolean frontendTreeRead(String sha) {
    return peers.called(
        PeerTarget.GITHOST, "/git/" + Fixture.PROJECT + "/" + FRONTEND + "/tree/" + sha);
  }

  // --- the rule -----------------------------------------------------------------------------------

  /**
   * THE WHOLE POINT: the lock at the gitlinked commit pins {@code @qits/x@1.2.3}, and the keep-set
   * names it — as an npm row of the CARRYING repository, with the gitlink and the commit as its
   * {@code via}. The external {@code @angular/core} beside it is not the GC's business.
   */
  @Test
  void anInternalNpmPinOfTheGitlinkedLockIsKeptByTheRepositoryCarryingTheGitlink() {
    frontendAt(SHA, "1.2.3");
    scanTheService();

    List<PinSourceDto.ArtifactPinDto> rows = gitlinkRows();
    assertEquals(
        List.of(
            new PinSourceDto.ArtifactPinDto(
                "npm",
                INTERNAL,
                "1.2.3",
                Fixture.REPOSITORY,
                GITLINK_PATH + "/package.json",
                "gitlink:" + GITLINK_PATH + "@" + SHA)),
        rows);
    // …and it is a keep-set row only: the carrying repository's own inventory, which is what a bump
    // edits, has no line for it.
    assertTrue(
        store.pins(Fixture.REPOSITORY).stream().noneMatch(pin -> INTERNAL.equals(pin.name)),
        "a pin reached through a gitlink is not a line of the carrying repository's");
  }

  /** A commit's tree never changes: a gitlink that has not moved is not read again. */
  @Test
  void anUnmovedGitlinkIsNotReadAgain() {
    frontendAt(SHA, "1.2.3");
    scanTheService();
    assertTrue(frontendTreeRead(SHA), "the first scan read the frontend's tree");

    script();
    frontendAt(SHA, "1.2.3");
    scanTheService();

    assertTrue(!frontendTreeRead(SHA), "an unmoved gitlink's tree is not read again");
    assertEquals(1, gitlinkRows().size(), "and the row is still served");
  }

  /**
   * A gitlink bump moved the commit, and the git host would not answer for the new tree. The rows
   * the gitlink reached last time stay — still naming the commit they were read at — rather than the
   * keep-set silently losing them.
   */
  @Test
  void anUnreadableTreeKeepsTheRowsTheGitlinkReachedBefore() {
    frontendAt(SHA, "1.2.3");
    scanTheService();
    assertEquals(1, gitlinkRows().size());

    Fixture.scriptGitlinkAt(peers, MOVED_SHA);
    frontendUnreachableAt(MOVED_SHA);
    scanTheService();

    assertEquals(
        List.of("1.2.3 gitlink:" + GITLINK_PATH + "@" + SHA),
        gitlinkRows().stream().map(row -> row.version() + " " + row.via()).toList());
    assertEquals(
        MOVED_SHA,
        store.pins(Fixture.REPOSITORY).stream()
            .filter(pin -> "gitlink".equals(pin.ecosystem))
            .findFirst()
            .orElseThrow()
            .version,
        "the gitlink pin itself moved; only the rows read through it are kept");

    // …and once the tree answers, the moved commit's lock replaces them.
    frontendAt(MOVED_SHA, "1.4.0");
    scanTheService();
    assertEquals(
        List.of("1.4.0 gitlink:" + GITLINK_PATH + "@" + MOVED_SHA),
        gitlinkRows().stream().map(row -> row.version() + " " + row.via()).toList());
  }

  /** An unreadable tree is not remembered as an empty one: the next scan asks again. */
  @Test
  void anUnreadableTreeIsAskedAgainNextScan() {
    frontendUnreachableAt(SHA);
    scanTheService();
    assertTrue(gitlinkRows().isEmpty());

    frontendAt(SHA, "1.2.3");
    scanTheService();
    assertEquals(1, gitlinkRows().size());
  }
}
