package eu.wohlben.qits.maintenance.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.bump.BumpService;
import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.bump.ReleaseRequestClient;
import eu.wohlben.qits.maintenance.dto.ReleaseRequestAutomationsDto.AutomationDto;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.model.BumpStatus;
import eu.wohlben.qits.maintenance.model.BumpTrigger;
import eu.wohlben.qits.maintenance.model.GroupSource;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>A member's release re-plans the estate</b> (qits-1224). A wrapper request folded before a
 * member released has {@code estate-pins} FRESH at that fold, and the per-fold verdict is never
 * planned again by the fold trigger. The release recorded in the ledger is what asks again: through
 * {@link UpstreamReplan#released}, past the stored FRESH, onto the request's own non-main source
 * branch — and the re-fold that run's commit causes is carried FRESH as any estate commit is. A
 * release of a repository of another project reaches no wrapper of this one.
 */
@QuarkusTest
class EstatePinsUpstreamReplanTest {

  private static final String PROJECT = "qits";

  private static final String WRAPPER = "qits-qits";

  private static final String MEMBER = "member-a";

  private static final String OUTSIDER = "outsider";

  private static final String REQUEST = "7e1f0c3a-2b4d-4e6f-8a9b-0c1d2e3f4a5b";

  private static final String FOLD = "e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1";

  private static final String NEXT_FOLD = "e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2e2";

  private static final String MEMBER_PATH = "components/qits-member/member-a";

  private static final String OLD_VERSION = "2026.901.1";

  private static final String OLD_SHA = "0bb0bb0bb0bb0bb0bb0bb0bb0bb0bb0bb0bb0bb0";

  private static final String NEW_VERSION = "2026.1013.90000";

  private static final String NEW_SHA = "0aa0aa0aa0aa0aa0aa0aa0aa0aa0aa0aa0aa0aa0";

  private static final String WORK_HEAD = "2000000000000000000000000000000000000001";

  private static final String RUN = "run-estate-upstream";

  private static final String GITMODULES =
      "[submodule \"member-a\"]\n"
          + "\tpath = " + MEMBER_PATH + "\n"
          + "\turl = ../member-a.git\n";

  private static final String TREE = "/git/" + PROJECT + "/" + WRAPPER + "/tree/";

  private static final String BLOB = "/git/" + PROJECT + "/" + WRAPPER + "/blob/";

  private static final String REQUESTS =
      ReleaseRequestClient.REQUESTS_PATH_PREFIX + "id-" + WRAPPER
          + ReleaseRequestClient.REQUESTS_PATH_SUFFIX;

  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject UpstreamReplan upstream;

  @Inject AutomationService automations;

  @Inject BumpService bumps;

  @Inject MaintenanceStore store;

  @Inject FakePeers peers;

  @Inject InventoryReset inventory;

  @Inject WorkQueue queue;

  @BeforeEach
  void seed() {
    queue.awaitIdle(Duration.ofSeconds(30));
    inventory.clear();
    peers.reset();
    repository(WRAPPER, PROJECT, "PROJECT");
    repository(MEMBER, PROJECT, "SERVICE");
    repository("other-qits", "other", "PROJECT");
    repository(OUTSIDER, "other", "SERVICE");
    store.recordRelease(
        MEMBER, OLD_VERSION, OLD_SHA, Instant.parse("2026-09-01T00:00:00Z"), List.of());
    peers.answer(
        PeerTarget.CI,
        CiClient.TRIGGER_PATH,
        FakePeers.Scripted.ok(
            "{\"eventId\":\"e1\",\"runIds\":[\"" + RUN + "\"],\"repositoriesRead\":1,"
                + "\"repositoriesSkipped\":[]}"));
    branch("work", WORK_HEAD, OLD_SHA);
    fold(FOLD);
    fold(NEXT_FOLD);
    request(FOLD);
    peers.answer(
        PeerTarget.PROJECTS,
        REQUESTS,
        FakePeers.Scripted.ok(
            "{\"requests\":[{\"id\":\"" + REQUEST + "\",\"state\":\"PENDING\",\"mergedSha\":\""
                + FOLD + "\",\"gates\":[{\"kind\":\"CI\",\"state\":\"PENDING\","
                + "\"detail\":null}]}]}"));
  }

  @AfterEach
  void drain() {
    queue.awaitIdle(Duration.ofSeconds(30));
  }

  private void repository(String name, String project, String archetype) {
    store.replaceInventory(
        name, project, "id-" + name, archetype, "main", RepositoryStatus.OK, "head", null,
        List.of(), List.of(), GroupSource.DEFAULT, pin -> PinKind.INTERNAL, Instant.now());
  }

  /** One wrapper source branch declaring the member, its gitlink at {@code pinned}. */
  private void branch(String branch, String head, String pinned) {
    Map<String, String> sha = Map.of("Git-Commit-Sha", head);
    peers.answer(
        PeerTarget.GITHOST,
        TREE + branch,
        FakePeers.Scripted.ok(
            "{\"entries\":[{\"name\":\".gitmodules\",\"type\":\"blob\"},"
                + "{\"name\":\"components\",\"type\":\"tree\"}]}",
            sha));
    peers.answer(
        PeerTarget.GITHOST, BLOB + head + "/.gitmodules", FakePeers.Scripted.ok(GITMODULES, sha));
    peers.answer(
        PeerTarget.GITHOST,
        TREE + head + "/components/qits-member",
        FakePeers.Scripted.ok(
            "{\"entries\":[{\"name\":\"member-a\",\"type\":\"commit\",\"mode\":\"160000\","
                + "\"sha\":\"" + pinned + "\"}]}",
            sha));
  }

  /** The wrapper's fold: a root that answers, and its declaration. */
  private void fold(String fold) {
    Map<String, String> sha = Map.of("Git-Commit-Sha", fold);
    peers.answer(PeerTarget.GITHOST, TREE + fold, FakePeers.Scripted.ok("{\"entries\":[]}", sha));
    peers.answer(
        PeerTarget.GITHOST, BLOB + fold + "/.gitmodules", FakePeers.Scripted.ok(GITMODULES, sha));
  }

  /** The request: PENDING at that fold, releasing main and the non-main source branch "work". */
  private void request(String mergedSha) {
    peers.answer(
        PeerTarget.PROJECTS,
        REQUESTS + "/" + REQUEST,
        FakePeers.Scripted.ok(
            "{\"request\":{\"id\":\"" + REQUEST + "\",\"state\":\"PENDING\",\"mergedSha\":\""
                + mergedSha + "\",\"sources\":[{\"kind\":\"BRANCH\",\"name\":\"main\"},"
                + "{\"kind\":\"BRANCH\",\"name\":\"work\"}]}}"));
  }

  private void released(String repository) {
    upstream.released(repository);
    // Twice: the re-plan runs on the worker and queues its own dispatch behind the first barrier.
    queue.awaitIdle(Duration.ofSeconds(30));
    queue.awaitIdle(Duration.ofSeconds(30));
  }

  private List<MtBump> estateRows() {
    return store.automationHistory(REQUEST, EstatePinsAutomation.KIND);
  }

  /** The fold trigger settles estate pins FRESH at FOLD: every pin names the member's release. */
  private void foldedBeforeTheRelease() {
    AutomationDto entry =
        AutomationFixture.entry(
            automations.trigger(
                REQUEST,
                new AutomationService.Fold(
                    WRAPPER, FOLD, null, null, List.of("main", "work"), null)),
            EstatePinsAutomation.KIND);
    queue.awaitIdle(Duration.ofSeconds(30));
    assertEquals(AutomationState.FRESH.name(), entry.state(), entry.detail());
    assertEquals(
        BumpStatus.NOTHING_TO_DO.name(), estateRows().getFirst().status, "stored for the fold");
    assertTrue(peers.bodiesFor(CiClient.TRIGGER_PATH).isEmpty());
  }

  /**
   * The whole loop: FRESH at the fold, the member releases, the estate pins are re-planned past the
   * stored verdict and a bump goes onto "work" (never main) — and the re-fold its commit causes is
   * carried FRESH with no second run.
   */
  @Test
  void aMemberReleaseReplansAFreshFoldOntoTheNonMainSourceBranch() throws Exception {
    foldedBeforeTheRelease();
    store.recordRelease(
        MEMBER, NEW_VERSION, NEW_SHA, Instant.parse("2026-10-13T09:00:00Z"), List.of());

    released(MEMBER);

    List<String> triggers = peers.bodiesFor(CiClient.TRIGGER_PATH);
    assertEquals(1, triggers.size(), "one run, for the one branch whose pin lags");
    JsonNode payload = JSON.readTree(triggers.getFirst()).get("payload");
    assertEquals("work", payload.get("branch").asText());
    assertEquals(EstatePinsAutomation.TARGETED_GROUP, payload.get("group").asText());
    JsonNode change = payload.get("changes").get(0);
    assertEquals(MEMBER_PATH, change.get("manifestPath").asText());
    assertEquals(OLD_SHA, change.get("from").asText());
    assertEquals(NEW_VERSION, change.get("to").asText());

    MtBump row =
        estateRows().stream()
            .filter(candidate -> BumpTrigger.UPSTREAM.name().equals(candidate.trigger))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no upstream row: " + estateRows()));
    assertEquals(FOLD, row.foldSha);
    assertEquals("work", row.branch);
    assertEquals(BumpStatus.RUNNING.name(), row.status, row.message);
    assertEquals(1, store.upstreamRestarts(REQUEST));
    AutomationDto atFold =
        AutomationFixture.entry(automations.automations(REQUEST, FOLD), EstatePinsAutomation.KIND);
    assertEquals(AutomationState.RUNNING.name(), atFold.state(), "the fold no longer reads FRESH");

    // The run's push onto "work" re-folds the request; its green ending is COMMITTED at FOLD.
    String written = "2000000000000000000000000000000000000009";
    branch("work", written, NEW_SHA);
    request(NEXT_FOLD);
    peers.answer(
        PeerTarget.CI,
        "/ci/api/runs/" + RUN,
        FakePeers.Scripted.ok("{\"id\":\"" + RUN + "\",\"status\":\"SUCCESS\"}"));
    bumps.poll(row.id);
    queue.awaitIdle(Duration.ofSeconds(30));
    AutomationDto committed =
        AutomationFixture.entry(automations.automations(REQUEST, FOLD), EstatePinsAutomation.KIND);
    assertEquals(AutomationState.COMMITTED.name(), committed.state(), committed.detail());

    AutomationDto next =
        AutomationFixture.entry(
            automations.trigger(
                REQUEST,
                new AutomationService.Fold(
                    WRAPPER, NEXT_FOLD, FOLD, List.of(MEMBER_PATH), List.of("main", "work"),
                    null)),
            EstatePinsAutomation.KIND);
    queue.awaitIdle(Duration.ofSeconds(30));
    assertEquals(AutomationState.FRESH.name(), next.state(), next.detail());
    assertTrue(next.detail().contains("carried"), next.detail());
    assertEquals(1, peers.bodiesFor(CiClient.TRIGGER_PATH).size(), "no second run");
  }

  /** qits-projects' history of the member: the one request that cut {@link #NEW_VERSION}. */
  private void memberCut(String state, String publish) {
    peers.answer(
        PeerTarget.PROJECTS,
        ReleaseRequestClient.REQUESTS_PATH_PREFIX + "id-" + MEMBER
            + ReleaseRequestClient.REQUESTS_PATH_SUFFIX + ReleaseRequestClient.HISTORY_QUERY,
        FakePeers.Scripted.ok(
            "{\"requests\":[{\"id\":\"rr-member\",\"version\":\"" + NEW_VERSION
                + "\",\"state\":\"" + state + "\",\"pipeline\":{\"phases\":[{\"phase\":"
                + "\"PUBLISH\",\"state\":\"" + publish + "\"}]}}]}"));
  }

  /**
   * THE LIVE TIMELINE (qits-1235): the member is tagged, and the re-plan reaches the estate pins
   * seconds later, while the member's publish run is still RUNNING and its changelog is not stored.
   * The row waits REQUESTED rather than FAILING the wrapper's request; once the changelog is stored
   * and the request reads FINALIZED, the next dispatch sends it, naming that changelog.
   */
  @Test
  void aMemberReleaseStillPublishingItsChangelogIsWaitedForThenSent() throws Exception {
    foldedBeforeTheRelease();
    Fixture.scriptChangelogs(peers, MEMBER, OLD_VERSION);
    memberCut("RELEASED", "RUNNING");
    store.recordRelease(
        MEMBER, NEW_VERSION, NEW_SHA, Instant.parse("2026-10-14T09:24:08Z"), List.of());

    released(MEMBER);

    MtBump row =
        estateRows().stream()
            .filter(candidate -> BumpTrigger.UPSTREAM.name().equals(candidate.trigger))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no upstream row: " + estateRows()));
    assertEquals(BumpStatus.REQUESTED.name(), row.status, row.message);
    assertEquals(
        "the changelog of " + MEMBER + " " + NEW_VERSION + " is " + BumpService.CHANGELOGS_PENDING,
        row.message);
    assertTrue(peers.bodiesFor(CiClient.TRIGGER_PATH).isEmpty(), "nothing sent while it waits");
    AutomationDto waiting =
        AutomationFixture.entry(automations.automations(REQUEST, FOLD), EstatePinsAutomation.KIND);
    assertEquals(AutomationState.REQUESTED.name(), waiting.state(), waiting.detail());

    // 09:31:39: the publish run stored the changelog and the request finalized.
    Fixture.scriptChangelogs(peers, MEMBER, OLD_VERSION, NEW_VERSION);
    memberCut("FINALIZED", "SUCCESS");
    bumps.sweep();
    queue.awaitIdle(Duration.ofSeconds(30));

    MtBump sent = store.bump(row.id).orElseThrow();
    assertEquals(BumpStatus.RUNNING.name(), sent.status, sent.message);
    List<String> triggers = peers.bodiesFor(CiClient.TRIGGER_PATH);
    assertEquals(1, triggers.size(), triggers.toString());
    JsonNode change = JSON.readTree(triggers.getFirst()).get("payload").get("changes").get(0);
    assertEquals(NEW_VERSION, change.get("to").asText());
    assertEquals(
        JSON.readTree(
            "{\"repository\":\"" + MEMBER + "\",\"versions\":[\"" + NEW_VERSION + "\"]}"),
        change.get("changelog"));
  }

  /** A release the fold already pins opens nothing and counts nothing. */
  @Test
  void aReleaseTheFoldAlreadyPinsOpensNothing() {
    foldedBeforeTheRelease();

    released(MEMBER);

    assertEquals(1, estateRows().size(), estateRows().toString());
    assertTrue(peers.bodiesFor(CiClient.TRIGGER_PATH).isEmpty());
    assertEquals(0, store.upstreamRestarts(REQUEST));
  }

  /**
   * A release of a repository of another project reaches no wrapper of this one: this wrapper's
   * requests are not even listed. Nor does a wrapper's own release re-plan itself.
   */
  @Test
  void aReleaseOutsideTheProjectLeavesThisWrapperAlone() {
    foldedBeforeTheRelease();
    store.recordRelease(
        MEMBER, NEW_VERSION, NEW_SHA, Instant.parse("2026-10-13T09:00:00Z"), List.of());

    released(OUTSIDER);
    released(WRAPPER);

    assertTrue(peers.bodiesFor(REQUESTS).isEmpty(), "the wrapper's requests were not listed");
    assertEquals(1, estateRows().size(), estateRows().toString());
    assertTrue(peers.bodiesFor(CiClient.TRIGGER_PATH).isEmpty());
  }
}
