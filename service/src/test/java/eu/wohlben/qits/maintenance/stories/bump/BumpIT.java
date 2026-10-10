package eu.wohlben.qits.maintenance.stories.bump;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.stories.inventory.InventoryIT;
import eu.wohlben.qits.maintenance.stories.support.StoryCatalog;
import eu.wohlben.qits.maintenance.stories.support.StoryIdentities;
import eu.wohlben.qits.maintenance.stories.support.StoryNetwork;
import eu.wohlben.qits.maintenance.stories.support.StoryPeers;
import eu.wohlben.qits.maintenance.stories.support.StoryProfile;
import eu.wohlben.qits.maintenance.stories.support.StoryTarget;
import eu.wohlben.qits.maintenance.stories.support.StoryWaits;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.Network;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.UserflowRunsAfter;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.Slugs;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * <b>The two things this service makes happen anywhere else</b> — and the shape of them is the whole
 * design: <i>this service DECIDES; a CI step APPLIES; the platform RELEASES.</i>
 *
 * <p>Nothing here clones a repository, edits a file or pushes a ref. A bump is a payload naming a
 * file, a location and two versions, handed to qits-ci as a {@code MaintenanceBump} trigger, and
 * the step that reads it is the only thing that touches anybody's tree. When the branch has moved,
 * this service opens a release REQUEST for it in qits-projects — nothing merges and nothing is
 * released at that call either; the quality gates settle the request and Auto Release tags it
 * afterwards. That is why the interesting
 * evidence is <b>what the two peers were handed</b>, read back off the wire rather than out of this
 * service's own row.
 *
 * <p><b>The two stories are the two endings a green run can have</b>, and telling them apart is a
 * rule nobody would guess:
 *
 * <ul>
 *   <li><b>the branch moved</b>: SUCCEEDED. The step found the versions and wrote them. Along the
 *       way a second request for the same group is refused 409 — two runs writing one branch would
 *       make the second a non-ff rejection at best.
 *   <li><b>the branch did not</b>: NOTHING_TO_DO, which is a real outcome and reads very differently
 *       from SUCCEEDED in a list of nightly bumps. Only the HEAD is compared, never a commit count:
 *       one bump is up to two commits, because the maven step and the node/docker step each clone,
 *       commit and push.
 * </ul>
 *
 * <p><b>The two stories use different repositories, and that is the namespacing this catalogue runs
 * on.</b> A bump holds its (repository, group) lock until it ends, so two stories sharing one would
 * have the second answered 409 by the first's leftovers — the hazard {@code InventoryReset} solves
 * for the surefire suite, which a launched process has no equivalent of.
 *
 * <p><b>What closes a bump is the sweep, and nothing else.</b> {@code BumpService.dispatch} sends
 * the trigger and returns with the row RUNNING; the poll that reads the ci run and writes the
 * verdict is only ever called from {@code BumpPollSchedule}. {@link StoryProfile} therefore leaves
 * the scheduler on and silences every other timer at its own key, so the only background work that
 * can draw an arrow into a diagram is work these two stories are waiting for.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class BumpIT {

  static final String CATEGORY = "the bump";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String PUSHED = "An operator asks for a group's upgrades and qits-ci pushes the branch";

  static final String PUSHED_SLUG = Slugs.slug(PUSHED);

  static final String UNMOVED = "A green run that moved nothing is not a success";

  static final String UNMOVED_SLUG = Slugs.slug(UNMOVED);

  static final String REBUILT =
      "A branch is cut from the release that has not reached main, and rebuilt when it lacks it";

  static final String REBUILT_SLUG = Slugs.slug(REBUILT);

  static final String UNDECIDED = "A base nobody could answer for is main, and the bump goes anyway";

  static final String UNDECIDED_SLUG = Slugs.slug(UNDECIDED);

  /**
   * The ids the two stories generated, kept so {@code @AfterAll} can pin that neither reached the
   * published bundle. A row id is a per-run value by definition: a note carrying one would move the
   * story's own hash on every build, and the only symptom is a hash that never settles.
   */
  private static String pushedBumpId;

  private static String unmovedBumpId;

  private static String rebuiltBumpId;

  private static String undecidedBumpId;

  @BeforeAll
  static void tapEverySideOfThisService() {
    StoryNetwork.install();
  }

  @UserStory(value = PUSHED, category = CATEGORY)
  @UserStoryDescription(
      """
      An operator has read what is out of date and presses Bump on one group. What this service
      does with that is compose a payload — the group's pending changes, each naming a manifest, a
      location and the two versions — and hand it to qits-ci under the bump row's own id, which is
      the dedupe key that makes a retry record no second run. It answers 202 at once, because
      applying the changes is a CI run in somebody else's pipeline: a clone, an edit, a push.
      Before the trigger, it asks qits-artifacts' docs store which releases of each internal
      dependency's source repository published a changelog: every release the bump pulls in must
      have one — a missing one fails the bump rather than writing a commit without it — and the
      ones it pulls in are named on their change, for the step to compose the commit message from.
      A repository whose releases all predate changelogs has none to name, and is listed as before.
      It
      reads the branch's head before the trigger and again when the run ends, and only the head,
      never a commit count — one bump is up to two commits. While the run is going, a second
      request for the same group is refused: two runs writing one branch would make the second a
      non-ff rejection at best. When the run passes and the branch has moved, the row ends
      SUCCEEDED and carries the changes it sent, which is the audit trail — not what is pending
      now, which by then is a different question — and then it hands the branch on, opening a
      release request for it in qits-projects rather than leaving the branch for somebody to
      notice. Nothing is released at that call: the quality gates settle the request and Auto
      Release tags it afterwards, which is where this service's part ends.
      """)
  @UserflowRunsAfter(InventoryIT.class)
  @Order(1)
  void aBumpIsAPayloadForSomebodyElsesPipeline(Interactions story, Network network) {
    NetworkCapture.actor(StoryIdentities.OPERATOR);
    StoryPeers ci = StoryPeers.attach(StoryTarget.CI);
    StoryPeers githost = StoryPeers.attach(StoryTarget.GITHOST);
    StoryPeers projects = StoryPeers.attach(StoryTarget.PROJECTS);
    StoryPeers artifacts = StoryPeers.attach(StoryTarget.ARTIFACTS);

    // WHO PUBLISHES EACH INTERNAL PIN, and which of them published changelogs (qits-893). The
    // eventstream's oldest changelog is the pin's own version, so the one release this bump pulls
    // in is the new pin, and it published one. The other four repositories answer 404: every
    // release of theirs predates changelogs, which is not a failure.
    StoryCatalog.seedProducers();
    artifacts.json(
        StoryCatalog.changelogPath(StoryCatalog.SECOND_REPOSITORY),
        "{\"name\":\"@changelog/" + StoryCatalog.SECOND_REPOSITORY + "\",\"versions\":["
            + "{\"version\":\"2026.821.3\",\"publishedAt\":\"2026-08-21T01:00:00Z\"},"
            + "{\"version\":\"2026.811.1\",\"publishedAt\":\"2026-08-11T01:00:00Z\"}]}");

    // THE RELEASE ASK, armed before anything is triggered. The sweep closes the bump and makes the
    // ask on the same worker task, so a route armed after the run went green would be a race the
    // story would lose about one time in ten. The answer is WRAPPED, as the controller sends it:
    // a client reading a flat body would find no id and record a convergence.
    projects.json(
        StoryCatalog.RELEASE_REQUESTS_PATH,
        "{\"request\":{\"id\":\"" + StoryCatalog.RELEASE_REQUEST + "\",\"repoId\":\""
            + StoryCatalog.CATALOG_ID + "\",\"state\":\"PENDING\",\"backingBranch\":\"release/"
            + StoryCatalog.RELEASE_REQUEST + "\",\"mergedSha\":null,\"detail\":null}}");
    // …and the LISTING on the same path, which is the other read: no release of this repository is
    // waiting to reach main, so the branch is cut from main exactly as every bump always was.
    projects.jsonFor("GET", StoryCatalog.RELEASE_REQUESTS_PATH, "{\"requests\":[]}");

    // qits-ci accepts the trigger and names one run, which is still going. The branch is armed
    // NOWHERE: an unregistered path is a 404, which is exactly what the git host says about a
    // branch nobody has pushed — and that 404 is what the head after the run is compared against.
    ci.json(
        StoryCatalog.TRIGGER_PATH,
        "{\"eventId\":\"e-pushed\",\"runIds\":[\"" + StoryCatalog.RUN + "\"],"
            + "\"repositoriesRead\":1,\"repositoriesSkipped\":[]}");
    ci.json(
        StoryCatalog.runPath(StoryCatalog.RUN),
        "{\"id\":\"" + StoryCatalog.RUN + "\",\"status\":\"RUNNING\"}");

    String bumps =
        StoryTarget.REPOSITORIES
            + "/"
            + StoryCatalog.REPOSITORY
            + "/groups/"
            + StoryCatalog.DEFAULT_GROUP
            + "/bumps";
    pushedBumpId =
        StoryIdentities.operator(given())
            .contentType(ContentType.JSON)
            .post(bumps)
            .then()
            .statusCode(202)
            .contentType(ContentType.JSON)
            .extract()
            .path("id");
    story
        .note("Bump answers 202 with the id of a row, and does not wait: what applies the changes"
            + " is a CI run in somebody else's pipeline")
        .as("bump-accepted");

    StoryWaits.bumpReaches(pushedBumpId, "RUNNING");

    // --- WHAT qits-ci WAS REALLY HANDED ---------------------------------------------------------
    //
    // Read off the wire rather than out of this service's own row, because "the payload is right"
    // is a claim about what left this process. The event id IS the bump row id: qits-ci dedupes on
    // (event id, repository, config path), so a dispatch whose answer was lost records no second
    // run when the sweep sends it again.
    List<String> triggers = ci.bodiesFor(StoryCatalog.TRIGGER_PATH);
    assertEquals(1, triggers.size(), "the bump must have been triggered exactly once");
    String payload = triggers.getFirst();
    assertTrue(
        payload.contains("\"name\":\"" + CiClient.EVENT_NAME + "\""),
        "the trigger must name the event the platform pipeline selects on: " + payload);
    assertTrue(
        payload.contains("\"eventId\":\"" + pushedBumpId + "\""),
        "the event id must BE the bump row id, which is qits-ci's dedupe key: " + payload);
    assertTrue(
        payload.contains("\"repository\":\"" + StoryCatalog.REPOSITORY + "\""),
        "the payload names the repository by its public name: " + payload);
    assertTrue(
        payload.contains("\"branch\":\"" + StoryCatalog.BRANCH + "\"")
            && payload.contains("\"baseRef\":\"main\""),
        "a group's name IS its branch, cut from the repository's main branch: " + payload);
    assertFalse(
        payload.contains("replaceHead"),
        "a branch cut from main is continued or started, never rebuilt: " + payload);
    // One change, in full, because the shape is the contract three repositories build against.
    assertTrue(
        payload.contains("\"location\":\"property:qits.eventstream.version\"")
            && payload.contains("\"from\":\"2026.811.1\"")
            && payload.contains("\"to\":\"2026.821.3\"")
            && payload.contains("\"manifestPath\":\"pom.xml\""),
        "a change names a file, a location and two versions: " + payload);
    // AND THE CHANGELOGS IT PULLS IN, named and never carried: the step fetches the texts itself.
    assertTrue(
        payload.contains(
            "\"changelog\":{\"repository\":\"" + StoryCatalog.SECOND_REPOSITORY
                + "\",\"versions\":[\"2026.821.3\"]}"),
        "the eventstream change names the one changelog it pulls in: " + payload);
    story
        .note("each internal change names the changelogs of the releases it pulls in — every"
            + " release after the old pin up to the new one, each proved published before the"
            + " trigger — and an external one, or one whose repository predates changelogs, names"
            + " none")
        .as("the-changelogs-it-pulls-in");
    // AND THE GROUPING IS HONOURED ON THE WIRE. @angular/core is pending too, and it belongs to the
    // group this repository declared for it — a payload carrying it would put a change on a branch
    // its author configured against.
    assertFalse(
        payload.contains("@angular/core"),
        "a change may only travel on the branch its own group names: " + payload);
    story
        .note("what qits-ci was handed is a list of edits — a file, a location and two versions"
            + " each — under the bump row's own id, which is the dedupe key that makes a retry"
            + " record no second run")
        .as("the-payload-is-the-decision");
    story
        .note("and the grouping is honoured on the wire: the pins this repository configured onto"
            + " another branch are not in this payload")
        .as("groups-are-branches");

    // --- ONE BRANCH, ONE WRITER -----------------------------------------------------------------
    StoryIdentities.operator(given())
        .contentType(ContentType.JSON)
        .post(bumps)
        .then()
        .statusCode(409)
        .contentType(ContentType.JSON)
        .body("message", not(nullValue()));
    story
        .note("a second request for the same group while one is going is refused 409 — two runs"
            + " writing one branch would make the second a non-ff rejection at best")
        .as("one-branch-one-writer");

    // --- THE RUN ENDS, AND THE BRANCH MOVED -----------------------------------------------------
    githost.json(
        StoryCatalog.tree(StoryCatalog.REPOSITORY, StoryCatalog.BRANCH),
        "{\"entries\":[]}",
        Map.of("Git-Commit-Sha", StoryCatalog.BUMPED_SHA));
    ci.json(
        StoryCatalog.runPath(StoryCatalog.RUN),
        "{\"id\":\"" + StoryCatalog.RUN + "\",\"status\":\"SUCCESS\"}");
    assertEquals("SUCCEEDED", StoryWaits.bump(pushedBumpId), "a green run on a moved branch is a success");

    StoryIdentities.operator(given())
        .get(StoryTarget.BUMPS + "/" + pushedBumpId)
        .then()
        .statusCode(200)
        .body("repository", equalTo(StoryCatalog.REPOSITORY))
        .body("group", equalTo(StoryCatalog.DEFAULT_GROUP))
        .body("branch", equalTo(StoryCatalog.BRANCH))
        .body("trigger", equalTo("MANUAL"))
        .body("status", equalTo("SUCCEEDED"))
        .body("ciRunStatus", equalTo("SUCCESS"))
        .body("ciRunIds", equalTo(List.of(StoryCatalog.RUN)))
        // Every bump row records WHICH environment's ci ran it, so a second one would be a config
        // entry rather than a schema change.
        .body("environment", not(nullValue()))
        // The pipeline file in the wrapper that answers MaintenanceBump. The same for every bump,
        // so the row carries it as a constant rather than reading it back per run.
        .body("configPath", equalTo(CiClient.CONFIG_PATH))
        // THE AUDIT TRAIL: what was SENT, not what is pending now. By the time anyone reads a bump
        // the pins have moved and the latest versions have moved again.
        .body("changes.size()", greaterThan(0))
        .body("changes.name", hasItem("eu.wohlben.qits:qits-eventstream"))
        .body("changes.name", not(hasItem("@angular/core")))
        .body("finishedAt", not(nullValue()))
        .body("message", containsString(StoryCatalog.BRANCH))
        // AND THE BRANCH WAS HANDED ON. A branch nobody asks about is a branch that sits there.
        .body("releaseRequestId", equalTo(StoryCatalog.RELEASE_REQUEST))
        // AND WHAT IT WAS CUT FROM, which is main when nothing released is still waiting for it.
        .body("baseRef", equalTo("main"))
        .body("replaceHead", nullValue());
    story
        .note("the run passed and the branch moved, so the row ends SUCCEEDED — with the changes it"
            + " SENT, which is the audit trail: what is pending now is a different question")
        .as("the-branch-was-pushed");

    // --- AND THE ASK THAT FOLLOWS IT ------------------------------------------------------------
    //
    // Read off the wire for the same reason the trigger is: "the branch was handed on" is a claim
    // about what left this process. Nothing merged and nothing released at this call — a release
    // request is OPENED, and the quality gates settle it afterwards — so what this proves is that
    // the branch stopped being this service's problem, not that it was released.
    List<String> asks = projects.bodiesFor(StoryCatalog.RELEASE_REQUESTS_PATH);
    assertEquals(1, asks.size(), "the release must have been asked for exactly once");
    String ask = asks.getFirst();
    assertTrue(
        ask.contains("\"branch\":\"" + StoryCatalog.BRANCH + "\""),
        "the ask names the branch this bump pushed: " + ask);
    assertTrue(
        ask.contains("\"summary\":\"bump(" + StoryCatalog.DEFAULT_GROUP + "): "),
        "the summary is the shape the bump's own commits carry: " + ask);
    // AND IT ASKS FOR THE BACK OF THE QUEUE. Every bump is LOWEST, unconditionally — nobody is
    // waiting on a dependency bump, so it yields to every release a person asked for.
    assertTrue(
        ask.contains("\"priority\":\"LOWEST\""),
        "a bump's release request is always the lowest priority one: " + ask);
    // AND THE REPOSITORY IS IN THE PATH, not the body: qits-projects addresses a repository by its
    // own catalog row id, which is the one thing that route resolves.
    assertTrue(
        StoryCatalog.RELEASE_REQUESTS_PATH.contains(StoryCatalog.CATALOG_ID),
        "the ask is addressed by the catalog id");
    story
        .note("and then this service opens a release request for that branch in qits-projects — at"
            + " the LOWEST priority, always, because nobody waits on a dependency bump — which is"
            + " where its part ends: the gates settle the request and Auto Release tags it, and"
            + " nothing here waits for either")
        .as("the-branch-is-handed-on");

    StoryIdentities.operator(given())
        .queryParam("repository", StoryCatalog.REPOSITORY)
        .get(StoryTarget.BUMPS)
        .then()
        .statusCode(200)
        .body("size()", greaterThan(0))
        .body("[0].status", equalTo("SUCCEEDED"))
        .body("[0].changes.size()", greaterThan(0));
    story
        .note("and it is in the log an operator reads afterwards, with its change list beside it")
        .as("the-bump-log");

    network.declare(
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "the changes are frozen onto the bump row at REQUEST time and never recomputed");
  }

  @UserStory(value = UNMOVED, category = CATEGORY)
  @UserStoryDescription(
      """
      The ending nobody designs for and everybody gets. qits-ci ran the pipeline, every step
      passed, and the branch is exactly where it was — because the step read the files and found
      the versions already there. That happens whenever the pins moved between the scan that
      computed the changes and the run that applied them, or another bump got there first, and it
      is not a success: reporting SUCCEEDED would put a branch in a nightly list that nobody ever
      pushed. So the head is read before the trigger and again when the run ends, only the head is
      compared — one bump is up to two commits, so a service expecting one would report every
      mixed group as broken — and a green run over an unmoved branch ends NOTHING_TO_DO.

      AND THE RELEASE IS STILL ASKED FOR, because "this run pushed nothing" is not "this branch has
      nothing unreleased". The branch here exists at a commit that is not main's: something put it
      there, earlier, and until somebody releases it the repository sits on an old dependency while
      every surface agrees there is nothing to do. That is not a hypothetical — qits-mirror-platform-service
      spent 2026-09-16 exactly like this, the dispatch window reporting "its branch is pushed and
      it waits on its own release" while the ending that would have asked for one had decided there
      was nothing to release. So the STATUS follows the run and the ASK follows the branch: ahead of
      main is asked for, level with main is not.
      """)
  @UserflowRunsAfter(InventoryIT.class)
  @Order(2)
  void aGreenRunOverAnUnmovedBranchIsNotASuccess(Interactions story, Network network) {
    NetworkCapture.actor(StoryIdentities.OPERATOR);
    StoryPeers ci = StoryPeers.attach(StoryTarget.CI);
    StoryPeers githost = StoryPeers.attach(StoryTarget.GITHOST);

    // The branch already exists and stays exactly where it is, before the run and after it. It is
    // the EXTERNAL half's branch: this repository pins one dependency and it is somebody else's.
    githost.json(
        StoryCatalog.tree(StoryCatalog.SECOND_REPOSITORY, StoryCatalog.EXTERNAL_BRANCH),
        "{\"entries\":[]}",
        Map.of("Git-Commit-Sha", StoryCatalog.UNMOVED_SHA));
    ci.json(
        StoryCatalog.TRIGGER_PATH,
        "{\"eventId\":\"e-unmoved\",\"runIds\":[\"" + StoryCatalog.SECOND_RUN + "\"],"
            + "\"repositoriesRead\":1,\"repositoriesSkipped\":[]}");
    ci.json(
        StoryCatalog.runPath(StoryCatalog.SECOND_RUN),
        "{\"id\":\"" + StoryCatalog.SECOND_RUN + "\",\"status\":\"SUCCESS\"}");
    // The release ask, at the SECOND repository's own catalog id. Armed because it is now made:
    // the branch above is at a commit that is not main's, so it carries unreleased work whatever
    // this run did or did not push.
    // WRAPPED, as the controller sends it — the pushed story's stub says the same thing and for the
    // same reason: a client reading a flat body finds no id and records a convergence, so a flat
    // fixture would make this story pass against a service that never read the answer.
    StoryPeers.attach(StoryTarget.PROJECTS)
        .json(
            StoryCatalog.SECOND_RELEASE_REQUESTS_PATH,
            "{\"request\":{\"id\":\"rr-unmoved\",\"repoId\":\"r2\",\"state\":\"PENDING\","
                + "\"backingBranch\":\"release/rr-unmoved\",\"mergedSha\":null,\"detail\":null}}")
        // Nothing of this repository is released and waiting for main either.
        .jsonFor("GET", StoryCatalog.SECOND_RELEASE_REQUESTS_PATH, "{\"requests\":[]}");

    unmovedBumpId =
        StoryIdentities.operator(given())
            .contentType(ContentType.JSON)
            .post(
                StoryTarget.REPOSITORIES
                    + "/"
                    + StoryCatalog.SECOND_REPOSITORY
                    + "/groups/"
                    + StoryCatalog.EXTERNAL_GROUP
                    + "/bumps")
            .then()
            .statusCode(202)
            .extract()
            .path("id");
    story
        .note("a second repository, the external half of its grouping with something pending, and"
            + " a branch that already exists at a commit this service records before it triggers"
            + " anything")
        .as("the-head-before");

    assertEquals(
        "NOTHING_TO_DO",
        StoryWaits.bump(unmovedBumpId),
        "a green run that moved no branch must not be reported as a success");

    StoryIdentities.operator(given())
        .get(StoryTarget.BUMPS + "/" + unmovedBumpId)
        .then()
        .statusCode(200)
        .body("repository", equalTo(StoryCatalog.SECOND_REPOSITORY))
        .body("status", equalTo("NOTHING_TO_DO"))
        // The ci run itself PASSED. The two facts are independent, and reading them as one is the
        // mistake this outcome exists to prevent.
        .body("ciRunStatus", equalTo("SUCCESS"))
        .body("ciRunIds", equalTo(List.of(StoryCatalog.SECOND_RUN)))
        // AND THE RELEASE WAS ASKED FOR ANYWAY. This is the assertion the live defect needed: the
        // status says this run wrote nothing, and the column says the branch was still handed on,
        // because it is ahead of main. The message is the ASK's by then — `note` recomposes it from
        // the frozen change list on every retry, which is what stops the column growing a line per
        // tick through an outage — so the ending's own wording is deliberately not asserted here.
        .body("releaseRequestId", equalTo("rr-unmoved"))
        // The changes are still on the row: the payload went out and is what a person reads to see
        // what the step decided against.
        .body("changes.size()", greaterThan(0));
    story
        .note("the run PASSED and the branch is where it was, so the outcome is NOTHING_TO_DO — and"
            + " the branch is nevertheless ahead of main, so the release is asked for all the same."
            + " The status follows the run; the ask follows the branch. A bump that pushed nothing"
            + " in THIS run is not a branch with nothing to release, and treating the two as one is"
            + " what left a repository sitting on an old dependency for a day")
        .as("passed-and-unmoved");

    network.declare(
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "the head before the run is recorded, and the verdict is written against it");
  }

  @UserStory(value = REBUILT, category = CATEGORY)
  @UserStoryDescription(
      """
      A release of this repository was cut and has not reached main yet — its deployment has not
      succeeded, or its merge will not apply. qits-projects folds every such tag into each release
      request of the repository, so a maintenance branch cut from main that edits the line the
      tag already moved — a base image argument, a property in a pom — conflicts with it in every
      fold, for as long as the tag stays unmerged. So before the trigger this service reads the
      repository's release history from qits-projects, takes the newest release that has no merge
      to main — newest by the version's numbers, not its text — and asks the git host whether main
      contains it after all. It does not, so the bump is cut from the tag. And because the branch
      already exists, cut from main long ago, the git host is asked the same question about the
      branch: it lacks the tag, so its head travels with the payload as the head to replace, and
      the step rebuilds the branch on the tag under a lease on exactly that head. A branch that
      moved in between is refused rather than overwritten.
      """)
  @UserflowRunsAfter(InventoryIT.class)
  @Order(3)
  void aBranchIsCutFromTheReleaseThatHasNotReachedMain(Interactions story, Network network) {
    NetworkCapture.actor(StoryIdentities.OPERATOR);
    StoryPeers ci = StoryPeers.attach(StoryTarget.CI);
    StoryPeers githost = StoryPeers.attach(StoryTarget.GITHOST);
    StoryPeers projects = StoryPeers.attach(StoryTarget.PROJECTS);

    // THE HISTORY, as qits-projects lists it with state=all. The newest unmerged release is
    // 2026.1007.171656; 2026.1007.61854 is older and sorts AFTER it as text, a newer one has merged,
    // and a request that never released has no tag at all. Only the first may be chosen.
    projects.jsonFor(
        "GET",
        StoryCatalog.RELEASE_REQUESTS_PATH,
        "{\"requests\":["
            + "{\"id\":\"rr-open\",\"state\":\"PENDING\",\"version\":null,\"releasedSha\":null,"
            + "\"mergedToMainAt\":null},"
            + "{\"id\":\"rr-merged\",\"state\":\"FINALIZED\",\"version\":\"2026.1008.10000\","
            + "\"releasedSha\":\"1111111111111111111111111111111111111111\","
            + "\"mergedToMainAt\":\"2026-10-08T01:00:00Z\"},"
            + "{\"id\":\"rr-newest\",\"state\":\"RELEASED\",\"version\":\""
            + StoryCatalog.TAG_VERSION + "\",\"releasedSha\":\"" + StoryCatalog.TAG_SHA
            + "\",\"mergedToMainAt\":null},"
            + "{\"id\":\"rr-older\",\"state\":\"OBSOLETE\",\"version\":\"2026.1007.61854\","
            + "\"releasedSha\":\"2222222222222222222222222222222222222222\","
            + "\"mergedToMainAt\":null}]}");
    // Neither main nor the branch contains the tag — one answer, because the stand-in matches the
    // door without its query, and both of the questions a rebuild asks are answered "no".
    githost.json(
        StoryCatalog.CONTAINS_PATH,
        "{\"repoId\":\"" + StoryCatalog.CATALOG_ID + "\",\"commit\":\"" + StoryCatalog.TAG_SHA
            + "\",\"in\":\"" + StoryCatalog.ANGULAR_BRANCH_SHA + "\",\"contains\":false}");
    // The branch exists, cut from main before the release was.
    githost.json(
        StoryCatalog.tree(StoryCatalog.REPOSITORY, StoryCatalog.ANGULAR_BRANCH),
        "{\"entries\":[]}",
        Map.of("Git-Commit-Sha", StoryCatalog.ANGULAR_BRANCH_SHA));
    ci.json(
        StoryCatalog.TRIGGER_PATH,
        "{\"eventId\":\"e-rebuilt\",\"runIds\":[\"" + StoryCatalog.THIRD_RUN + "\"],"
            + "\"repositoriesRead\":1,\"repositoriesSkipped\":[]}");
    ci.json(
        StoryCatalog.runPath(StoryCatalog.THIRD_RUN),
        "{\"id\":\"" + StoryCatalog.THIRD_RUN + "\",\"status\":\"RUNNING\"}");

    rebuiltBumpId =
        StoryIdentities.operator(given())
            .contentType(ContentType.JSON)
            .post(groupBumps(StoryCatalog.REPOSITORY, StoryCatalog.ANGULAR_GROUP))
            .then()
            .statusCode(202)
            .extract()
            .path("id");
    StoryWaits.bumpReaches(rebuiltBumpId, "RUNNING");

    List<String> triggers = ci.bodiesFor(StoryCatalog.TRIGGER_PATH);
    String payload = triggers.getLast();
    assertTrue(
        payload.contains("\"eventId\":\"" + rebuiltBumpId + "\""),
        "the trigger read is this story's own: " + payload);
    assertTrue(
        payload.contains("\"baseRef\":\"refs/tags/" + StoryCatalog.TAG_VERSION + "\""),
        "the branch is cut from the newest release main does not carry: " + payload);
    assertTrue(
        payload.contains("\"replaceHead\":\"" + StoryCatalog.ANGULAR_BRANCH_SHA + "\""),
        "and the branch that lacks it is rebuilt over the head this service read: " + payload);
    story
        .note("the newest release that has not reached main — newest by its numbers, not its text —"
            + " is what the branch is cut from, because a branch cut from main would conflict with"
            + " it in every fold")
        .as("cut-from-the-unmerged-release");
    story
        .note("and the existing branch, which lacks it, travels as the head to replace: the step"
            + " rebuilds it on the tag under a lease on exactly that head")
        .as("rebuilt-over-a-leased-head");

    githost.json(
        StoryCatalog.tree(StoryCatalog.REPOSITORY, StoryCatalog.ANGULAR_BRANCH),
        "{\"entries\":[]}",
        Map.of("Git-Commit-Sha", StoryCatalog.REBUILT_SHA));
    ci.json(
        StoryCatalog.runPath(StoryCatalog.THIRD_RUN),
        "{\"id\":\"" + StoryCatalog.THIRD_RUN + "\",\"status\":\"SUCCESS\"}");
    assertEquals("SUCCEEDED", StoryWaits.bump(rebuiltBumpId));

    StoryIdentities.operator(given())
        .get(StoryTarget.BUMPS + "/" + rebuiltBumpId)
        .then()
        .statusCode(200)
        .body("group", equalTo(StoryCatalog.ANGULAR_GROUP))
        .body("status", equalTo("SUCCEEDED"))
        .body("baseRef", equalTo("refs/tags/" + StoryCatalog.TAG_VERSION))
        .body("replaceHead", equalTo(StoryCatalog.ANGULAR_BRANCH_SHA));
    story
        .note("the row says what the branch was cut from and what it replaced, which is also how the"
            + " dispatcher knows a conflicted release was already rebuilt on that tag once")
        .as("the-base-is-recorded");

    network.declare(
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "the base and the head it replaces are recorded on the bump row before the trigger");
  }

  @UserStory(value = UNDECIDED, category = CATEGORY)
  @UserStoryDescription(
      """
      The base is an improvement on a bump, never a precondition of it. When qits-projects cannot
      list the repository's releases, this service does not know whether a release is waiting to
      reach main, and it does not guess: the bump is cut from main, exactly as every bump was
      before the base was chosen at all, nothing is asked of the git host about a tag nobody named,
      no head is offered for replacing, and the bump goes out and ends as it would have anyway. The
      worst this costs is the conflict a tag base would have avoided.
      """)
  @UserflowRunsAfter(InventoryIT.class)
  @Order(4)
  void aBaseNobodyCouldAnswerForIsMain(Interactions story, Network network) {
    NetworkCapture.actor(StoryIdentities.OPERATOR);
    StoryPeers ci = StoryPeers.attach(StoryTarget.CI);
    StoryPeers projects = StoryPeers.attach(StoryTarget.PROJECTS);

    projects.answerFor(
        "GET",
        StoryCatalog.RELEASE_REQUESTS_PATH,
        503,
        "application/json",
        "{\"message\":\"unavailable\"}");
    ci.json(
        StoryCatalog.TRIGGER_PATH,
        "{\"eventId\":\"e-undecided\",\"runIds\":[\"" + StoryCatalog.FOURTH_RUN + "\"],"
            + "\"repositoriesRead\":1,\"repositoriesSkipped\":[]}");
    ci.json(
        StoryCatalog.runPath(StoryCatalog.FOURTH_RUN),
        "{\"id\":\"" + StoryCatalog.FOURTH_RUN + "\",\"status\":\"SUCCESS\"}");

    undecidedBumpId =
        StoryIdentities.operator(given())
            .contentType(ContentType.JSON)
            .post(groupBumps(StoryCatalog.REPOSITORY, StoryCatalog.EXTERNAL_GROUP))
            .then()
            .statusCode(202)
            .extract()
            .path("id");
    // Nothing is pushed — the external branch is armed nowhere, before or after — so the ending is
    // NOTHING_TO_DO, and it is an ending: the base did not stop the bump.
    assertEquals("NOTHING_TO_DO", StoryWaits.bump(undecidedBumpId));

    List<String> triggers = ci.bodiesFor(StoryCatalog.TRIGGER_PATH);
    String payload = triggers.getLast();
    assertTrue(
        payload.contains("\"eventId\":\"" + undecidedBumpId + "\""),
        "the trigger read is this story's own: " + payload);
    assertTrue(
        payload.contains("\"baseRef\":\"main\"") && !payload.contains("replaceHead"),
        "an unreadable listing is main, and nothing is rebuilt on a guess: " + payload);
    StoryIdentities.operator(given())
        .get(StoryTarget.BUMPS + "/" + undecidedBumpId)
        .then()
        .statusCode(200)
        .body("baseRef", equalTo("main"))
        .body("replaceHead", nullValue());
    story
        .note("qits-projects could not list the releases, so the branch is cut from main and the"
            + " bump goes out all the same — the base is never a reason for a bump to fail")
        .as("unreadable-is-main");

    network.declare(
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "main is recorded as the base the bump was cut from");
  }

  /** {@code POST …/repositories/<repo>/groups/<group>/bumps}. */
  private static String groupBumps(String repository, String group) {
    return StoryTarget.REPOSITORIES + "/" + repository + "/groups/" + group + "/bumps";
  }

  @AfterAll
  static void bothBaseStoriesAreComplete() {
    // --- cut from the unmerged release, and rebuilt -----------------------------------------------
    ReportAssertions.assertComplete(CATEGORY_SLUG, REBUILT_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY_SLUG, REBUILT_SLUG, "cut-from-the-unmerged-release");
    ReportAssertions.assertStepId(CATEGORY_SLUG, REBUILT_SLUG, "rebuilt-over-a-leased-head");
    ReportAssertions.assertStepId(CATEGORY_SLUG, REBUILT_SLUG, "the-base-is-recorded");
    in(
        REBUILT_SLUG,
        "POST " + groupBumps(StoryCatalog.REPOSITORY, StoryCatalog.ANGULAR_GROUP) + " -> 202");
    in(REBUILT_SLUG, "GET " + StoryTarget.BUMPS + "/" + StoryTarget.ID + " -> 200");
    out(
        REBUILT_SLUG,
        StoryTarget.PROJECTS,
        "GET " + StoryCatalog.releaseListingWire(StoryCatalog.RELEASE_REQUESTS_PATH) + " -> 200");
    // Main's head, for the ancestry question — and the question itself, under the storage id, with
    // both shas templated. ONE label for the two questions (on main, on the branch), because they
    // are the same door asked about the same tag.
    out(
        REBUILT_SLUG,
        StoryTarget.GITHOST,
        "GET " + StoryCatalog.treeWire(StoryCatalog.REPOSITORY, "main") + " -> 200");
    out(
        REBUILT_SLUG,
        StoryTarget.GITHOST,
        "GET " + StoryCatalog.CONTAINS_PATH + "?commit=" + StoryTarget.DIGEST + "&in="
            + StoryTarget.DIGEST + " -> 200");
    out(
        REBUILT_SLUG,
        StoryTarget.GITHOST,
        "GET " + StoryCatalog.treeWire(StoryCatalog.REPOSITORY, StoryCatalog.ANGULAR_BRANCH)
            + " -> 200");
    out(REBUILT_SLUG, StoryTarget.CI, "POST " + StoryCatalog.TRIGGER_PATH + " -> 200");
    out(
        REBUILT_SLUG,
        StoryTarget.CI,
        "GET " + StoryCatalog.runPath(StoryCatalog.THIRD_RUN) + " -> 200");
    out(
        REBUILT_SLUG,
        StoryTarget.PROJECTS,
        "POST " + StoryCatalog.RELEASE_REQUESTS_PATH + " -> 200");
    // Two in; out, the listing, main's head, the ancestry door, the branch head, the trigger, the
    // run and the release ask; and a row. Still no arrow into any repository: the rebuild is the
    // step's, under its lease, and this service only named the head it may replace.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, REBUILT_SLUG, 10);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG, REBUILT_SLUG, List.of(StoryIdentities.OPERATOR, StoryTarget.SERVICE));
    ReportAssertions.assertNotLeaked(CATEGORY_SLUG, REBUILT_SLUG, rebuiltBumpId);

    // --- the base nobody could answer for ---------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY_SLUG, UNDECIDED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY_SLUG, UNDECIDED_SLUG, "unreadable-is-main");
    out(
        UNDECIDED_SLUG,
        StoryTarget.PROJECTS,
        "GET " + StoryCatalog.releaseListingWire(StoryCatalog.RELEASE_REQUESTS_PATH) + " -> 503");
    out(UNDECIDED_SLUG, StoryTarget.CI, "POST " + StoryCatalog.TRIGGER_PATH + " -> 200");
    // Two in; out, the unreadable listing, the branch head (absent, before and after), the trigger,
    // the run, main's head at the ending; and a row. EIGHT, and no ancestry question among them:
    // there was no tag to ask about.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, UNDECIDED_SLUG, 8);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG, UNDECIDED_SLUG, List.of(StoryIdentities.OPERATOR, StoryTarget.SERVICE));
    ReportAssertions.assertNotLeaked(CATEGORY_SLUG, UNDECIDED_SLUG, undecidedBumpId);
  }

  @AfterAll
  static void bothBumpStoriesAreComplete() {
    String branchWire = StoryCatalog.treeWire(StoryCatalog.REPOSITORY, StoryCatalog.BRANCH);
    String secondBranchWire =
        StoryCatalog.treeWire(StoryCatalog.SECOND_REPOSITORY, StoryCatalog.EXTERNAL_BRANCH);
    String bumpsPath =
        StoryTarget.REPOSITORIES
            + "/"
            + StoryCatalog.REPOSITORY
            + "/groups/"
            + StoryCatalog.DEFAULT_GROUP
            + "/bumps";

    // --- the branch moved -----------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY_SLUG, PUSHED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY_SLUG, PUSHED_SLUG, "bump-accepted");
    ReportAssertions.assertStepId(CATEGORY_SLUG, PUSHED_SLUG, "the-payload-is-the-decision");
    ReportAssertions.assertStepId(CATEGORY_SLUG, PUSHED_SLUG, "the-changelogs-it-pulls-in");
    ReportAssertions.assertStepId(CATEGORY_SLUG, PUSHED_SLUG, "groups-are-branches");
    ReportAssertions.assertStepId(CATEGORY_SLUG, PUSHED_SLUG, "one-branch-one-writer");
    ReportAssertions.assertStepId(CATEGORY_SLUG, PUSHED_SLUG, "the-branch-was-pushed");
    ReportAssertions.assertStepId(CATEGORY_SLUG, PUSHED_SLUG, "the-branch-is-handed-on");
    ReportAssertions.assertStepId(CATEGORY_SLUG, PUSHED_SLUG, "the-bump-log");

    in(PUSHED_SLUG, "POST " + bumpsPath + " -> 202");
    // THE REFUSAL IS ITS OWN ARROW, because a status is half of what an edge says.
    in(PUSHED_SLUG, "POST " + bumpsPath + " -> 409");
    in(PUSHED_SLUG, "GET " + StoryTarget.BUMPS + "/" + StoryTarget.ID + " -> 200");
    in(PUSHED_SLUG, "GET " + StoryTarget.BUMPS + " -> 200");

    // The branch head, read twice: a 404 before the trigger and a commit after the run. Those two
    // labels ARE the comparison this story is about.
    out(PUSHED_SLUG, StoryTarget.GITHOST, "GET " + branchWire + " -> 404");
    out(PUSHED_SLUG, StoryTarget.GITHOST, "GET " + branchWire + " -> 200");
    out(PUSHED_SLUG, StoryTarget.CI, "POST " + StoryCatalog.TRIGGER_PATH + " -> 200");
    out(
        PUSHED_SLUG,
        StoryTarget.CI,
        "GET " + StoryCatalog.runPath(StoryCatalog.RUN) + " -> 200");
    // THE SECOND THING THIS SERVICE MAKES HAPPEN ANYWHERE ELSE. The repository is IN the path,
    // because that is how qits-projects addresses one — its own catalog row id — and a label
    // without it would not say which repository was handed on.
    out(PUSHED_SLUG, StoryTarget.PROJECTS, "POST " + StoryCatalog.RELEASE_REQUESTS_PATH + " -> 200");
    // AND THE READ BEFORE THE TRIGGER (qits-1081): is a release of this repository cut and not on
    // main yet? None is, so nothing else is asked and the base is main.
    out(
        PUSHED_SLUG,
        StoryTarget.PROJECTS,
        "GET " + StoryCatalog.releaseListingWire(StoryCatalog.RELEASE_REQUESTS_PATH) + " -> 200");

    // THE CHANGELOG LISTINGS (qits-893): one read per source repository — the eventstream's
    // answered, and the four whose releases predate changelogs a 404 each.
    out(
        PUSHED_SLUG,
        StoryTarget.ARTIFACTS,
        "GET " + StoryCatalog.changelogPath(StoryCatalog.SECOND_REPOSITORY) + " -> 200");
    for (String predates :
        List.of("qits-parent", "qits-arch-rules", "qits-ui-components-jslib", "qits-build-images")) {
      out(
          PUSHED_SLUG,
          StoryTarget.ARTIFACTS,
          "GET " + StoryCatalog.changelogPath(predates) + " -> 404");
    }

    ReportAssertions.assertDeclaredEdge(
        CATEGORY_SLUG,
        PUSHED_SLUG,
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "the changes are frozen onto the bump row at REQUEST time and never recomputed");

    // THE DESIGN, ASSERTED AS A SHAPE. Four requests in; out, the release listing, five changelog
    // listings, one trigger, one run read, two head reads, one release ask and a row. THIS SERVICE
    // PUSHED NOTHING — there is no arrow from it to any repository, because there is no such call in
    // it to make. A seventeenth edge would be this process having grown a way to touch somebody
    // else's tree, and no presence check could see it.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, PUSHED_SLUG, 16);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG, PUSHED_SLUG, List.of(StoryIdentities.OPERATOR, StoryTarget.SERVICE));
    // A bump reads no manifest and asks no registry: the changes were frozen at REQUEST time, out
    // of an inventory a scan wrote. Recomputing at dispatch would not be the list the operator saw.
    // (qits-projects IS reached here — for the release listing and the release ask above, never the
    // catalog; and qits-artifacts only for the changelog listings above, never a registry.)
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, PUSHED_SLUG, StoryTarget.MIRROR);
    // The row id is generated per run and reaches no label, no note and no rendering.
    ReportAssertions.assertNotLeaked(CATEGORY_SLUG, PUSHED_SLUG, pushedBumpId);

    // --- the branch did not ---------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY_SLUG, UNMOVED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY_SLUG, UNMOVED_SLUG, "the-head-before");
    ReportAssertions.assertStepId(CATEGORY_SLUG, UNMOVED_SLUG, "passed-and-unmoved");

    in(
        UNMOVED_SLUG,
        "POST "
            + StoryTarget.REPOSITORIES
            + "/"
            + StoryCatalog.SECOND_REPOSITORY
            + "/groups/"
            + StoryCatalog.EXTERNAL_GROUP
            + "/bumps -> 202");
    in(UNMOVED_SLUG, "GET " + StoryTarget.BUMPS + "/" + StoryTarget.ID + " -> 200");
    // ONE LABEL FOR BOTH READS, and that is the point rather than an accident: the head before the
    // trigger and the head after the run are the same answer, which is what NOTHING_TO_DO means.
    out(UNMOVED_SLUG, StoryTarget.GITHOST, "GET " + secondBranchWire + " -> 200");
    // MAIN'S HEAD, AND IT IS THE NEW ARROW. It is the read that turns "this run pushed nothing"
    // into "this branch has nothing unreleased" — two different questions, and the whole of the
    // 2026-09-16 defect was answering the second with the first. One read, at the ending only.
    out(
        UNMOVED_SLUG,
        StoryTarget.GITHOST,
        "GET " + StoryCatalog.treeWire(StoryCatalog.SECOND_REPOSITORY, "main") + " -> 200");
    out(UNMOVED_SLUG, StoryTarget.CI, "POST " + StoryCatalog.TRIGGER_PATH + " -> 200");
    out(
        UNMOVED_SLUG,
        StoryTarget.CI,
        "GET " + StoryCatalog.runPath(StoryCatalog.SECOND_RUN) + " -> 200");
    // …and the ask itself, at the SECOND repository's catalog id. The pushed story has this arrow
    // too; what used to separate the two endings on the far side of this service was its absence
    // here, and that separation was the bug.
    out(
        UNMOVED_SLUG,
        StoryTarget.PROJECTS,
        "POST " + StoryCatalog.SECOND_RELEASE_REQUESTS_PATH + " -> 200");
    out(
        UNMOVED_SLUG,
        StoryTarget.PROJECTS,
        "GET " + StoryCatalog.releaseListingWire(StoryCatalog.SECOND_RELEASE_REQUESTS_PATH)
            + " -> 200");

    ReportAssertions.assertDeclaredEdge(
        CATEGORY_SLUG,
        UNMOVED_SLUG,
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "the head before the run is recorded, and the verdict is written against it");

    // Two in; out, the release listing, two head reads (the branch and main), one trigger, one run
    // read, one release ask and a row. EIGHT where it was six came from main's head and the ask, which
    // are one change — you cannot honestly make the second without the first, because a branch that
    // is level with main must still be asked about for nothing — and NINE from the listing a base is
    // chosen from (qits-1081).
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, UNMOVED_SLUG, 9);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG, UNMOVED_SLUG, List.of(StoryIdentities.OPERATOR, StoryTarget.SERVICE));
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, UNMOVED_SLUG, StoryTarget.ARTIFACTS);
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, UNMOVED_SLUG, StoryTarget.MIRROR);
    ReportAssertions.assertNotLeaked(CATEGORY_SLUG, UNMOVED_SLUG, unmovedBumpId);
  }

  private static void in(String slug, String label) {
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        slug,
        NetworkEdge.HTTP,
        StoryIdentities.OPERATOR,
        StoryTarget.SERVICE,
        label);
  }

  private static void out(String slug, String peer, String label) {
    ReportAssertions.assertEdge(
        CATEGORY_SLUG, slug, NetworkEdge.HTTP, StoryTarget.SERVICE, peer, label);
  }
}
