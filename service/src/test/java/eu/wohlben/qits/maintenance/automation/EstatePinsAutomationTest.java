package eu.wohlben.qits.maintenance.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.bump.BumpService;
import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.bump.ReleaseRequestClient;
import eu.wohlben.qits.maintenance.dto.ReleaseRequestAutomationsDto;
import eu.wohlben.qits.maintenance.dto.ReleaseRequestAutomationsDto.AutomationDto;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.githost.GitHostReader;
import eu.wohlben.qits.maintenance.latest.GitlinkSha;
import eu.wohlben.qits.maintenance.model.BumpStatus;
import eu.wohlben.qits.maintenance.model.BumpTrigger;
import eu.wohlben.qits.maintenance.model.GroupSource;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.pending.Change;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore.AutomationOpening;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The estate-pins kind</b> (qits-999, qits-1258): the gitlink decision qits-projects' {@code
 * EstatePinRefresh} made, ported, reading this service's own facts — the inventory's archetype, each
 * member's GITLINK latest (the release its main head carries, qits-1257), the git host — and, since
 * qits-1258, read AT THE FOLD and written as ONE commit on
 * the kind's own branch.
 *
 * <p>The decision's cases are still that class's: current pins are FRESH, one stale pin is one
 * change, a fold without {@code .gitmodules} and a sibling with no release are skipped, an
 * unreadable read is UNKNOWN, and no run ever carries an empty change list; the parity case holds the
 * change JSON to what {@code HttpEstatePins} sent. What qits-1258 adds is the cost and the target: a
 * nine-branch request is planned with the fold's {@code .gitmodules} and ONE recursive tree read,
 * whatever its branches; an older git host falls back to the per-directory listings; the kind's own
 * commit re-folds to FRESH; and a row opened on a source branch before the change still ends as one.
 */
@QuarkusTest
class EstatePinsAutomationTest {

  private static final String PROJECT = "qits";

  private static final String WRAPPER = "qits-qits";

  private static final String REQUEST = "9e1f0c3a-2b4d-4e6f-8a9b-0c1d2e3f4a5b";

  private static final String FOLD = "f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1f1";

  private static final String NEXT_FOLD = "f2f2f2f2f2f2f2f2f2f2f2f2f2f2f2f2f2f2f2f2";

  private static final String MEMBER_PATH = "components/qits-member/member-a";

  private static final String MEMBER_C_PATH = "components/qits-other/member-c";

  private static final String MEMBER_VERSION = "2026.910.180413";

  private static final String MEMBER_RELEASED_SHA = "0aa0aa0aa0aa0aa0aa0aa0aa0aa0aa0aa0aa0aa0";

  private static final String MEMBER_C_VERSION = "2026.911.10101";

  private static final String MEMBER_C_RELEASED_SHA = "0cc0cc0cc0cc0cc0cc0cc0cc0cc0cc0cc0cc0cc0";

  private static final String STALE_PIN = "0123456789abcdef0123456789abcdef01234567";

  private static final String RUN = "run-estate-pins";

  private static final String OWN_BRANCH =
      AutomationService.BRANCH_PREFIX + EstatePinsAutomation.KIND + "/" + REQUEST;

  /** The nine named branches of qits-qits-rr-137's shape, and main. */
  private static final List<String> NINE_BRANCHES =
      List.of(
          "main", "ticket/qits-1", "ticket/qits-2", "ticket/qits-3", "ticket/qits-4",
          "ticket/qits-5", "ticket/qits-6", "ticket/qits-7", "ticket/qits-8", "ticket/qits-9");

  /**
   * Four entries: two released members of the project in two component directories, a member
   * never released, and a name this inventory does not hold. Tab-indented, as the wrapper's reader
   * reads it.
   */
  private static final String GITMODULES =
      "[submodule \"member-a\"]\n"
          + "\tpath = " + MEMBER_PATH + "\n"
          + "\turl = ../member-a.git\n"
          + "\tbranch = main\n"
          + "[submodule \"member-b\"]\n"
          + "\tpath = components/qits-member/member-b\n"
          + "\turl = ../member-b.git\n"
          + "[submodule \"member-c\"]\n"
          + "\tpath = " + MEMBER_C_PATH + "\n"
          + "\turl = ../member-c.git\n"
          + "[submodule \"stranger\"]\n"
          + "\tpath = components/elsewhere/stranger\n"
          + "\turl = ../stranger.git\n";

  private static final String TREE = "/git/" + PROJECT + "/" + WRAPPER + "/tree/";

  private static final String BLOB = "/git/" + PROJECT + "/" + WRAPPER + "/blob/";

  private static final String REQUESTS =
      ReleaseRequestClient.REQUESTS_PATH_PREFIX + "id-" + WRAPPER
          + ReleaseRequestClient.REQUESTS_PATH_SUFFIX;

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
    repository("member-c", PROJECT, "SERVICE");
    store.recordRelease(
        "member-a", "2026.901.1", "0bb0bb0bb0bb0bb0bb0bb0bb0bb0bb0bb0bb0bb0",
        Instant.parse("2026-09-01T00:00:00Z"), List.of());
    store.recordRelease(
        "member-a", MEMBER_VERSION, MEMBER_RELEASED_SHA, Instant.parse("2026-09-10T18:04:13Z"),
        List.of());
    store.recordRelease(
        "member-c", MEMBER_C_VERSION, MEMBER_C_RELEASED_SHA,
        Instant.parse("2026-09-11T01:01:01Z"), List.of());
    // What each member's main head carries (qits-1257) — the row the plan reads. member-b has none.
    mainHead("member-a", MEMBER_VERSION, MEMBER_RELEASED_SHA);
    mainHead("member-c", MEMBER_C_VERSION, MEMBER_C_RELEASED_SHA);
    peers.answer(
        PeerTarget.CI,
        CiClient.TRIGGER_PATH,
        FakePeers.Scripted.ok(
            "{\"eventId\":\"e1\",\"runIds\":[\"" + RUN + "\"],\"repositoriesRead\":1,"
                + "\"repositoriesSkipped\":[]}"));
  }

  /** A member's GITLINK latest, as {@code latest/MainHeadLatest} writes it from main's head. */
  private void mainHead(String member, String version, String sha) {
    store.recordGitlinkLatest(member, version, GitlinkSha.of(sha), Instant.now());
  }

  private void repository(String name, String project, String archetype) {
    store.replaceInventory(
        name, project, "id-" + name, archetype, "main", RepositoryStatus.OK, "head", null,
        List.of(), List.of(), GroupSource.DEFAULT, pin -> PinKind.INTERNAL, Instant.now());
  }

  // --- the fold, as the git host answers it ------------------------------------------------------

  /** A gitlink entry of a recursive listing: its path under the root beside its name. */
  private static String gitlink(String path, String sha) {
    String name = path.substring(path.lastIndexOf('/') + 1);
    return "{\"name\":\"" + name + "\",\"path\":\"" + path + "\",\"type\":\"commit\","
        + "\"mode\":\"160000\",\"sha\":\"" + sha + "\"}";
  }

  /**
   * One fold of the wrapper: its {@code .gitmodules}, its plain root listing (so a file it lacks is
   * ABSENT), and its whole tree — answered RECURSIVELY, the way a current git host answers {@code
   * ?recursive=true}, or as the plain root listing an older one gives, with the component
   * directories then listable one by one. {@code memberA} and {@code memberC} are the gitlinks the
   * fold holds, null for one not committed yet; member-b is always at {@link #STALE_PIN}.
   */
  private void fold(String fold, String memberA, String memberC, boolean recursive) {
    Map<String, String> sha = Map.of("Git-Commit-Sha", fold);
    String root =
        "{\"entries\":[{\"name\":\".gitmodules\",\"type\":\"blob\"},"
            + "{\"name\":\"components\",\"type\":\"tree\"}]}";
    peers.answer(PeerTarget.GITHOST, TREE + fold, FakePeers.Scripted.ok(root, sha));
    peers.answer(
        PeerTarget.GITHOST, BLOB + fold + "/.gitmodules", FakePeers.Scripted.ok(GITMODULES, sha));
    if (recursive) {
      List<String> entries = new ArrayList<>();
      entries.add("{\"name\":\".gitmodules\",\"path\":\".gitmodules\",\"type\":\"blob\"}");
      entries.add("{\"name\":\"README.md\",\"path\":\"components/README.md\",\"type\":\"blob\"}");
      if (memberA != null) {
        entries.add(gitlink(MEMBER_PATH, memberA));
      }
      entries.add(gitlink("components/qits-member/member-b", STALE_PIN));
      if (memberC != null) {
        entries.add(gitlink(MEMBER_C_PATH, memberC));
      }
      peers.answer(
          PeerTarget.GITHOST,
          TREE + fold + GitHostReader.RECURSIVE_QUERY,
          FakePeers.Scripted.ok(
              "{\"recursive\":true,\"entries\":[" + String.join(",", entries) + "]}", sha));
    } else {
      // A git host that predates the parameter ignores it: the root's own listing, no "recursive".
      peers.answer(
          PeerTarget.GITHOST,
          TREE + fold + GitHostReader.RECURSIVE_QUERY,
          FakePeers.Scripted.ok(root, sha));
    }
    // The per-directory listings, for the fallback — and to prove the recursive path never asks.
    List<String> member = new ArrayList<>();
    if (memberA != null) {
      member.add(
          "{\"name\":\"member-a\",\"type\":\"commit\",\"mode\":\"160000\",\"sha\":\"" + memberA
              + "\"}");
    }
    member.add(
        "{\"name\":\"member-b\",\"type\":\"commit\",\"mode\":\"160000\",\"sha\":\"" + STALE_PIN
            + "\"}");
    peers.answer(
        PeerTarget.GITHOST,
        TREE + fold + "/components/qits-member",
        FakePeers.Scripted.ok("{\"entries\":[" + String.join(",", member) + "]}", sha));
    peers.answer(
        PeerTarget.GITHOST,
        TREE + fold + "/components/qits-other",
        FakePeers.Scripted.ok(
            memberC == null
                ? "{\"entries\":[]}"
                : "{\"entries\":[{\"name\":\"member-c\",\"type\":\"commit\",\"mode\":\"160000\","
                    + "\"sha\":\"" + memberC + "\"}]}",
            sha));
  }

  /** A fold whose every released member is pinned at its release. */
  private void currentFold(String fold) {
    fold(fold, MEMBER_RELEASED_SHA, MEMBER_C_RELEASED_SHA, true);
  }

  /** The request as qits-projects answers it: PENDING at that fold. */
  private void request(String mergedSha) {
    peers.answer(
        PeerTarget.PROJECTS,
        REQUESTS + "/" + REQUEST,
        FakePeers.Scripted.ok(
            "{\"request\":{\"id\":\"" + REQUEST + "\",\"state\":\"PENDING\",\"mergedSha\":\""
                + mergedSha + "\",\"sources\":[{\"kind\":\"BRANCH\",\"name\":\"main\"},"
                + "{\"kind\":\"BRANCH\",\"name\":\"work\"}]}}"));
  }

  /** The kind's own branch, standing at {@code sha}. */
  private void ownBranchAt(String sha) {
    peers.answer(
        PeerTarget.GITHOST,
        TREE + OWN_BRANCH.replace("/", "%2F"),
        FakePeers.Scripted.ok("{\"entries\":[]}", Map.of("Git-Commit-Sha", sha)));
  }

  /** How many calls reached the git host for this wrapper with a url ending in {@code tail}. */
  private long calls(String tail) {
    return peers.urls().stream().filter(url -> url.endsWith(tail)).count();
  }

  /** Every git-host call this wrapper's plan made — anything under its tree or blob routes. */
  private List<String> wrapperReads() {
    return peers.urls().stream()
        .filter(url -> url.contains("/git/" + PROJECT + "/" + WRAPPER + "/"))
        .toList();
  }

  private AutomationSubject subject(List<String> sourceBranches) {
    MtRepository wrapper = store.repository(WRAPPER).orElseThrow();
    return automations.subject(wrapper, REQUEST, FOLD, sourceBranches, null);
  }

  private Plan plan(List<String> sourceBranches) {
    return estatePins.plan(subject(sourceBranches));
  }

  private Plan plan() {
    return plan(List.of("main", "work"));
  }

  private static List<String> commitPaths(Plan.Run run) {
    @SuppressWarnings("unchecked")
    List<String> paths = (List<String>) run.extras().get(DependencyBumpAutomation.COMMIT_PATHS);
    return paths;
  }

  // --- the decision ------------------------------------------------------------------------------

  @Test
  void onlyAWrapperIsAnEstateAndItsCommitLandsOnItsOwnBranch() {
    assertTrue(estatePins.applicability(subject(List.of("work"))).applied());
    MtRepository member = store.repository("member-a").orElseThrow();
    assertFalse(
        estatePins
            .applicability(automations.subject(member, REQUEST, FOLD, List.of("work"), null))
            .applied());
    assertEquals(Target.OWN_BRANCH, estatePins.target());
    assertEquals(CiClient.AUTOMATION_EVENT_NAME, estatePins.pipeline());
    assertEquals(
        AutomationService.BRANCH_PREFIX + EstatePinsAutomation.KIND + "/",
        estatePins.branchPrefix());
  }

  @Test
  void currentPinsAreFreshWithNoRun() {
    currentFold(FOLD);

    Plan plan = plan();

    assertEquals(Plan.Kind.FRESH, plan.kind(), plan.reason());
    assertTrue(plan.runs().isEmpty());
  }

  @Test
  void stalePinsAreOneRunOnTheOwnBranchAndTheUnreleasedAndUnknownMembersAreSkipped() {
    fold(FOLD, STALE_PIN, null, true);

    Plan plan = plan();

    assertEquals(Plan.Kind.RUN, plan.kind(), plan.reason());
    assertEquals(1, plan.runs().size(), "one commit, whatever the branches");
    Plan.Run run = plan.runs().getFirst();
    assertNull(run.branch(), "the kind's own branch");
    assertEquals(
        List.of(
            new Change(
                "gitlink", MEMBER_PATH, "member-a", STALE_PIN, MEMBER_VERSION,
                "gitlink:" + MEMBER_PATH),
            new Change(
                "gitlink", MEMBER_C_PATH, "member-c", null, MEMBER_C_VERSION,
                "gitlink:" + MEMBER_C_PATH)),
        run.changes(),
        "member-b has no release, stranger is no repository of this project, and member-c's"
            + " gitlink is not committed yet");
    assertEquals(List.of(MEMBER_PATH, MEMBER_C_PATH), commitPaths(run));
  }

  @Test
  void aFoldWithoutGitmodulesPinsNothing() {
    Map<String, String> sha = Map.of("Git-Commit-Sha", FOLD);
    peers.answer(
        PeerTarget.GITHOST,
        TREE + FOLD,
        FakePeers.Scripted.ok("{\"entries\":[{\"name\":\"README.md\",\"type\":\"blob\"}]}", sha));

    Plan plan = plan();

    assertEquals(Plan.Kind.FRESH, plan.kind(), plan.reason());
    assertEquals(0, calls(GitHostReader.RECURSIVE_QUERY), "nothing declared, no tree read");
  }

  @Test
  void aSiblingWithNoReleaseIsNotInTheEstate() {
    currentFold(FOLD);
    // member-b's gitlink is STALE_PIN at the fold and it has no release: nothing to pin it at.

    assertEquals(Plan.Kind.FRESH, plan().kind(), "an unreleased member never becomes a change");
  }

  /**
   * <b>qits-qits-rr-137 (qits-1257).</b> member-a has a NEWER release in the ledger — cut, RELEASED,
   * its publish failed, never finalized and so never merged into main — and the estate still pins the
   * release main's head carries. Until qits-1257 the plan took the newest ledger row and named this
   * unfinalized one.
   */
  @Test
  void aMemberReleasedButNotFinalizedIsPinnedAtTheReleaseItsMainHeadCarries() {
    store.recordRelease(
        "member-a", "2026.1014.232301", "0dd0dd0dd0dd0dd0dd0dd0dd0dd0dd0dd0dd0dd0",
        Instant.parse("2026-10-14T23:23:01Z"), List.of());
    currentFold(FOLD);

    assertEquals(
        Plan.Kind.FRESH, plan().kind(), "a pin at main's release is current, whatever was cut since");

    peers.reset();
    fold(FOLD, STALE_PIN, MEMBER_C_RELEASED_SHA, true);
    Plan plan = plan();
    assertEquals(Plan.Kind.RUN, plan.kind(), plan.reason());
    assertEquals(
        List.of(
            new Change(
                "gitlink", MEMBER_PATH, "member-a", STALE_PIN, MEMBER_VERSION,
                "gitlink:" + MEMBER_PATH)),
        plan.runs().getFirst().changes(),
        "the previous, finalized release — never the newest ledger row");
  }

  /**
   * A member released into the ledger whose main head carries no release this service knows has no
   * GITLINK latest row, and is not in the estate — exactly as one with no release at all.
   */
  @Test
  void aMemberWithAReleaseButNoMainHeadLatestIsSkipped() {
    store.recordRelease(
        "member-b", "2026.1015.21231", "0ee0ee0ee0ee0ee0ee0ee0ee0ee0ee0ee0ee0ee0",
        Instant.parse("2026-10-15T02:12:31Z"), List.of());
    currentFold(FOLD);

    assertEquals(
        Plan.Kind.FRESH, plan().kind(), "member-b's STALE_PIN is no change: nothing finalized");
  }

  @Test
  void anUnreadableTreeOrDeclarationIsUnknown() {
    fold(FOLD, STALE_PIN, null, true);
    peers.answer(
        PeerTarget.GITHOST,
        TREE + FOLD + GitHostReader.RECURSIVE_QUERY,
        FakePeers.Scripted.unreachable("refused"));

    Plan tree = plan();
    assertEquals(Plan.Kind.UNKNOWN, tree.kind());
    assertTrue(tree.reason().contains("tree of the fold"), tree.reason());

    peers.answer(
        PeerTarget.GITHOST, BLOB + FOLD + "/.gitmodules", FakePeers.Scripted.unreachable("refused"));
    Plan declaration = plan();
    assertEquals(Plan.Kind.UNKNOWN, declaration.kind());
    assertTrue(declaration.reason().contains("submodule declaration"), declaration.reason());
  }

  /**
   * <b>THE COST (qits-1258).</b> Nine source branches are planned with the fold's {@code
   * .gitmodules} and ONE recursive tree read — two git-host calls, the same as for one branch — and
   * no branch, and no component directory, is read at all.
   */
  @Test
  void nineSourceBranchesArePlannedWithTwoReadsOfTheFold() {
    fold(FOLD, STALE_PIN, STALE_PIN, true);

    Plan nine = plan(NINE_BRANCHES);

    assertEquals(Plan.Kind.RUN, nine.kind(), nine.reason());
    assertEquals(1, nine.runs().size());
    List<String> reads = wrapperReads();
    assertEquals(2, reads.size(), reads.toString());
    assertTrue(reads.get(0).endsWith(BLOB + FOLD + "/.gitmodules"), reads.toString());
    assertTrue(
        reads.get(1).endsWith(TREE + FOLD + GitHostReader.RECURSIVE_QUERY),
        "the declaration and one recursive listing of the fold, nothing else");

    peers.reset();
    fold(FOLD, STALE_PIN, STALE_PIN, true);
    Plan one = plan(List.of("main", "work"));
    assertEquals(nine, one, "the branches the request names change nothing about the plan");
    assertEquals(2, wrapperReads().size());
  }

  /**
   * An older git host ignores {@code ?recursive=true} and answers the root's own listing: that is
   * no statement about what lies deeper, so each candidate's directory is listed — once per
   * directory — and the decision is the same one.
   */
  @Test
  void aGitHostThatCannotRecurseFallsBackToTheDirectoryListings() {
    fold(FOLD, STALE_PIN, STALE_PIN, false);

    Plan plan = plan(NINE_BRANCHES);

    assertEquals(Plan.Kind.RUN, plan.kind(), plan.reason());
    assertEquals(
        List.of(
            new Change(
                "gitlink", MEMBER_PATH, "member-a", STALE_PIN, MEMBER_VERSION,
                "gitlink:" + MEMBER_PATH),
            new Change(
                "gitlink", MEMBER_C_PATH, "member-c", STALE_PIN, MEMBER_C_VERSION,
                "gitlink:" + MEMBER_C_PATH)),
        plan.runs().getFirst().changes());
    assertEquals(1, calls(TREE + FOLD + GitHostReader.RECURSIVE_QUERY));
    assertEquals(1, calls(TREE + FOLD + "/components/qits-member"), "each directory once");
    assertEquals(1, calls(TREE + FOLD + "/components/qits-other"));
    assertEquals(4, wrapperReads().size(), wrapperReads().toString());

    peers.reset();
    fold(FOLD, MEMBER_RELEASED_SHA, MEMBER_C_RELEASED_SHA, false);
    assertEquals(Plan.Kind.FRESH, plan().kind(), "and current pins read current there too");
  }

  @Test
  void anUnreadableDirectoryInTheFallbackIsUnknown() {
    fold(FOLD, STALE_PIN, STALE_PIN, false);
    peers.answer(
        PeerTarget.GITHOST,
        TREE + FOLD + "/components/qits-other",
        FakePeers.Scripted.unreachable("refused"));

    Plan plan = plan();

    assertEquals(Plan.Kind.UNKNOWN, plan.kind());
    assertTrue(plan.reason().contains(MEMBER_C_PATH), plan.reason());
  }

  /** What the plan reads, and so what a fold must touch to be planned again rather than carried. */
  @Test
  void theInputsAreTheDeclarationAndItsGitlinks() {
    currentFold(FOLD);
    AutomationSubject subject = subject(List.of("work"));

    List<String> inputs = estatePins.inputPaths(subject);

    assertTrue(Pathspecs.matchesAny(inputs, ".gitmodules"));
    assertTrue(Pathspecs.matchesAny(inputs, MEMBER_PATH), "its own commit is an input");
    assertFalse(Pathspecs.matchesAny(inputs, "README.md"), "a person's README is not");
    assertFalse(Pathspecs.matchesAny(inputs, "src/__screenshots__/a.png"));

    peers.answer(
        PeerTarget.GITHOST, BLOB + FOLD + "/.gitmodules", FakePeers.Scripted.unreachable("refused"));
    assertTrue(
        Pathspecs.matchesAny(estatePins.inputPaths(subject(List.of("work"))), "README.md"),
        "an unreadable declaration names everything, so nothing carries on it");
  }

  // --- through the engine ------------------------------------------------------------------------

  /**
   * <b>PARITY.</b> The same {@code .gitmodules} and release rows produce, byte for byte, the change
   * array {@code HttpEstatePins.body} built for {@code POST /branches/bumps} — rebuilt here with that
   * method's own code — and that same array is what reaches qits-ci, now through the shared core
   * with the gitlink paths as its {@code commitPaths}.
   */
  @Test
  void theChangeJsonIsByteIdenticalToWhatHttpEstatePinsSent() throws Exception {
    fold(FOLD, STALE_PIN, null, true);
    List<Change> changes = plan().runs().getFirst().changes();
    assertEquals(httpEstatePinsChanges(changes), JSON.writeValueAsString(changes));

    request(FOLD);
    automations.trigger(
        REQUEST,
        new AutomationService.Fold(WRAPPER, FOLD, null, null, List.of("main", "work"), null));
    queue.awaitIdle(Duration.ofSeconds(30));
    List<String> triggers = peers.bodiesFor(CiClient.TRIGGER_PATH);
    assertEquals(1, triggers.size());
    JsonNode body = JSON.readTree(triggers.getFirst());
    assertEquals(CiClient.AUTOMATION_EVENT_NAME, body.get("name").asText());
    JsonNode payload = body.get("payload");
    assertEquals(httpEstatePinsChanges(changes), JSON.writeValueAsString(payload.get("changes")));
    assertEquals(EstatePinsAutomation.KIND, payload.get("kind").asText());
    assertEquals(OWN_BRANCH, payload.get("branch").asText());
    assertEquals(FOLD, payload.get("foldSha").asText());
    assertEquals(
        "[\"" + MEMBER_PATH + "\",\"" + MEMBER_C_PATH + "\"]",
        payload.get("commitPaths").toString(),
        "the gitlinks the changes move, and nothing else is staged");
    assertFalse(payload.has("group"), "no targeted sentinel on the shared core");
  }

  /**
   * <b>THE NINE-BRANCH WRAPPER REQUEST</b> (qits-qits-rr-137), settled end to end: ONE row, on
   * {@code maintenance/automations/estate-pins/<request>}, carrying every changed gitlink, and none
   * on any source branch; the fold read twice in all; the green run joined at LOWEST; and the re-fold
   * that commit causes — gitlink paths, this kind's inputs — planned again and found FRESH, with no
   * second run. The loop ends there.
   */
  @Test
  void aNineBranchWrapperRequestSettlesAsOneCommitAndItsReFoldIsFresh() throws Exception {
    fold(FOLD, STALE_PIN, STALE_PIN, true);
    currentFold(NEXT_FOLD);
    request(FOLD);
    peers.answer(
        PeerTarget.PROJECTS,
        REQUESTS + "/" + REQUEST + "/sources",
        FakePeers.Scripted.ok(
            "{\"request\":{\"id\":\"" + REQUEST + "\",\"state\":\"PENDING\"}}"));

    ReleaseRequestAutomationsDto answer =
        automations.trigger(
            REQUEST,
            new AutomationService.Fold(WRAPPER, FOLD, null, null, NINE_BRANCHES, "qits-1258"));
    queue.awaitIdle(Duration.ofSeconds(30));

    AutomationDto entry = AutomationFixture.entry(answer, EstatePinsAutomation.KIND);
    assertEquals(AutomationState.REQUESTED.name(), entry.state(), entry.detail());
    List<MtBump> rows = store.automationHistory(REQUEST, EstatePinsAutomation.KIND);
    assertEquals(1, rows.size(), "one row for nine branches: " + rows);
    MtBump row = rows.getFirst();
    assertEquals(OWN_BRANCH, row.branch);
    assertEquals(BumpStatus.RUNNING.name(), row.status, row.message);
    assertEquals(
        List.of(MEMBER_PATH, MEMBER_C_PATH),
        BumpService.changes(row).stream().map(Change::manifestPath).toList());
    for (String branch : NINE_BRANCHES) {
      String route = "/tree/" + branch.replace("/", "%2F");
      assertTrue(
          wrapperReads().stream().noneMatch(url -> url.endsWith(route)),
          "no source branch is read: " + branch + " in " + wrapperReads());
    }
    assertEquals(1, calls(TREE + FOLD + GitHostReader.RECURSIVE_QUERY), "one tree read");
    assertEquals(
        1, calls(BLOB + FOLD + "/.gitmodules"), "one declaration read, settle and dispatch alike");
    assertEquals(0, calls(TREE + FOLD + "/components/qits-member"), "no directory listing");
    List<String> triggers = peers.bodiesFor(CiClient.TRIGGER_PATH);
    assertEquals(1, triggers.size());
    assertEquals(
        OWN_BRANCH, JSON.readTree(triggers.getFirst()).get("payload").get("branch").asText());

    // The run pushed one commit onto the own branch; it ends COMMITTED and is joined at LOWEST.
    String written = "1000000000000000000000000000000000000009";
    ownBranchAt(written);
    peers.answer(
        PeerTarget.CI,
        "/ci/api/runs/" + RUN,
        FakePeers.Scripted.ok("{\"id\":\"" + RUN + "\",\"status\":\"SUCCESS\"}"));
    bumps.poll(row.id);
    queue.awaitIdle(Duration.ofSeconds(30));
    AutomationDto committed =
        AutomationFixture.entry(automations.automations(REQUEST, FOLD), EstatePinsAutomation.KIND);
    assertEquals(AutomationState.COMMITTED.name(), committed.state(), committed.detail());
    assertEquals(written, committed.resultSha());
    List<String> joins = peers.bodiesFor(REQUESTS + "/" + REQUEST + "/sources");
    assertEquals(1, joins.size());
    JsonNode join = JSON.readTree(joins.getFirst());
    assertEquals(OWN_BRANCH, join.get("branch").asText());
    assertEquals(ReleaseRequestClient.LOWEST, join.get("priority").asText());

    // The join re-folds the request: only the gitlinks changed, which this kind reads, so it is
    // PLANNED again rather than carried — and every pin is now at its release.
    request(NEXT_FOLD);
    AutomationDto next =
        AutomationFixture.entry(
            automations.trigger(
                REQUEST,
                new AutomationService.Fold(
                    WRAPPER, NEXT_FOLD, FOLD, List.of(MEMBER_PATH, MEMBER_C_PATH), NINE_BRANCHES,
                    "qits-1258")),
            EstatePinsAutomation.KIND);
    queue.awaitIdle(Duration.ofSeconds(30));
    assertEquals(AutomationState.FRESH.name(), next.state(), next.detail());
    assertTrue(next.detail().contains("already name what the members released"), next.detail());
    assertEquals(1, calls(TREE + NEXT_FOLD + GitHostReader.RECURSIVE_QUERY), "planned, not carried");
    assertEquals(1, peers.bodiesFor(CiClient.TRIGGER_PATH).size(), "no second run");
  }

  /** A fold that touched none of the inputs — a person's README — carries the outcome, unread. */
  @Test
  void aFoldThatTouchedNoInputCarries() {
    currentFold(FOLD);
    currentFold(NEXT_FOLD);
    request(FOLD);
    AutomationDto first =
        AutomationFixture.entry(
            automations.trigger(
                REQUEST,
                new AutomationService.Fold(WRAPPER, FOLD, null, null, NINE_BRANCHES, null)),
            EstatePinsAutomation.KIND);
    assertEquals(AutomationState.FRESH.name(), first.state(), first.detail());

    AutomationDto next =
        AutomationFixture.entry(
            automations.trigger(
                REQUEST,
                new AutomationService.Fold(
                    WRAPPER, NEXT_FOLD, FOLD, List.of("README.md"), NINE_BRANCHES, null)),
            EstatePinsAutomation.KIND);
    queue.awaitIdle(Duration.ofSeconds(30));

    assertEquals(AutomationState.FRESH.name(), next.state(), next.detail());
    assertTrue(next.detail().contains("carried"), next.detail());
    assertEquals(0, calls(TREE + NEXT_FOLD + GitHostReader.RECURSIVE_QUERY), "nothing planned");
  }

  /**
   * The re-run door plans this kind too: its plan is its change list, so a FRESH plan is recorded
   * on the own branch and no CI run is spent writing nothing.
   */
  @Test
  void aReRunOfCurrentPinsRecordsFreshWithoutARun() {
    currentFold(FOLD);
    request(FOLD);

    UUID id = automations.run(WRAPPER, REQUEST, EstatePinsAutomation.KIND, null, BumpTrigger.MANUAL);
    queue.awaitIdle(Duration.ofSeconds(30));

    MtBump row = store.bump(id).orElseThrow();
    assertEquals(BumpStatus.NOTHING_TO_DO.name(), row.status, row.message);
    assertEquals(OWN_BRANCH, row.branch);
    assertTrue(peers.bodiesFor(CiClient.TRIGGER_PATH).isEmpty());
  }

  /**
   * <b>That FRESH is the worker's now</b> (qits-1687): the door answers with a REQUESTED row before
   * a single read of the fold, and the plan the worker reads ends it NOTHING_TO_DO with the same
   * "fresh:" sentence the door used to record.
   */
  @Test
  void aReRunIsPlannedOnTheWorkerAndItsFreshPlanEndsTheRowThere() throws Exception {
    currentFold(FOLD);
    request(FOLD);
    java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
    queue.submit("hold the worker", () -> {
      try {
        held.await(30, java.util.concurrent.TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    UUID id;
    try {
      id = automations.run(WRAPPER, REQUEST, EstatePinsAutomation.KIND, null, BumpTrigger.MANUAL);

      MtBump opened = store.bump(id).orElseThrow();
      assertEquals(BumpStatus.REQUESTED.name(), opened.status, opened.message);
      assertEquals(AutomationService.PLANNING, opened.message);
      assertNull(opened.changes, "unplanned");
      assertTrue(wrapperReads().isEmpty(), "the door read nothing of the fold: " + wrapperReads());
    } finally {
      held.countDown();
    }
    queue.awaitIdle(Duration.ofSeconds(30));

    MtBump row = store.bump(id).orElseThrow();
    assertEquals(BumpStatus.NOTHING_TO_DO.name(), row.status, row.message);
    assertTrue(row.message.startsWith("fresh: "), row.message);
    assertTrue(row.message.contains("already name what the members released"), row.message);
    assertEquals(OWN_BRANCH, row.branch);
    assertEquals(1, calls(TREE + FOLD + GitHostReader.RECURSIVE_QUERY), "planned once, by the worker");
    assertTrue(peers.bodiesFor(CiClient.TRIGGER_PATH).isEmpty());
  }

  /**
   * A re-run whose plan cannot be read ends FAILED with the sentence the door's 409 used to carry
   * (qits-1687): the door has answered long before the plan is read, so the row is where it goes.
   */
  @Test
  void aReRunWhosePlanCannotBeReadEndsFailed() {
    fold(FOLD, STALE_PIN, null, true);
    peers.answer(
        PeerTarget.GITHOST,
        TREE + FOLD + GitHostReader.RECURSIVE_QUERY,
        FakePeers.Scripted.unreachable("refused"));
    request(FOLD);

    UUID id = automations.run(WRAPPER, REQUEST, EstatePinsAutomation.KIND, null, BumpTrigger.MANUAL);
    queue.awaitIdle(Duration.ofSeconds(30));

    MtBump row = store.bump(id).orElseThrow();
    assertEquals(BumpStatus.FAILED.name(), row.status, row.message);
    assertTrue(row.message.startsWith("Estate pins could not be planned: "), row.message);
    assertTrue(row.message.contains("tree of the fold"), row.message);
    assertTrue(peers.bodiesFor(CiClient.TRIGGER_PATH).isEmpty());
  }

  /**
   * <b>A ROW OPENED BEFORE qits-1258 ON A SOURCE BRANCH</b> still goes out and ends as the
   * source-branch row it is: the bump pipeline under the {@code targeted} sentinel, its run's verdict
   * rather than a head comparison, no join — though the kind now writes its own branch.
   */
  @Test
  void aLegacyRowOnASourceBranchStillEndsAsOne() throws Exception {
    String head = "3000000000000000000000000000000000000001";
    String after = "3000000000000000000000000000000000000002";
    request(FOLD);
    peers.answer(
        PeerTarget.GITHOST,
        TREE + "work",
        FakePeers.Scripted.ok("{\"entries\":[]}", Map.of("Git-Commit-Sha", head)));
    UUID id =
        store.openAutomation(
            new AutomationOpening(
                WRAPPER,
                EstatePinsAutomation.KIND,
                REQUEST,
                FOLD,
                null,
                null,
                "work",
                null,
                "test",
                BumpTrigger.FOLD,
                List.of(
                    new Change(
                        "gitlink", MEMBER_PATH, "member-a", STALE_PIN, MEMBER_VERSION,
                        "gitlink:" + MEMBER_PATH)),
                Map.of(),
                BumpStatus.REQUESTED,
                null),
            true,
            Instant.now());

    automations.dispatch(id);
    queue.awaitIdle(Duration.ofSeconds(30));

    MtBump running = store.bump(id).orElseThrow();
    assertEquals(BumpStatus.RUNNING.name(), running.status, running.message);
    assertEquals(CiClient.CONFIG_PATH, automations.configPath(running), "the bump pipeline's run");
    JsonNode body = JSON.readTree(peers.bodiesFor(CiClient.TRIGGER_PATH).getFirst());
    assertEquals(CiClient.EVENT_NAME, body.get("name").asText());
    assertEquals("work", body.get("payload").get("branch").asText());
    assertEquals(
        EstatePinsAutomation.TARGETED_GROUP, body.get("payload").get("group").asText());

    peers.answer(
        PeerTarget.GITHOST,
        TREE + "work",
        FakePeers.Scripted.ok("{\"entries\":[]}", Map.of("Git-Commit-Sha", after)));
    peers.answer(
        PeerTarget.CI,
        "/ci/api/runs/" + RUN,
        FakePeers.Scripted.ok("{\"id\":\"" + RUN + "\",\"status\":\"SUCCESS\"}"));
    bumps.poll(id);
    queue.awaitIdle(Duration.ofSeconds(30));

    MtBump done = store.bump(id).orElseThrow();
    assertEquals(BumpStatus.SUCCEEDED.name(), done.status, done.message);
    assertEquals(after, done.resultSha, "the run's commit, read once afterwards");
    assertEquals("1 dependencies on work at " + after, done.message, "the source-branch ending");
    assertTrue(
        peers.bodiesFor(REQUESTS + "/" + REQUEST + "/sources").isEmpty(),
        "a source branch is the request's already: nothing is joined");
  }

  /** The registry's disjointness, per subject: a wrapper's gitlink paths are nobody else's. */
  @Test
  void aWrappersGitlinkPathsAreDisjointFromEveryOtherKind() {
    currentFold(FOLD);
    AutomationSubject subject = subject(List.of("work"));
    assertEquals(
        List.of(
            MEMBER_PATH, "components/qits-member/member-b", MEMBER_C_PATH,
            "components/elsewhere/stranger"),
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
}
