package eu.wohlben.qits.maintenance.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.bump.BumpService;
import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.dto.ReleaseRequestAutomationsDto;
import eu.wohlben.qits.maintenance.dto.ReleaseRequestAutomationsDto.AutomationDto;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.model.BumpStatus;
import eu.wohlben.qits.maintenance.model.GroupSource;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.pending.Change;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The estate-pins kind</b> (qits-999): the gitlink decision qits-projects' {@code
 * EstatePinRefresh} made, ported, reading this service's own facts — the inventory's archetype, the
 * release ledger, the git host. The cases are that class's: current pins are FRESH, one stale pin
 * is one change, a branch without {@code .gitmodules} and a sibling with no release are skipped, an
 * unreadable tree is UNKNOWN, two branches are two runs, main is never one, and no run ever carries
 * an empty change list. The parity case holds the change JSON to what {@code HttpEstatePins} sent.
 */
@QuarkusTest
class EstatePinsAutomationTest {

  private static final String PROJECT = "qits";

  private static final String WRAPPER = "qits-qits";

  private static final String REQUEST = "9e1f0c3a-2b4d-4e6f-8a9b-0c1d2e3f4a5b";

  private static final String FOLD = "f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1";

  private static final String NEXT_FOLD = "f2f2f2f2f2f2f2f2f2f2f2f2f2f2f2f2f2f2f2f2";

  private static final String MEMBER_PATH = "components/qits-member/member-a";

  private static final String MEMBER_VERSION = "2026.910.180413";

  private static final String MEMBER_RELEASED_SHA = "0aa0aa0aa0aa0aa0aa0aa0aa0aa0aa0aa0aa0aa0";

  private static final String STALE_PIN = "0123456789abcdef0123456789abcdef01234567";

  private static final String RUN = "run-estate-pins";

  /**
   * Three entries: a released member of the project, a member never released, and a name this
   * inventory does not hold. Tab-indented and with an inline section the wrapper's reader would
   * read exactly as written.
   */
  private static final String GITMODULES =
      "[submodule \"member-a\"]\n"
          + "\tpath = " + MEMBER_PATH + "\n"
          + "\turl = ../member-a.git\n"
          + "\tbranch = main\n"
          + "[submodule \"member-b\"]\n"
          + "\tpath = components/qits-member/member-b\n"
          + "\turl = ../member-b.git\n"
          + "[submodule \"stranger\"]\n"
          + "\tpath = components/elsewhere/stranger\n"
          + "\turl = ../stranger.git\n";

  private static final String TREE = "/git/" + PROJECT + "/" + WRAPPER + "/tree/";

  private static final String BLOB = "/git/" + PROJECT + "/" + WRAPPER + "/blob/";

  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject AutomationService automations;

  @Inject EstatePinsAutomation estatePins;

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
    repository("member-a", PROJECT, "SERVICE");
    repository("member-b", PROJECT, "SERVICE");
    store.recordRelease(
        "member-a", "2026.901.1", "0bb0bb0bb0bb0bb0bb0bb0bb0bb0bb0bb0bb0bb0",
        Instant.parse("2026-09-01T00:00:00Z"), List.of());
    store.recordRelease(
        "member-a", MEMBER_VERSION, MEMBER_RELEASED_SHA, Instant.parse("2026-09-10T18:04:13Z"),
        List.of());
    Ci.accepts(peers);
  }

  private void repository(String name, String project, String archetype) {
    store.replaceInventory(
        name, project, "id-" + name, archetype, "main", RepositoryStatus.OK, "head", null,
        List.of(), List.of(), GroupSource.DEFAULT, pin -> PinKind.INTERNAL, Instant.now());
  }

  /** One wrapper branch: its root (declaring submodules or not) and the gitlink it holds. */
  private void branch(String branch, String head, boolean declares, String pinned) {
    Map<String, String> sha = Map.of("Git-Commit-Sha", head);
    peers.answer(
        PeerTarget.GITHOST,
        TREE + branch.replace("/", "%2F"),
        FakePeers.Scripted.ok(
            declares
                ? "{\"entries\":[{\"name\":\".gitmodules\",\"type\":\"blob\"},"
                    + "{\"name\":\"components\",\"type\":\"tree\"}]}"
                : "{\"entries\":[{\"name\":\"README.md\",\"type\":\"blob\"}]}",
            sha));
    peers.answer(
        PeerTarget.GITHOST, BLOB + head + "/.gitmodules", FakePeers.Scripted.ok(GITMODULES, sha));
    peers.answer(
        PeerTarget.GITHOST,
        TREE + head + "/components/qits-member",
        FakePeers.Scripted.ok(
            pinned == null
                ? "{\"entries\":[]}"
                : "{\"entries\":[{\"name\":\"member-a\",\"type\":\"commit\",\"mode\":\"160000\","
                    + "\"sha\":\"" + pinned + "\"},"
                    + "{\"name\":\"member-b\",\"type\":\"commit\",\"mode\":\"160000\","
                    + "\"sha\":\"" + STALE_PIN + "\"}]}",
            sha));
  }

  private AutomationSubject subject(List<String> sourceBranches) {
    MtRepository wrapper = store.repository(WRAPPER).orElseThrow();
    return automations.subject(wrapper, REQUEST, FOLD, sourceBranches, null);
  }

  private Plan plan(String... sourceBranches) {
    return estatePins.plan(subject(List.of(sourceBranches)));
  }

  @Test
  void onlyAWrapperIsAnEstate() {
    assertTrue(estatePins.applicability(subject(List.of("work"))).applied());
    MtRepository member = store.repository("member-a").orElseThrow();
    assertFalse(
        estatePins
            .applicability(automations.subject(member, REQUEST, FOLD, List.of("work"), null))
            .applied());
  }

  @Test
  void currentPinsAreFreshWithNoRun() {
    branch("work", "1000000000000000000000000000000000000001", true, MEMBER_RELEASED_SHA);

    Plan plan = plan("work");

    assertEquals(Plan.Kind.FRESH, plan.kind(), plan.reason());
    assertTrue(plan.runs().isEmpty());
  }

  @Test
  void oneStalePinIsOneChangeAndTheUnreleasedAndUnknownMembersAreSkipped() {
    branch("work", "1000000000000000000000000000000000000001", true, STALE_PIN);

    Plan plan = plan("work");

    assertEquals(Plan.Kind.RUN, plan.kind(), plan.reason());
    assertEquals(1, plan.runs().size());
    Plan.Run run = plan.runs().getFirst();
    assertEquals("work", run.branch());
    assertEquals(
        List.of(
            new Change(
                "gitlink", MEMBER_PATH, "member-a", STALE_PIN, MEMBER_VERSION,
                "gitlink:" + MEMBER_PATH)),
        run.changes(),
        "member-b has no release and stranger is no repository of this project");
  }

  @Test
  void aBranchWithoutGitmodulesContributesNothing() {
    branch("work", "1000000000000000000000000000000000000001", false, STALE_PIN);

    assertEquals(Plan.Kind.FRESH, plan("work").kind());
  }

  @Test
  void aSiblingWithNoReleaseIsNotInTheEstate() {
    branch("work", "1000000000000000000000000000000000000001", true, MEMBER_RELEASED_SHA);
    // member-b's gitlink is STALE_PIN on that branch and it has no release: nothing to pin it at.

    Plan plan = plan("work");

    assertEquals(Plan.Kind.FRESH, plan.kind(), "an unreleased member never becomes a change");
  }

  @Test
  void anUnreadableTreeIsUnknown() {
    peers.answer(PeerTarget.GITHOST, TREE + "work", FakePeers.Scripted.unreachable("refused"));

    Plan plan = plan("work");

    assertEquals(Plan.Kind.UNKNOWN, plan.kind());
    assertTrue(plan.reason().contains("work"), plan.reason());
  }

  @Test
  void twoStaleSourceBranchesAreTwoRunsAndMainIsNeverOne() {
    branch("main", "1000000000000000000000000000000000000000", true, STALE_PIN);
    branch("work", "1000000000000000000000000000000000000001", true, STALE_PIN);
    branch("epic/also", "1000000000000000000000000000000000000002", true, null);

    Plan plan = plan("main", "work", "epic/also");

    assertEquals(
        List.of("work", "epic/also"),
        plan.runs().stream().map(Plan.Run::branch).toList(),
        "one run per branch the request releases, in source order, and never main");
    Plan.Run unpinned = plan.runs().get(1);
    assertEquals(null, unpinned.changes().getFirst().from(), "nothing committed there yet");
  }

  @Test
  void neverAnEmptyChangeList() {
    branch("work", "1000000000000000000000000000000000000001", true, STALE_PIN);
    branch("current", "1000000000000000000000000000000000000003", true, MEMBER_RELEASED_SHA);
    branch("plain", "1000000000000000000000000000000000000004", false, null);

    Plan plan = plan("work", "current", "plain");

    assertEquals(List.of("work"), plan.runs().stream().map(Plan.Run::branch).toList());
    assertTrue(plan.runs().stream().noneMatch(run -> run.changes().isEmpty()));
  }

  /**
   * <b>PARITY.</b> The same {@code .gitmodules} and release rows produce, byte for byte, the change
   * array {@code HttpEstatePins.body} built for {@code POST /branches/bumps} — rebuilt here with that
   * method's own code — and that same array is what reaches qits-ci in the trigger.
   */
  @Test
  void theChangeJsonIsByteIdenticalToWhatHttpEstatePinsSent() throws Exception {
    branch("work", "1000000000000000000000000000000000000001", true, STALE_PIN);
    branch("epic/also", "1000000000000000000000000000000000000002", true, null);

    List<Plan.Run> runs = plan("work", "epic/also").runs();
    for (Plan.Run run : runs) {
      String ours = JSON.writeValueAsString(run.changes());
      String theirs = httpEstatePinsChanges(run.changes());
      assertEquals(theirs, ours, "the change JSON of " + run.branch());
    }

    // And through the trigger: what leaves this process is that same array.
    WrapperFold.scriptFold(peers, FOLD);
    automations.trigger(
        REQUEST,
        new AutomationService.Fold(WRAPPER, FOLD, null, null, List.of("main", "work"), null));
    queue.awaitIdle(Duration.ofSeconds(30));
    List<String> triggers = peers.bodiesFor(CiClient.TRIGGER_PATH);
    assertEquals(1, triggers.size());
    JsonNode payload = JSON.readTree(triggers.getFirst()).get("payload");
    assertEquals(
        httpEstatePinsChanges(runs.getFirst().changes()),
        JSON.writeValueAsString(payload.get("changes")));
    assertEquals(BumpService.TARGETED_GROUP, payload.get("group").asText(), "bump(targeted): …");
    assertEquals("work", payload.get("branch").asText());
    assertEquals("MaintenanceBump", JSON.readTree(triggers.getFirst()).get("name").asText());
  }

  /**
   * The whole loop on the engine: the wrapper's fold asks one run, its green ending is COMMITTED
   * with the commit it left, and the re-fold that commit causes — a gitlink path and nothing else —
   * is FRESH with no second run.
   */
  @Test
  void aWrapperFoldWritesItsPinsAndTheReFoldIsFresh() {
    branch("work", "1000000000000000000000000000000000000001", true, STALE_PIN);
    WrapperFold.scriptFold(peers, FOLD);
    WrapperFold.scriptFold(peers, NEXT_FOLD);
    WrapperFold.scriptRequest(peers, REQUEST, FOLD);

    ReleaseRequestAutomationsDto answer =
        automations.trigger(
            REQUEST,
            new AutomationService.Fold(WRAPPER, FOLD, null, null, List.of("main", "work"), null));
    queue.awaitIdle(Duration.ofSeconds(30));
    assertEquals(1, answer.automations().size(), "screenshots do not apply to a wrapper");
    AutomationDto entry = answer.automations().getFirst();
    assertEquals(EstatePinsAutomation.KIND, entry.kind());
    assertEquals("Estate pins", entry.label());
    MtBump row = store.bump(UUID.fromString(entry.bumpId())).orElseThrow();
    assertEquals(BumpStatus.RUNNING.name(), row.status, row.message);
    assertEquals("work", row.branch);

    String written = "1000000000000000000000000000000000000009";
    branch("work", written, true, MEMBER_RELEASED_SHA);
    peers.answer(
        PeerTarget.CI,
        "/ci/api/runs/" + RUN,
        FakePeers.Scripted.ok("{\"id\":\"" + RUN + "\",\"status\":\"SUCCESS\"}"));
    bumps.poll(row.id);
    queue.awaitIdle(Duration.ofSeconds(30));
    AutomationDto committed = automations.automations(REQUEST, FOLD).automations().getFirst();
    assertEquals(AutomationState.COMMITTED.name(), committed.state(), committed.detail());
    assertEquals(written, committed.resultSha());

    AutomationDto next =
        automations
            .trigger(
                REQUEST,
                new AutomationService.Fold(
                    WRAPPER, NEXT_FOLD, FOLD, List.of(MEMBER_PATH), List.of("main", "work"), null))
            .automations()
            .getFirst();
    queue.awaitIdle(Duration.ofSeconds(30));
    assertEquals(AutomationState.FRESH.name(), next.state(), next.detail());
    assertEquals(1, peers.bodiesFor(CiClient.TRIGGER_PATH).size(), "no second run");
  }

  /** The registry's disjointness, per subject: a wrapper's gitlink paths are nobody else's. */
  @Test
  void aWrappersGitlinkPathsAreDisjointFromEveryOtherKind() {
    WrapperFold.scriptFold(peers, FOLD);
    AutomationSubject subject = subject(List.of("work"));
    assertEquals(
        List.of(MEMBER_PATH, "components/qits-member/member-b", "components/elsewhere/stranger"),
        estatePins.committablePaths(subject));
    AutomationRegistryTest.assertDisjoint(automations.kinds(), subject);
  }

  /** {@code HttpEstatePins.body}'s change array, rebuilt with that method's own code. */
  private static String httpEstatePinsChanges(List<Change> changes) throws Exception {
    ObjectNode root = JSON.createObjectNode();
    root.put("branch", "ignored");
    ArrayNode array = root.putArray("changes");
    for (Change change : changes) {
      ObjectNode node = array.addObject();
      node.put("ecosystem", "gitlink");
      node.put("manifestPath", change.manifestPath());
      node.put("name", change.name());
      node.put("from", change.from());
      node.put("to", change.to());
      node.put("location", "gitlink" + ":" + change.manifestPath());
    }
    return JSON.writeValueAsString(root.get("changes"));
  }

  /** The CI and qits-projects half of the wrapper's fixture. */
  private static final class Ci {

    static void accepts(FakePeers peers) {
      peers.answer(
          PeerTarget.CI,
          CiClient.TRIGGER_PATH,
          FakePeers.Scripted.ok(
              "{\"eventId\":\"e1\",\"runIds\":[\"" + RUN + "\"],\"repositoriesRead\":1,"
                  + "\"repositoriesSkipped\":[]}"));
    }
  }

  /** The wrapper's fold: a root that answers, so a missing package.json is ABSENT, and its declaration. */
  private static final class WrapperFold {

    static void scriptFold(FakePeers peers, String fold) {
      Map<String, String> sha = Map.of("Git-Commit-Sha", fold);
      peers.answer(PeerTarget.GITHOST, TREE + fold, FakePeers.Scripted.ok("{\"entries\":[]}", sha));
      peers.answer(
          PeerTarget.GITHOST, BLOB + fold + "/.gitmodules", FakePeers.Scripted.ok(GITMODULES, sha));
    }

    static void scriptRequest(FakePeers peers, String requestId, String mergedSha) {
      peers.answer(
          PeerTarget.PROJECTS,
          "/projects/api/repositories/id-" + WRAPPER + "/release-requests/" + requestId,
          FakePeers.Scripted.ok(
              "{\"request\":{\"id\":\"" + requestId + "\",\"state\":\"PENDING\",\"mergedSha\":\""
                  + mergedSha + "\"}}"));
    }
  }
}
