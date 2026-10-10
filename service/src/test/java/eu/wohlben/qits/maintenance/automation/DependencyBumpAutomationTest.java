package eu.wohlben.qits.maintenance.automation;

import static eu.wohlben.qits.maintenance.automation.AutomationFixture.CURRENT_POM;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.EVENTSTREAM;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.FOLD_A;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.FOLD_B;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.FOLD_C;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.QUARKUS_BOM;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.REQUEST;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.STALE_POM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.bump.BumpService;
import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.config.MaintenanceConfig;
import eu.wohlben.qits.maintenance.config.UpstreamSwitch;
import eu.wohlben.qits.maintenance.dto.ReleaseRequestAutomationsDto.AutomationDto;
import eu.wohlben.qits.maintenance.dto.ReleaseRequestAutomationsDto;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.entity.MtReleaseRequest;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.latest.GitlinkSha;
import eu.wohlben.qits.maintenance.model.BumpStatus;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.pending.Change;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The pre-run's two stages and its dependency bump</b> (qits-1133), driven through {@code
 * AutomationService.trigger} the way qits-projects' post reaches it: the bump planned AT THE FOLD,
 * the DERIVED kinds WAITING behind it, the two new answer words only for a caller that accepts
 * them, the LOWEST join, and the carry-over a SOURCE commit must not get for the DERIVED kinds.
 */
@QuarkusTest
@TestProfile(DependencyBumpOn.class)
class DependencyBumpAutomationTest {

  private static final String RUN = "run-dependency-bump";

  private static final List<String> ACCEPTS = List.of("WAITING", "NOT_APPLICABLE");

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String BUMP_BRANCH =
      AutomationFixture.branch(DependencyBumpAutomation.KIND, REQUEST);

  @Inject AutomationService automations;

  @Inject DependencyBumpAutomation dependencyBump;

  @Inject BumpService bumps;

  @Inject ScanService scans;

  @Inject MaintenanceStore store;

  @Inject FakePeers peers;

  @Inject InventoryReset inventory;

  @Inject WorkQueue queue;

  @Inject MaintenanceConfig config;

  @BeforeEach
  void scriptThePeers() {
    queue.awaitIdle(Duration.ofSeconds(30));
    inventory.clear();
    peers.reset();
    Fixture.scriptScan(peers);
    Fixture.scriptBranchAbsent(peers);
    Fixture.scriptCiAccepts(peers, RUN);
    // Who publishes each internal pin: a bump's changelog ranges need a source repository (qits-893).
    Fixture.seedProducers(store);
    for (String fold : List.of(FOLD_A, FOLD_B, FOLD_C)) {
      AutomationFixture.scriptFold(peers, fold, true);
    }
    AutomationFixture.scriptRequest(peers, REQUEST, "PENDING", FOLD_A);
    AutomationFixture.scriptJoin(peers, REQUEST, AutomationFixture.joined(REQUEST));
    Fixture.scriptForeignBranchAt(
        peers, AutomationFixture.branch(REQUEST), AutomationFixture.BEFORE);
    Fixture.scriptForeignBranchAt(peers, BUMP_BRANCH, AutomationFixture.BEFORE);
    scans.request(ScanScope.ALL, null, ScanTrigger.MANUAL);
    queue.awaitIdle(Duration.ofSeconds(60));
  }

  private ReleaseRequestAutomationsDto trigger(
      String fold, String previous, List<String> changed, List<String> accepts) {
    ReleaseRequestAutomationsDto answer =
        automations.trigger(
            REQUEST,
            new AutomationService.Fold(
                Fixture.REPOSITORY, fold, previous, changed, List.of("main", "work"), "qits-1133",
                accepts));
    queue.awaitIdle(Duration.ofSeconds(30));
    return answer;
  }

  private MtBump row(AutomationDto entry) {
    return store.bump(UUID.fromString(entry.bumpId())).orElseThrow();
  }

  private List<MtBump> rows(String fold, String kind) {
    return store.automations(REQUEST, fold).stream()
        .filter(row -> kind.equals(row.automationKind))
        .toList();
  }

  private List<String> triggers() {
    return peers.bodiesFor(CiClient.TRIGGER_PATH);
  }

  private static List<String> names(List<Change> changes) {
    return changes.stream().map(Change::name).toList();
  }

  /** Ends the newest run of a row with this CI status, the branch standing at {@code head}. */
  private MtBump end(MtBump row, String branch, String head, String status) {
    Fixture.scriptForeignBranchAt(peers, branch, head);
    Fixture.scriptRun(peers, RUN, status);
    bumps.poll(row.id);
    queue.awaitIdle(Duration.ofSeconds(30));
    return store.bump(row.id).orElseThrow();
  }

  // --- planning at the fold --------------------------------------------------------------------

  /**
   * A stale INTERNAL pin at the fold is planned and dispatched; the external one is not, because a
   * person opened this request. The DERIVED screenshots WAIT and store nothing, the kinds that do
   * not apply are listed with their reason — and the payload carries the MaintenanceBump entry shape
   * and the one file it touches.
   */
  @Test
  void aStalePinAtTheFoldIsPlannedAndTheDerivedKindsWait() throws Exception {
    AutomationFixture.scriptManifests(peers, FOLD_A, STALE_POM, null);

    ReleaseRequestAutomationsDto answer = trigger(FOLD_A, null, null, ACCEPTS);

    AutomationDto bump = AutomationFixture.entry(answer, DependencyBumpAutomation.KIND);
    assertEquals(AutomationState.REQUESTED.name(), bump.state(), bump.detail());
    MtBump row = row(bump);
    assertEquals(BumpStatus.RUNNING.name(), row.status, row.message);
    assertEquals(BUMP_BRANCH, row.branch);
    assertEquals(List.of(EVENTSTREAM), names(BumpService.changes(row)), "internal only");

    AutomationDto screenshots =
        AutomationFixture.entry(answer, ScreenshotBaselinesAutomation.KIND);
    assertEquals(AutomationState.WAITING.name(), screenshots.state());
    assertTrue(screenshots.reason().contains("dependency bump"), screenshots.reason());
    assertNull(screenshots.bumpId());
    assertTrue(rows(FOLD_A, ScreenshotBaselinesAutomation.KIND).isEmpty(), "nothing stored");

    AutomationDto estate = AutomationFixture.entry(answer, EstatePinsAutomation.KIND);
    assertEquals(AutomationState.NOT_APPLICABLE.name(), estate.state());
    assertTrue(estate.reason().contains("no wrapper"), estate.reason());
    assertEquals(estate.reason(), estate.detail());

    assertEquals(1, triggers().size(), "one run: the bump, and no screenshots beside it");
    JsonNode body = JSON.readTree(triggers().getFirst());
    assertEquals(CiClient.AUTOMATION_EVENT_NAME, body.get("name").asText());
    JsonNode payload = body.get("payload");
    assertEquals(DependencyBumpAutomation.KIND, payload.get("kind").asText());
    assertEquals(BUMP_BRANCH, payload.get("branch").asText());
    assertEquals("[\"pom.xml\"]", payload.get("commitPaths").toString());
    JsonNode change = payload.get("changes").get(0);
    assertEquals(1, payload.get("changes").size());
    List<String> fields = new ArrayList<>();
    change.fieldNames().forEachRemaining(fields::add);
    assertEquals(
        List.of("ecosystem", "manifestPath", "name", "from", "to", "location"), fields,
        "exactly the MaintenanceBump changes[] entry");
    assertEquals("maven", change.get("ecosystem").asText());
    assertEquals("pom.xml", change.get("manifestPath").asText());
    assertEquals(EVENTSTREAM, change.get("name").asText());
    assertEquals("2026.811.1", change.get("from").asText());
    assertEquals("2026.821.3", change.get("to").asText());
    assertFalse(change.get("location").asText().isBlank());
  }

  // --- the changelogs (qits-893) ------------------------------------------------------------------

  /**
   * The dependency bump's commit is a bump commit like any other, so its internal change names the
   * changelogs of the releases it pulls in — the same {@code changelog} field, spelled by the same
   * code, as the MaintenanceBump trigger's — and is otherwise the entry it always was.
   */
  @Test
  void anInternalChangeCarriesItsChangelogRange() throws Exception {
    Fixture.scriptChangelogs(peers, "qits-eventstream", "2026.821.3", "2026.811.1", "2026.815.1");
    AutomationFixture.scriptManifests(peers, FOLD_A, STALE_POM, null);

    MtBump row =
        row(AutomationFixture.entry(trigger(FOLD_A, null, null, ACCEPTS),
            DependencyBumpAutomation.KIND));

    assertEquals(BumpStatus.RUNNING.name(), row.status, row.message);
    assertEquals(1, triggers().size());
    JsonNode change = JSON.readTree(triggers().getFirst()).path("payload").path("changes").get(0);
    assertEquals(EVENTSTREAM, change.get("name").asText());
    assertEquals(
        JSON.readTree(
            "{\"repository\":\"qits-eventstream\",\"versions\":[\"2026.815.1\",\"2026.821.3\"]}"),
        change.get("changelog"));
  }

  /**
   * A release between the pins that published no changelog FAILS the bump with the sentence naming
   * it, and nothing is sent to qits-ci — the same ending a group bump's missing changelog gets.
   */
  @Test
  void aMissingChangelogFailsTheBumpAndDispatchesNothing() {
    // The floor is 2026.811.1, so 2026.821.3 — the new pin — is a release that published nothing.
    Fixture.scriptChangelogs(peers, "qits-eventstream", "2026.811.1");
    AutomationFixture.scriptManifests(peers, FOLD_A, STALE_POM, null);

    MtBump row =
        row(AutomationFixture.entry(trigger(FOLD_A, null, null, ACCEPTS),
            DependencyBumpAutomation.KIND));

    assertEquals(BumpStatus.FAILED.name(), row.status, row.message);
    assertTrue(
        row.message.contains("no changelog for qits-eventstream 2026.821.3"), row.message);
    assertTrue(triggers().isEmpty(), "nothing is sent to qits-ci: " + triggers());
  }

  /** An unreadable docs store says nothing about the changelogs: the bump waits for the sweep. */
  @Test
  void anUnreadableDocsStoreLeavesTheBumpRequested() {
    peers.answer(
        PeerTarget.ARTIFACTS_DOCS,
        Fixture.CHANGELOG_PATH + "qits-eventstream",
        FakePeers.Scripted.status(503, "busy"));
    AutomationFixture.scriptManifests(peers, FOLD_A, STALE_POM, null);

    MtBump row =
        row(AutomationFixture.entry(trigger(FOLD_A, null, null, ACCEPTS),
            DependencyBumpAutomation.KIND));

    assertEquals(BumpStatus.REQUESTED.name(), row.status, row.message);
    assertEquals(BumpService.CHANGELOGS_UNREADABLE, row.message);
    assertTrue(triggers().isEmpty());
  }

  /**
   * On a MAIN-ONLY request with the upstream switch on the external upgrade rides too — and carries
   * no {@code changelog} key at all, while the internal one beside it names its range.
   */
  @Test
  void anExternalChangeCarriesNoChangelog() throws Exception {
    Fixture.scriptChangelogs(peers, "qits-eventstream", "2026.821.3", "2026.811.1");
    AutomationFixture.scriptManifests(peers, FOLD_A, STALE_POM, null);
    store.recordOpenedRequest(
        REQUEST, Fixture.REPOSITORY, "main", MtReleaseRequest.MAIN_ONLY, null, Instant.now());
    MaintenanceConfig real = UpstreamSwitch.install(config, true);
    try {
      trigger(FOLD_A, null, null, ACCEPTS);
    } finally {
      UpstreamSwitch.restore(real);
    }

    assertEquals(1, triggers().size(), triggers().toString());
    JsonNode changes = JSON.readTree(triggers().getFirst()).path("payload").path("changes");
    assertEquals(2, changes.size(), changes.toString());
    for (JsonNode change : changes) {
      if (QUARKUS_BOM.equals(change.path("name").asText())) {
        assertFalse(change.has("changelog"), "an external change names none: " + change);
      } else {
        assertEquals(EVENTSTREAM, change.path("name").asText());
        assertEquals(
            JSON.readTree("{\"repository\":\"qits-eventstream\",\"versions\":[\"2026.821.3\"]}"),
            change.get("changelog"));
      }
    }
  }

  /**
   * Without {@code accepts} the answer is exactly the old one: no NOT_APPLICABLE entry, and the
   * waiting screenshots read REQUESTED — with no row behind them.
   */
  @Test
  void withoutTheAcceptsFlagTheAnswerIsTheOldOne() {
    AutomationFixture.scriptManifests(peers, FOLD_A, STALE_POM, null);

    ReleaseRequestAutomationsDto answer = trigger(FOLD_A, null, null, null);

    assertEquals(
        List.of(DependencyBumpAutomation.KIND, ScreenshotBaselinesAutomation.KIND),
        answer.automations().stream().map(AutomationDto::kind).toList(),
        "no inapplicable kind is listed");
    AutomationDto screenshots =
        AutomationFixture.entry(answer, ScreenshotBaselinesAutomation.KIND);
    assertEquals(AutomationState.REQUESTED.name(), screenshots.state());
    assertNull(screenshots.bumpId());
    assertNull(screenshots.reason());
    assertTrue(screenshots.detail().contains("waiting"), screenshots.detail());
    assertTrue(rows(FOLD_A, ScreenshotBaselinesAutomation.KIND).isEmpty());
  }

  /**
   * EXTERNAL upgrades are not live in R1: a group bump's request (a {@code maintenance/<group>}
   * branch) gets internal pins only, and so does a MAIN-ONLY request while the upstream switch is
   * off. Only a main-only request with the switch on plans the external one too.
   */
  @Test
  void externalUpgradesArePlannedOnlyInAMainOnlyRequestWithTheSwitchOn() {
    AutomationFixture.scriptManifests(peers, FOLD_A, STALE_POM, null);

    store.recordOpenedRequest(
        REQUEST, Fixture.REPOSITORY, "maintenance/dependencies", MtReleaseRequest.GROUP_BUMP, null,
        Instant.now());
    assertEquals(List.of(EVENTSTREAM), planned(), "a group bump's request: internal only");

    store.recordOpenedRequest(
        REQUEST, Fixture.REPOSITORY, "main", MtReleaseRequest.MAIN_ONLY, null, Instant.now());
    // Shipped ON since the cutover (qits-1133 R2).
    assertEquals(
        List.of(EVENTSTREAM, QUARKUS_BOM), planned(), "main-only, switch on: external too");

    MaintenanceConfig real = UpstreamSwitch.install(config, false);
    try {
      assertEquals(List.of(EVENTSTREAM), planned(), "main-only, switch off: internal only");
    } finally {
      UpstreamSwitch.restore(real);
    }
  }

  /** What the bump plans at fold A for the request, without opening anything. */
  private List<String> planned() {
    MtRepository repository = store.repository(Fixture.REPOSITORY).orElseThrow();
    Plan plan = dependencyBump.plan(automations.subject(repository, REQUEST, FOLD_A, null, null));
    assertEquals(Plan.Kind.RUN, plan.kind(), plan.reason());
    return names(plan.runs().getFirst().changes());
  }

  /** {@code hold:} is the escape hatch: a held dependency is never planned. */
  @Test
  void aHeldDependencyIsNeverBumped() {
    AutomationFixture.scriptManifests(
        peers, FOLD_A, STALE_POM, "hold:\n  - \"eu.wohlben.qits:qits-event*\"\n");

    ReleaseRequestAutomationsDto answer = trigger(FOLD_A, null, null, ACCEPTS);

    AutomationDto bump = AutomationFixture.entry(answer, DependencyBumpAutomation.KIND);
    assertEquals(AutomationState.FRESH.name(), bump.state(), bump.detail());
    assertTrue(bump.detail().contains("held") && bump.detail().contains(EVENTSTREAM),
        bump.detail());
    assertTrue(triggers().stream().noneMatch(body -> body.contains(DependencyBumpAutomation.KIND)));
    assertEquals(
        AutomationState.REQUESTED.name(),
        AutomationFixture.entry(answer, ScreenshotBaselinesAutomation.KIND).state(),
        "the source is fresh, so the derived kinds go in the same ask");
  }

  /** {@code ignore:} takes the ecosystem off the fold altogether — and so off the plan. */
  @Test
  void anIgnoredEcosystemIsNeverBumped() {
    AutomationFixture.scriptManifests(peers, FOLD_A, STALE_POM, "ignore: [maven]\n");

    AutomationDto bump =
        AutomationFixture.entry(trigger(FOLD_A, null, null, ACCEPTS), DependencyBumpAutomation.KIND);

    assertEquals(AutomationState.FRESH.name(), bump.state(), bump.detail());
  }

  /**
   * A gitlink is a pin like any other outside a wrapper — planned, its path among the files the run
   * stages — and estate-pins' alone inside one, where this kind neither plans nor claims it.
   */
  @Test
  void aGitlinkIsBumpedOutsideAWrapperAndLeftToEstatePinsInOne() {
    String stale = "0000000000000000000000000000000000000001";
    String released = "0000000000000000000000000000000000000002";
    store.recordLatestIfNewer(
        Ecosystem.GITLINK, "qits-ci-frontend", "2026.900.1", GitlinkSha.of(released),
        Instant.now());
    AutomationFixture.scriptGitlinkFold(peers, FOLD_A, stale);
    MtRepository repository = store.repository(Fixture.REPOSITORY).orElseThrow();

    Plan plan = dependencyBump.plan(automations.subject(repository, REQUEST, FOLD_A, null, null));
    assertEquals(Plan.Kind.RUN, plan.kind(), plan.reason());
    Plan.Run run = plan.runs().getFirst();
    assertEquals(List.of("qits-ci-frontend"), names(run.changes()));
    assertEquals(List.of("webui"), run.extras().get(DependencyBumpAutomation.COMMIT_PATHS));
    assertTrue(
        dependencyBump
            .committablePaths(automations.subject(repository, REQUEST, FOLD_A, null, null))
            .contains("webui"));

    MtRepository wrapper = new MtRepository();
    wrapper.name = repository.name;
    wrapper.project = repository.project;
    wrapper.catalogId = repository.catalogId;
    wrapper.mainBranch = repository.mainBranch;
    wrapper.archetype = "PROJECT";
    AutomationSubject inWrapper = automations.subject(wrapper, REQUEST, FOLD_A, null, null);
    assertEquals(Plan.Kind.FRESH, dependencyBump.plan(inWrapper).kind());
    assertFalse(dependencyBump.committablePaths(inWrapper).contains("webui"));
  }

  // --- the ending, the join, and what the commit re-runs ---------------------------------------

  /**
   * A green run that moved the branch is joined at LOWEST; the fold its commit makes is planned
   * again and FRESH — and the screenshots are NOT carried across it, although they were COMMITTED
   * on the fold before: a SOURCE commit is a build input, never their own output.
   */
  @Test
  void theBumpJoinsAtLowestAndItsCommitReRunsTheDerivedKinds() {
    // Fold A: both pins stale only for the bump; the screenshots have been run and committed there.
    AutomationFixture.scriptManifests(peers, FOLD_A, STALE_POM, null);
    MtBump bump =
        row(AutomationFixture.entry(trigger(FOLD_A, null, null, ACCEPTS),
            DependencyBumpAutomation.KIND));
    MtBump ended = end(bump, BUMP_BRANCH, AutomationFixture.PUSHED, "SUCCESS");
    assertEquals(BumpStatus.SUCCEEDED.name(), ended.status, ended.message);
    List<String> joins = peers.bodiesFor(AutomationFixture.joinPath(REQUEST));
    assertEquals(1, joins.size());
    assertTrue(joins.getFirst().contains("\"priority\":\"LOWEST\""), joins.getFirst());
    assertTrue(joins.getFirst().contains(BUMP_BRANCH), joins.getFirst());

    // Fold B is the bump's own commit: only pom.xml changed. Pins current, so the bump is FRESH by
    // its plan, and the screenshots run on what it wrote.
    AutomationFixture.scriptManifests(peers, FOLD_B, CURRENT_POM, null);
    ReleaseRequestAutomationsDto answer = trigger(FOLD_B, FOLD_A, List.of("pom.xml"), ACCEPTS);
    assertEquals(
        AutomationState.FRESH.name(),
        AutomationFixture.entry(answer, DependencyBumpAutomation.KIND).state());
    AutomationDto screenshots =
        AutomationFixture.entry(answer, ScreenshotBaselinesAutomation.KIND);
    assertEquals(AutomationState.REQUESTED.name(), screenshots.state(), screenshots.detail());
    MtBump screenshotRow = row(screenshots);
    assertEquals(BumpStatus.RUNNING.name(), screenshotRow.status, screenshotRow.message);
    MtBump committed =
        end(screenshotRow, AutomationFixture.branch(REQUEST), AutomationFixture.PUSHED, "SUCCESS");
    assertEquals(BumpStatus.SUCCEEDED.name(), committed.status, committed.message);

    // Fold C: another pom-only change after the screenshots COMMITTED on fold B. Under the old union
    // rule a pom path was "automation output" and would carry; a SOURCE path never does.
    AutomationFixture.scriptManifests(peers, FOLD_C, CURRENT_POM, null);
    AutomationDto again =
        AutomationFixture.entry(
            trigger(FOLD_C, FOLD_B, List.of("pom.xml"), ACCEPTS),
            ScreenshotBaselinesAutomation.KIND);
    assertEquals(AutomationState.REQUESTED.name(), again.state(), again.detail());
    assertFalse(row(again).automationOnly, "a SOURCE commit is not the screenshots' own output");
  }

  /**
   * A run that found nothing to write leaves the branch where it was: FRESH, and the next ask of
   * the same fold lets the screenshots go.
   */
  @Test
  void anUnmovedBumpIsFreshAndTheDerivedKindsGoOnTheNextAsk() {
    AutomationFixture.scriptManifests(peers, FOLD_A, STALE_POM, null);
    MtBump bump =
        row(AutomationFixture.entry(trigger(FOLD_A, null, null, ACCEPTS),
            DependencyBumpAutomation.KIND));
    assertEquals(
        BumpStatus.NOTHING_TO_DO.name(),
        end(bump, BUMP_BRANCH, AutomationFixture.BEFORE, "SUCCESS").status);

    ReleaseRequestAutomationsDto answer = trigger(FOLD_A, null, null, ACCEPTS);

    assertEquals(
        AutomationState.FRESH.name(),
        AutomationFixture.entry(answer, DependencyBumpAutomation.KIND).state());
    assertEquals(
        AutomationState.REQUESTED.name(),
        AutomationFixture.entry(answer, ScreenshotBaselinesAutomation.KIND).state());
    assertTrue(peers.bodiesFor(AutomationFixture.joinPath(REQUEST)).isEmpty(), "nothing joined");
  }

  // --- inputPaths ---------------------------------------------------------------------------------

  /**
   * {@code entity-diagram} declares its inputs, so a fold that touched none of them carries its
   * outcome — a README is nobody's automation output and still costs no run — while one that
   * touched a Java source runs again.
   */
  @Test
  void entityDiagramCarriesAcrossAFoldThatTouchedNoInput() {
    String branch = AutomationFixture.branch(EntityDiagramAutomation.KIND, REQUEST);
    for (String fold : List.of(FOLD_A, FOLD_B, FOLD_C)) {
      AutomationFixture.scriptFold(peers, fold, false);
      AutomationFixture.scriptEntityDiagramApplies(peers, fold);
    }
    Fixture.scriptForeignBranchAt(peers, branch, AutomationFixture.BEFORE);
    MtBump diagram =
        row(AutomationFixture.entry(trigger(FOLD_A, null, null, ACCEPTS),
            EntityDiagramAutomation.KIND));
    assertEquals(
        BumpStatus.SUCCEEDED.name(),
        end(diagram, branch, AutomationFixture.PUSHED, "SUCCESS").status);
    int before = triggers().size();

    AutomationDto carried =
        AutomationFixture.entry(
            trigger(FOLD_B, FOLD_A, List.of("README.md"), ACCEPTS), EntityDiagramAutomation.KIND);
    assertEquals(AutomationState.FRESH.name(), carried.state(), carried.detail());
    assertTrue(carried.detail().contains("carried"), carried.detail());
    assertEquals(before, triggers().size(), "no run for a fold that touched no input");

    AutomationDto rerun =
        AutomationFixture.entry(
            trigger(FOLD_C, FOLD_B, List.of("ci/src/main/java/Foo.java"), ACCEPTS),
            EntityDiagramAutomation.KIND);
    assertEquals(AutomationState.REQUESTED.name(), rerun.state(), rerun.detail());
  }

  // --- the runtime disjointness check -------------------------------------------------------

  /** A plan naming a path another applicable kind commits is refused, with the sentence. */
  @Test
  void aPlanThatWritesAnotherKindsPathIsRefused() {
    Plan plan =
        Plan.runs(
            List.of(
                new Plan.Run(
                    null,
                    List.of(),
                    java.util.Map.of(DependencyBumpAutomation.COMMIT_PATHS, List.of("webui")))));
    java.util.Map<String, List<String>> paths = new java.util.LinkedHashMap<>();
    paths.put(DependencyBumpAutomation.KIND, DependencyBumpAutomation.MANIFESTS);
    paths.put(EstatePinsAutomation.KIND, List.of("webui"));

    String clash = AutomationService.clash(dependencyBump, plan, paths);

    assertNotNull(clash);
    assertTrue(clash.contains("webui") && clash.contains(EstatePinsAutomation.KIND), clash);
    paths.remove(EstatePinsAutomation.KIND);
    assertNull(AutomationService.clash(dependencyBump, plan, paths), "its own paths never clash");
  }
}
