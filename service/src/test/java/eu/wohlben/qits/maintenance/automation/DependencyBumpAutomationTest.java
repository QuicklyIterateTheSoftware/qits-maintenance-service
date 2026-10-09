package eu.wohlben.qits.maintenance.automation;

import static eu.wohlben.qits.maintenance.automation.AutomationFixture.REQUEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.bump.BumpService;
import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.dto.ReleaseRequestAutomationsDto;
import eu.wohlben.qits.maintenance.dto.ReleaseRequestAutomationsDto.AutomationDto;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.model.BumpStatus;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
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
 * <b>The dependency-bump automation</b> (qits-1133), with its switch on: a fold whose
 * platform-internal pins are behind gets one run that rebuilds the kind's branch from the base;
 * third-party pins are never in it; {@code hold:} leaves a pin alone; the DERIVED kinds wait for it.
 *
 * <p>The fold is {@link Fixture#HEAD_SHA}, the fixture repository's main, so the fold and the base
 * declare the same pins and the scan has filled {@code mt_latest} for every one of them.
 */
@QuarkusTest
@TestProfile(DependencyBumpAutomationTest.SwitchedOn.class)
class DependencyBumpAutomationTest {

  /** The switch on, nothing else changed. */
  public static class SwitchedOn implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(DependencyBumpAutomation.SWITCH, "true");
    }
  }

  private static final String RUN = "run-dependency-bump";

  private static final String FOLD = Fixture.HEAD_SHA;

  private static final String BLOB =
      "/git/" + Fixture.PROJECT + "/" + Fixture.REPOSITORY + "/blob/" + FOLD + "/";

  private static final List<String> BOTH = List.of("WAITING", "NOT_APPLICABLE");

  @Inject AutomationService automations;

  @Inject BumpService bumps;

  @Inject ScanService scans;

  @Inject MaintenanceStore store;

  @Inject FakePeers peers;

  @Inject InventoryReset inventory;

  @Inject WorkQueue queue;

  @Inject DependencyBumpAutomation dependencyBump;

  @Inject eu.wohlben.qits.maintenance.githost.GitHostReader gitHost;

  @BeforeEach
  void scriptThePeers() {
    queue.awaitIdle(Duration.ofSeconds(30));
    inventory.clear();
    peers.reset();
    Fixture.scriptScan(peers);
    Fixture.scriptBranchAbsent(peers);
    Fixture.scriptCiAccepts(peers, RUN);
    AutomationFixture.scriptRequest(peers, REQUEST, "PENDING", FOLD);
    AutomationFixture.scriptJoin(peers, REQUEST, AutomationFixture.joined(REQUEST));
    scans.request(ScanScope.ALL, null, ScanTrigger.MANUAL);
    queue.awaitIdle(Duration.ofSeconds(60));
  }

  private ReleaseRequestAutomationsDto trigger(List<String> accepts) {
    ReleaseRequestAutomationsDto answer =
        automations.trigger(
            REQUEST,
            new AutomationService.Fold(
                Fixture.REPOSITORY, FOLD, null, null, List.of("main", "work"), null, accepts));
    queue.awaitIdle(Duration.ofSeconds(30));
    return answer;
  }

  private static AutomationDto entry(ReleaseRequestAutomationsDto answer, String kind) {
    return answer.automations().stream()
        .filter(candidate -> kind.equals(candidate.kind()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no " + kind + " entry in " + answer));
  }

  private String payload() {
    List<String> bodies = peers.bodiesFor(CiClient.TRIGGER_PATH);
    assertEquals(1, bodies.size(), "one run: " + bodies);
    return bodies.getFirst();
  }

  private static String branch() {
    return AutomationFixture.branch(DependencyBumpAutomation.KIND, REQUEST);
  }

  /** The fold's {@code .config/qits/maintenance.yml}, replaced. */
  private void scriptConfig(String yaml) {
    peers.answer(
        PeerTarget.GITHOST,
        BLOB + ".config/qits/maintenance.yml",
        FakePeers.Scripted.ok(yaml, Map.of("Git-Commit-Sha", FOLD)));
  }

  /**
   * Behind platform pins are one run on the kind's own branch, with every change measured against
   * the base — and never a third-party pin.
   */
  @Test
  void behindPlatformPinsAreOneRunWithTheirChangesAndNoThirdPartyPin() {
    AutomationDto bump = entry(trigger(BOTH), DependencyBumpAutomation.KIND);

    assertEquals(AutomationState.REQUESTED.name(), bump.state(), bump.detail());
    MtBump row = store.bump(UUID.fromString(bump.bumpId())).orElseThrow();
    assertEquals(BumpStatus.RUNNING.name(), row.status, row.message);
    assertEquals(branch(), row.branch);
    String payload = payload();
    assertTrue(payload.contains("\"name\":\"MaintenanceBump\""), payload);
    assertTrue(payload.contains("\"kind\":\"dependency-bump\""), payload);
    assertTrue(payload.contains("\"group\":\"dependencies\""), payload);
    assertTrue(payload.contains("\"baseRef\":\"main\""), payload);
    assertTrue(payload.contains("\"branch\":\"" + branch() + "\""), payload);
    assertTrue(payload.contains("\"requestId\":\"" + REQUEST + "\""), payload);
    assertTrue(payload.contains("\"foldSha\":\"" + FOLD + "\""), payload);
    assertTrue(payload.contains("eu.wohlben.qits:qits-eventstream"), payload);
    assertTrue(payload.contains("\"to\":\"2026.821.3\""), payload);
    assertTrue(payload.contains("@qits/ui-components"), payload);
    assertFalse(payload.contains("quarkus-bom"), "a third-party pin is never bumped: " + payload);
    assertFalse(payload.contains("@angular/core"), payload);
    assertFalse(payload.contains("replaceHead"), "no branch yet, nothing to rebuild over");
  }

  /**
   * What the kind may commit is every manifest the fold's pins sit in, the lock beside {@code
   * package.json}, and the gitlink path — what carry-over and the overlap check read.
   */
  @Test
  void itMayCommitTheManifestsTheLockAndTheGitlink() {
    AutomationSubject subject =
        new AutomationSubject(
            store.repository(Fixture.REPOSITORY).orElseThrow(),
            REQUEST,
            FOLD,
            AutomationService.FOLD_BRANCH_PREFIX + REQUEST,
            List.of(),
            null,
            new FoldReader(gitHost, Fixture.PROJECT, Fixture.REPOSITORY, FOLD));

    List<String> paths = dependencyBump.committablePaths(subject);

    for (String path :
        List.of("pom.xml", "service/pom.xml", "package.json", "package-lock.json", "Dockerfile",
            "webui")) {
      assertTrue(paths.contains(path), path + " in " + paths);
    }
    assertFalse(paths.contains(".gitmodules"), paths.toString());
  }

  /** An existing branch is rebuilt: its head travels as {@code replaceHead}. */
  @Test
  void anExistingBranchIsRebuiltOverItsHead() {
    Fixture.scriptForeignBranchAt(peers, branch(), AutomationFixture.BEFORE);

    trigger(BOTH);

    assertTrue(
        payload().contains("\"replaceHead\":\"" + AutomationFixture.BEFORE + "\""), payload());
  }

  /** The kinds that do not apply are listed with their reason, to a caller that accepts the word. */
  @Test
  void theKindsThatDoNotApplyAreListedWithTheirReason() {
    ReleaseRequestAutomationsDto answer = trigger(BOTH);

    AutomationDto estate = entry(answer, EstatePinsAutomation.KIND);
    assertEquals(AutomationState.NOT_APPLICABLE.name(), estate.state());
    assertTrue(estate.detail().contains("no wrapper"), estate.detail());
    assertNull(estate.bumpId());
    assertNull(estate.updatedAt());
    assertEquals(
        AutomationState.NOT_APPLICABLE.name(),
        entry(answer, ScreenshotBaselinesAutomation.KIND).state());
    assertTrue(
        store.notApplicable(REQUEST, FOLD).containsKey(EstatePinsAutomation.KIND),
        "kept per (request, fold, kind)");
    assertEquals(
        answer.automations().size(),
        automations.automations(REQUEST, FOLD, BOTH).automations().size(),
        "and the read door answers the same entries");
  }

  /** A caller that names no extra state gets neither word: the answer before qits-1133. */
  @Test
  void aCallerThatAcceptsNothingGetsNeitherWord() {
    ReleaseRequestAutomationsDto answer = trigger(null);

    assertTrue(
        answer.automations().stream()
            .noneMatch(
                entry ->
                    entry.state().equals("NOT_APPLICABLE") || entry.state().equals("WAITING")),
        answer.toString());
    assertEquals(1, answer.automations().size(), answer.toString());
  }

  /** A DERIVED kind waits until the SOURCE kinds are FRESH, and an older caller reads UNKNOWN. */
  @Test
  void screenshotBaselinesWaitForTheBump() {
    Map<String, String> sha = Map.of("Git-Commit-Sha", FOLD);
    peers.answer(
        PeerTarget.GITHOST,
        BLOB + "package.json",
        FakePeers.Scripted.ok(
            "{\"name\":\"client\",\"scripts\":{\"test:browser\":\"vitest --browser\"},"
                + "\"dependencies\":{\"@qits/ui-components\":\"2026.8.1\"}}",
            sha));
    peers.answer(
        PeerTarget.GITHOST,
        BLOB + ScreenshotBaselinesAutomation.RENDERER,
        FakePeers.Scripted.ok("chromium 140 / linux\n", sha));

    AutomationDto waiting = entry(trigger(BOTH), ScreenshotBaselinesAutomation.KIND);
    assertEquals(AutomationState.WAITING.name(), waiting.state());
    assertEquals("waits for Dependency bump", waiting.detail());
    assertTrue(
        store.automations(REQUEST, FOLD).stream()
            .noneMatch(row -> ScreenshotBaselinesAutomation.KIND.equals(row.automationKind)),
        "WAITING is not stored");

    AutomationDto older = entry(trigger(null), ScreenshotBaselinesAutomation.KIND);
    assertEquals(AutomationState.UNKNOWN.name(), older.state());
    assertEquals("waits for Dependency bump", older.detail());
  }

  /** {@code hold:} leaves a pin alone: held everything is FRESH with no run. */
  @Test
  void heldPinsAreLeftAlone() {
    scriptConfig("hold: [\"*\"]\n");

    AutomationDto bump = entry(trigger(BOTH), DependencyBumpAutomation.KIND);

    assertEquals(AutomationState.FRESH.name(), bump.state(), bump.detail());
    assertTrue(bump.detail().contains("held: *"), bump.detail());
    assertTrue(peers.bodiesFor(CiClient.TRIGGER_PATH).isEmpty(), "no run");
  }

  /** One held pin is left out of the run; the rest are written. */
  @Test
  void oneHeldPinIsLeftOutOfTheRun() {
    scriptConfig("hold: [\"eu.wohlben.qits:qits-eventstream\"]\n");

    trigger(BOTH);

    assertFalse(payload().contains("qits-eventstream"), payload());
    assertTrue(payload().contains("@qits/ui-components"), payload());
  }

  /** A broken config is UNKNOWN with its sentence: it holds the request until the branch fixes it. */
  @Test
  void aBrokenConfigIsUnknown() {
    scriptConfig("hold: nope\n");

    AutomationDto bump = entry(trigger(BOTH), DependencyBumpAutomation.KIND);

    assertEquals(AutomationState.UNKNOWN.name(), bump.state());
    assertTrue(bump.detail().contains("`hold` must be a list"), bump.detail());
  }

  /** A green run that moved the branch joins it to the request at priority LOWEST. */
  @Test
  void aGreenRunJoinsItsBranchAtTheLowestPriority() {
    AutomationDto bump = entry(trigger(BOTH), DependencyBumpAutomation.KIND);
    Fixture.scriptForeignBranchAt(peers, branch(), AutomationFixture.PUSHED);
    Fixture.scriptRun(peers, RUN, "SUCCESS");

    bumps.poll(UUID.fromString(bump.bumpId()));
    queue.awaitIdle(Duration.ofSeconds(30));

    MtBump row = store.bump(UUID.fromString(bump.bumpId())).orElseThrow();
    assertEquals(BumpStatus.SUCCEEDED.name(), row.status, row.message);
    List<String> joins = peers.bodiesFor(AutomationFixture.joinPath(REQUEST));
    assertEquals(1, joins.size(), joins.toString());
    assertTrue(joins.getFirst().contains("\"priority\":\"LOWEST\""), joins.getFirst());
    assertTrue(joins.getFirst().contains(branch()), joins.getFirst());
  }

  /** The step's "not ours" exit says so: a hand-written commit is never rebuilt over. */
  @Test
  void aHandWrittenCommitFailsWithASentence() {
    AutomationDto bump = entry(trigger(BOTH), DependencyBumpAutomation.KIND);
    peers.answer(
        PeerTarget.CI,
        "/ci/api/runs/" + RUN,
        FakePeers.Scripted.ok(
            "{\"id\":\"" + RUN + "\",\"status\":\"FAILED\",\"steps\":[{\"stepIndex\":0,"
                + "\"image\":\"node-base:latest\",\"status\":\"FAILED\",\"exitCode\":42,"
                + "\"output\":\"not ours\\n\"}]}"));

    bumps.poll(UUID.fromString(bump.bumpId()));
    queue.awaitIdle(Duration.ofSeconds(30));

    MtBump row = store.bump(UUID.fromString(bump.bumpId())).orElseThrow();
    assertEquals(BumpStatus.FAILED.name(), row.status);
    assertTrue(row.message.contains("did not write"), row.message);
    assertTrue(peers.bodiesFor(AutomationFixture.joinPath(REQUEST)).isEmpty());
  }
}
