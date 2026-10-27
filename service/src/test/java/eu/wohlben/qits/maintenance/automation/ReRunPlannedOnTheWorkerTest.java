package eu.wohlben.qits.maintenance.automation;

import static eu.wohlben.qits.maintenance.automation.AutomationFixture.EVENTSTREAM;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.FOLD_A;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.REQUEST;
import static eu.wohlben.qits.maintenance.automation.AutomationFixture.STALE_POM;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.bump.BumpService;
import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.manifest.GroupConfig;
import eu.wohlben.qits.maintenance.model.BumpStatus;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.pending.Change;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.scan.ScanService;
import eu.wohlben.qits.maintenance.scan.ScanTrigger;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The re-run door answers inside qits-projects' budget</b> (qits-1687). The door used to read
 * the kind's plan on the request thread — whole-repository scans on the git host — and on a slow
 * git host answered after qits-projects' ten seconds had run out, with the run already opened. Now
 * it opens one row and the worker plans it; what the door answers synchronously — the 409s — it
 * still answers, and what the plan decides still reaches qits-ci.
 */
@QuarkusTest
class ReRunPlannedOnTheWorkerTest {

  private static final String RUN = "run-rerun-on-the-worker";

  private static final String RERUN =
      "/maintenance/api/release-requests/" + REQUEST + "/automations/"
          + DependencyBumpAutomation.KIND + "/runs";

  private static final String BODY =
      "{\"repository\":\"" + Fixture.REPOSITORY + "\",\"workItem\":\"qits-1687\"}";

  /** The first git-host read the dependency bump's plan makes: the repository's own config. */
  private static final String PLAN_READ =
      "/git/" + Fixture.PROJECT + "/" + Fixture.REPOSITORY + "/blob/" + FOLD_A + "/"
          + GroupConfig.PATH;

  private static final String BUMP_BRANCH =
      AutomationFixture.branch(DependencyBumpAutomation.KIND, REQUEST);

  /** How long the slow git host takes to answer the plan's first read. */
  private static final long SLOW_MS = 10_000;

  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject ScanService scans;

  @Inject MaintenanceStore store;

  @Inject FakePeers peers;

  @Inject InventoryReset inventory;

  @Inject WorkQueue queue;

  @BeforeEach
  void scriptThePeers() {
    queue.awaitIdle(Duration.ofSeconds(60));
    inventory.clear();
    peers.reset();
    Fixture.scriptScan(peers);
    Fixture.scriptBranchAbsent(peers);
    Fixture.scriptCiAccepts(peers, RUN);
    Fixture.seedProducers(store);
    AutomationFixture.scriptFold(peers, FOLD_A, true);
    AutomationFixture.scriptManifests(peers, FOLD_A, STALE_POM, null);
    AutomationFixture.scriptRequest(peers, REQUEST, "PENDING", FOLD_A);
    Fixture.scriptForeignBranchAt(peers, BUMP_BRANCH, AutomationFixture.BEFORE);
    scans.request(ScanScope.ALL, null, ScanTrigger.MANUAL);
    queue.awaitIdle(Duration.ofSeconds(60));
  }

  private static io.restassured.specification.RequestSpecification rerun(String body) {
    return given().contentType(ContentType.JSON).body(body);
  }

  private List<MtBump> rows() {
    return store.automations(REQUEST, FOLD_A).stream()
        .filter(row -> DependencyBumpAutomation.KIND.equals(row.automationKind))
        .toList();
  }

  /**
   * With the git host taking ten seconds over the plan's first read, the door still answers 202 in
   * well under five; the row it opened is REQUESTED and unplanned, a second press meets the 409
   * meanwhile, and once the git host answers, the worker plans the row and sends it.
   */
  @Test
  void theDoorAnswersInsideTheBudgetWhileTheGitHostIsSlow() throws Exception {
    AtomicBoolean slowed = new AtomicBoolean();
    peers.onCall(
        PeerTarget.GITHOST,
        PLAN_READ,
        () -> {
          if (slowed.compareAndSet(false, true)) {
            try {
              Thread.sleep(SLOW_MS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        });

    long start = System.nanoTime();
    String id =
        rerun(BODY)
            .when()
            .post(RERUN)
            .then()
            .statusCode(202)
            .body("id", notNullValue())
            .extract()
            .path("id");
    long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    assertTrue(took < 5_000, "the door answered inside its budget: " + took + " ms");

    MtBump opened = store.bump(UUID.fromString(id)).orElseThrow();
    assertEquals(BumpStatus.REQUESTED.name(), opened.status, opened.message);
    assertEquals("MANUAL", opened.trigger);
    assertEquals("qits-1687", opened.workItem);
    assertNull(opened.changes, "not planned yet: the plan is the worker's");
    assertEquals(AutomationService.PLANNING, opened.message);

    // A second press while the first is being planned is the "one is already active" 409.
    rerun("{}").when().post(RERUN).then().statusCode(409);
    assertEquals(1, rows().size(), "two presses, one row");

    assertTrue(queue.awaitIdle(Duration.ofSeconds(60)), "the worker finished the plan");
    long all = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    assertTrue(slowed.get(), "the plan read the git host");
    assertTrue(all >= SLOW_MS, "the slow read was the worker's, not the door's: " + all + " ms");

    MtBump sent = store.bump(UUID.fromString(id)).orElseThrow();
    assertEquals(BumpStatus.RUNNING.name(), sent.status, sent.message);
    assertEquals(BUMP_BRANCH, sent.branch);
    assertEquals(
        List.of(EVENTSTREAM),
        BumpService.changes(sent).stream().map(Change::name).toList(),
        "the plan's changes, written onto the row");
    assertEquals(1, peers.bodiesFor(CiClient.TRIGGER_PATH).size());
  }

  /**
   * <b>A RUN plan reaches qits-ci as it always did</b>: the row the door opened carries the plan's
   * {@code commitPaths} and its MaintenanceBump changes once the worker has planned it, and no
   * "planning" sentence is left on it.
   */
  @Test
  void aRunPlanStillSendsItsCommitPathsAndChanges() throws Exception {
    String id = rerun(BODY).when().post(RERUN).then().statusCode(202).extract().path("id");
    queue.awaitIdle(Duration.ofSeconds(60));

    MtBump sent = store.bump(UUID.fromString(id)).orElseThrow();
    assertEquals(BumpStatus.RUNNING.name(), sent.status, sent.message);
    assertNull(sent.message, "the planning sentence is cleared by the plan");
    assertEquals(1, rows().size(), "one run, on the row the door opened");

    List<String> triggers = peers.bodiesFor(CiClient.TRIGGER_PATH);
    assertEquals(1, triggers.size());
    JsonNode body = JSON.readTree(triggers.getFirst());
    assertEquals(CiClient.AUTOMATION_EVENT_NAME, body.get("name").asText());
    JsonNode payload = body.get("payload");
    assertEquals(DependencyBumpAutomation.KIND, payload.get("kind").asText());
    assertEquals(BUMP_BRANCH, payload.get("branch").asText());
    assertEquals("[\"pom.xml\"]", payload.get("commitPaths").toString());
    assertEquals(1, payload.get("changes").size());
    JsonNode change = payload.get("changes").get(0);
    assertEquals(EVENTSTREAM, change.get("name").asText());
    assertEquals("2026.811.1", change.get("from").asText());
    assertEquals("2026.821.3", change.get("to").asText());
  }

  /** A second re-run while the first is still active is a 409, and opens nothing. */
  @Test
  void aSecondReRunWhileOneIsActiveIs409() {
    rerun(BODY).when().post(RERUN).then().statusCode(202);
    rerun(BODY).when().post(RERUN).then().statusCode(409);
    queue.awaitIdle(Duration.ofSeconds(60));
    rerun(BODY).when().post(RERUN).then().statusCode(409);
    assertEquals(1, rows().size());
  }

  /** A settled request is still the synchronous 409, with no row opened and no plan read. */
  @Test
  void aFinalizedRequestIs409() {
    AutomationFixture.scriptRequest(peers, REQUEST, "FINALIZED", FOLD_A);

    rerun(BODY).when().post(RERUN).then().statusCode(409);
    queue.awaitIdle(Duration.ofSeconds(60));

    assertTrue(rows().isEmpty(), "nothing opened");
    assertTrue(!peers.called(PeerTarget.GITHOST, PLAN_READ), "nothing planned");
    assertTrue(peers.bodiesFor(CiClient.TRIGGER_PATH).isEmpty());
  }
}
