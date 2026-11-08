package eu.wohlben.qits.maintenance.automation;

import static eu.wohlben.qits.maintenance.automation.AutomationFixture.FOLD_A;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.FOLD_B;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.REQUEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.dto.ReleaseRequestAutomationsDto.AutomationDto;
import eu.wohlben.qits.maintenance.githost.GitHostReader;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import eu.wohlben.qits.maintenance.scan.ScanService;
import eu.wohlben.qits.maintenance.scan.ScanTrigger;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>{@code golden-recordings-npm}'s carry-over, through the engine</b> (qits-1957): after the kind
 * was FRESH on the previous fold, a fold that changed only {@code src/testing/browser/routes.json}
 * — what a {@code screenshot-baselines} join commits — is planned again and re-records, where it
 * used to carry over and ship stale pacts (qits-landing-app-rr-182); a fold that changed only the
 * screenshot references still carries, unread.
 */
@QuarkusTest
class GoldenRecordingsCarryOverTest {

  private static final String RUN = "run-golden-recordings";

  private static final String TREE = "/git/" + Fixture.PROJECT + "/" + Fixture.REPOSITORY + "/tree/";

  private static final String BLOB = "/git/" + Fixture.PROJECT + "/" + Fixture.REPOSITORY + "/blob/";

  /** An npm app pinning a provider's golden masters, with no browser tests of its own. */
  private static final String PACKAGE_JSON =
      "{\"name\":\"app\",\"devDependencies\":{\"@qits/projects-golden-masters\":\"2026.1022.1\"}}";

  private static final String ROUTES = "src/testing/browser/routes.json";

  private static final String SPEC = "src/app/x/x.store.pact.spec.ts";

  @Inject AutomationService automations;

  @Inject ScanService scans;

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
    Fixture.scriptCiAccepts(peers, RUN);
    for (String fold : List.of(FOLD_A, FOLD_B)) {
      AutomationFixture.scriptFold(peers, fold, false);
      peers.answer(
          PeerTarget.GITHOST,
          BLOB + fold + "/package.json",
          FakePeers.Scripted.ok(PACKAGE_JSON, Map.of("Git-Commit-Sha", fold)));
    }
    AutomationFixture.scriptRequest(peers, REQUEST, "PENDING", FOLD_A);
    AutomationFixture.scriptJoin(peers, REQUEST, AutomationFixture.joined(REQUEST));
    Fixture.scriptForeignBranchAt(
        peers,
        AutomationFixture.branch(GoldenRecordingsNpmAutomation.KIND, REQUEST),
        AutomationFixture.BEFORE);
    // main and fold A hold the same sources; what fold B holds is each test's.
    wholeTree("main", "main", "1111111111111111111111111111111111111111");
    wholeTree(FOLD_A, FOLD_A, "1111111111111111111111111111111111111111");
    scans.request(ScanScope.ALL, null, ScanTrigger.MANUAL);
    queue.awaitIdle(Duration.ofSeconds(60));
  }

  /** A recursive tree at {@code revision}: a pact spec, and the route ledger at {@code routes}. */
  private void wholeTree(String revision, String sha, String routes) {
    List<String> entries = new ArrayList<>();
    entries.add(blob(SPEC, "2222222222222222222222222222222222222222"));
    entries.add(blob(ROUTES, routes));
    entries.add(blob("src/app/x/__screenshots__/a.png", "3333333333333333333333333333333333333333"));
    peers.answer(
        PeerTarget.GITHOST,
        TREE + revision + GitHostReader.RECURSIVE_QUERY,
        FakePeers.Scripted.ok(
            "{\"recursive\":true,\"entries\":[" + String.join(",", entries) + "]}",
            Map.of("Git-Commit-Sha", sha)));
  }

  private static String blob(String path, String sha) {
    String name = path.substring(path.lastIndexOf('/') + 1);
    return "{\"name\":\"" + name + "\",\"path\":\"" + path + "\",\"type\":\"blob\","
        + "\"mode\":\"100644\",\"sha\":\"" + sha + "\"}";
  }

  private AutomationDto golden(String fold, String previous, List<String> changed) {
    AutomationDto entry =
        AutomationFixture.entry(
            automations.trigger(
                REQUEST,
                new AutomationService.Fold(
                    Fixture.REPOSITORY, fold, previous, changed, List.of("main", "work"), null)),
            GoldenRecordingsNpmAutomation.KIND);
    queue.awaitIdle(Duration.ofSeconds(30));
    return entry;
  }

  private long wholeTreeReads(String revision) {
    String tail = TREE + revision + GitHostReader.RECURSIVE_QUERY;
    return peers.urls().stream().filter(url -> url.endsWith(tail)).count();
  }

  private void freshOnFoldA() {
    AutomationDto first = golden(FOLD_A, null, null);
    assertEquals(AutomationState.FRESH.name(), first.state(), first.detail());
    assertTrue(first.detail().contains("every source"), first.detail());
    assertEquals(0, peers.bodiesFor(CiClient.TRIGGER_PATH).size(), "no run on fold A");
  }

  /** The screenshot-baselines join moved the route ledger: planned again, and re-recorded. */
  @Test
  void aFoldThatChangedOnlyTheRouteLedgerIsPlannedAgainNotCarried() {
    freshOnFoldA();
    wholeTree(FOLD_B, FOLD_B, "4444444444444444444444444444444444444444");

    AutomationDto next = golden(FOLD_B, FOLD_A, List.of(ROUTES));

    assertFalse(next.detail() != null && next.detail().contains("carried"), next.detail());
    assertEquals(AutomationState.REQUESTED.name(), next.state(), next.detail());
    assertEquals(1, wholeTreeReads(FOLD_B), "planned: fold B's tree was read");
    assertEquals(1, peers.bodiesFor(CiClient.TRIGGER_PATH).size(), "and re-recorded");
  }

  /** A join that moved only screenshot references touched no recording input: it carries. */
  @Test
  void aFoldThatChangedOnlyScreenshotReferencesCarries() {
    freshOnFoldA();
    wholeTree(FOLD_B, FOLD_B, "1111111111111111111111111111111111111111");

    AutomationDto next = golden(FOLD_B, FOLD_A, List.of("src/app/x/__screenshots__/a.png"));

    assertEquals(AutomationState.FRESH.name(), next.state(), next.detail());
    assertTrue(next.detail().contains("carried"), next.detail());
    assertEquals(0, wholeTreeReads(FOLD_B), "carried, so nothing was planned");
    assertEquals(0, peers.bodiesFor(CiClient.TRIGGER_PATH).size(), "and nothing run");
  }
}
