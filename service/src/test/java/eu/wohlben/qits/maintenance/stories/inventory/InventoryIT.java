package eu.wohlben.qits.maintenance.stories.inventory;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.maintenance.stories.scan.ScanCycleIT;
import eu.wohlben.qits.maintenance.stories.support.StoryCatalog;
import eu.wohlben.qits.maintenance.stories.support.StoryIdentities;
import eu.wohlben.qits.maintenance.stories.support.StoryNetwork;
import eu.wohlben.qits.maintenance.stories.support.StoryProfile;
import eu.wohlben.qits.maintenance.stories.support.StoryTarget;
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
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * <b>The question this service exists to answer, and the shape of the answer.</b>
 *
 * <p>"Who is still on last month's release" has no answer anywhere else on this platform. Every
 * repository knows its own pins and nothing knows them all: qits-githost holds the files, the
 * registries hold the versions, and neither of them has ever been asked to join the two. That join
 * is what an inventory IS — and it is why the inventory is a STORE rather than a page that asks the
 * git host on each load.
 *
 * <p><b>So the whole subject of this story is an arrow that is not there.</b> Three reads answer
 * every repository, one repository in full, and one dependency's every pin across the platform —
 * and not one of them reaches qits-projects, qits-githost, a registry or the mirror. The proof is
 * not that the pages came back; it is that five directed negatives hold while they did, which is
 * exactly what a presence check cannot say.
 *
 * <p><b>It runs after {@link ScanCycleIT}</b>, and that is not merely convenient. The claim is about
 * requests that were never made, and it is only checkable once the story that DID make them has
 * drained its own edges — a cumulative source is attributed by a cursor, so an earlier scan's
 * manifest reads would otherwise land in this diagram and say the opposite of what the story says.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
public class InventoryIT {

  static final String CATEGORY = "the inventory";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String STORY = "An operator reads what the platform pins, and no peer is asked";

  static final String SLUG = Slugs.slug(STORY);

  @BeforeAll
  static void tapEverySideOfThisService() {
    StoryNetwork.install();
  }

  @UserStory(value = STORY, category = CATEGORY)
  @UserStoryDescription(
      """
      An operator opens the maintenance console. The landing page is every repository on the
      platform with the state of each — what it pins, which groups it configured, and how many
      upgrades are waiting behind each of them. One repository in full is the next click: every
      pin it holds, where in which file the version is set, what the newest published version is,
      and whether that counts as pending. And then the same inventory read the other way round —
      one dependency, and everyone who pins it — which is the question a release is followed by
      and the one nothing else on the platform can answer. None of it asks anybody anything: this
      is a store, filled by a scan that already ran, and a page that asked the git host on each
      load would answer nothing at all whenever the git host was busy.
      """)
  @UserflowRunsAfter(ScanCycleIT.class)
  void anOperatorReadsTheInventoryAndNoPeerIsAsked(Interactions story, Network network) {
    // A person is a pair of headers here: this service authenticates nobody, the platform edge
    // performed the login and asserted the identity. The tap sees a request and never a narrative
    // role, so the actor is named before the first call.
    NetworkCapture.actor(StoryIdentities.OPERATOR);

    StoryIdentities.operator(given())
        .get(StoryTarget.REPOSITORIES)
        .then()
        .statusCode(200)
        .body("size()", equalTo(2))
        .body("name", hasItem(StoryCatalog.REPOSITORY))
        .body("find { it.name == '" + StoryCatalog.REPOSITORY + "' }.status", equalTo("OK"))
        .body("find { it.name == '" + StoryCatalog.REPOSITORY + "' }.pending", greaterThan(0))
        // WHAT EACH REPOSITORY IS, read from the store rather than asked of qits-projects — which
        // is the whole point of the column and is checked by the five directed negatives below. It
        // is null for the repository the catalog did not classify, and that reads as "we were not
        // told" rather than as a kind of its own.
        .body(
            "find { it.name == '" + StoryCatalog.REPOSITORY + "' }.archetype",
            equalTo(StoryCatalog.ARCHETYPE))
        .body(
            "find { it.name == '" + StoryCatalog.SECOND_REPOSITORY + "' }.archetype", nullValue())
        // The two kind groups, whatever the repository's file still says: its retired `groups:`
        // key is ignored (qits-1133 R5).
        .body(
            "find { it.name == '" + StoryCatalog.REPOSITORY + "' }.groups.branch",
            hasItem(StoryCatalog.BRANCH));
    story
        .note("the landing page: every repository, what kind of thing it is, its groups, and how"
            + " many upgrades wait behind each of them")
        .as("repositories-listed");

    StoryIdentities.operator(given())
        .get(StoryTarget.REPOSITORIES + "/" + StoryCatalog.REPOSITORY)
        .then()
        .statusCode(200)
        .body("headSha", equalTo(StoryCatalog.HEAD_SHA))
        .body("archetype", equalTo(StoryCatalog.ARCHETYPE))
        // WHERE the version is set is the field that matters: a bump names a file and a location,
        // and a wrong location is a wrong edit in somebody else's repository.
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-arch-rules' }.manifestPath",
            equalTo("pom.xml"))
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-arch-rules' }.location",
            equalTo("property:qits.arch-rules.version"))
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-parent' }.location",
            equalTo("parent:eu.wohlben.qits:qits-parent"))
        .body("pins.find { it.name == '@qits/ui-components' }.manifestPath", equalTo("package.json"))
        .body("pins.find { it.name == 'qits/build-images/maven-base' }.manifestPath",
            equalTo("Dockerfile"))
        // Nothing anywhere still carries an expression in its name: the first live scan died
        // turning one of those into a URL.
        .body("pins.findAll { it.name.contains('$') }", equalTo(List.of()));
    story
        .note("one repository in full — every pin, and the file and location its version is set in,"
            + " because that is what a bump would have to edit")
        .as("one-repository-in-full");

    // THE INVENTORY READ THE OTHER WAY ROUND. This is the read that has no equivalent anywhere else
    // on the platform, and the reason the join is stored rather than computed on demand.
    StoryIdentities.operator(given())
        .queryParam("name", "eu.wohlben.qits:*")
        .get(StoryTarget.DEPENDENCIES)
        .then()
        .statusCode(200)
        .body("name", hasItem("eu.wohlben.qits:qits-eventstream"))
        .body("find { it.name == 'eu.wohlben.qits:qits-eventstream' }.latest", equalTo("2026.821.3"))
        .body(
            "find { it.name == 'eu.wohlben.qits:qits-eventstream' }.pins.repository",
            hasItem(StoryCatalog.REPOSITORY))
        .body(
            "find { it.name == 'eu.wohlben.qits:qits-eventstream' }.pins.find { it.repository =="
                + " '" + StoryCatalog.REPOSITORY + "' }.pending",
            equalTo(true))
        // A latest that could not be read offers nothing and SAYS SO. Nothing here is in that
        // state, and the field being null rather than absent is what a green tick would hide.
        .body("find { it.name == 'eu.wohlben.qits:qits-eventstream' }.error", nullValue());
    story
        .note("and the same inventory the other way round: one dependency, everyone who pins it —"
            + " the question a release is followed by, and the one nothing else here can answer")
        .as("who-pins-this");

    // The glob is the same two-wildcard form a group's `deps` entry takes, so an operator can paste
    // one out of a repository's own configuration and see exactly what it claims.
    StoryIdentities.operator(given())
        .queryParam("name", "@qits/*")
        .get(StoryTarget.DEPENDENCIES)
        .then()
        .statusCode(200)
        .body("name", hasItem("@qits/ui-components"))
        .body("name", not(hasItem("@angular/core")));
    story
        .note("the filter is the same glob a repository writes in its own grouping file, so what an"
            + " operator pastes in claims exactly what it claims over there")
        .as("the-glob-is-the-repositorys-own");

    // THE ONE ARROW OUT, AND IT IS A CLAIM RATHER THAN EVIDENCE. Every read above was answered from
    // the store, over a JDBC connection opened inside the launched process where no tap of ours can
    // stand. What makes the story's title true is not this edge but the five that are absent.
    network.declare(
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "every answer on this page is rows, joined in the store");
  }

  @AfterAll
  static void theInventoryStoryIsComplete() {
    ReportAssertions.assertComplete(CATEGORY_SLUG, SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY_SLUG, SLUG, "repositories-listed");
    ReportAssertions.assertStepId(CATEGORY_SLUG, SLUG, "one-repository-in-full");
    ReportAssertions.assertStepId(CATEGORY_SLUG, SLUG, "who-pins-this");
    ReportAssertions.assertStepId(CATEGORY_SLUG, SLUG, "the-glob-is-the-repositorys-own");

    in("GET " + StoryTarget.REPOSITORIES + " -> 200");
    in("GET " + StoryTarget.REPOSITORIES + "/" + StoryCatalog.REPOSITORY + " -> 200");
    // Both dependency reads are one edge: the tap labels a path and a status, and the glob travels
    // in the query. That is the right division — the graph says who reached what and got what, the
    // steps say which question was asked.
    in("GET " + StoryTarget.DEPENDENCIES + " -> 200");

    ReportAssertions.assertDeclaredEdge(
        CATEGORY_SLUG,
        SLUG,
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "every answer on this page is rows, joined in the store");

    // THE STORY'S TITLE, ASSERTED AS A SHAPE — five directed negatives, one per peer this service
    // has. Each one is a claim a presence check cannot make: that the console's landing page does
    // not depend on the catalog being up, that a repository's detail page does not re-read its
    // manifests, and that reading who pins a dependency asks no registry what is newest. The count
    // closes it: four edges, and a fifth would be one of the five arrows below appearing.
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, SLUG, StoryTarget.PROJECTS);
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, SLUG, StoryTarget.GITHOST);
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, SLUG, StoryTarget.CI);
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, SLUG, StoryTarget.ARTIFACTS);
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, SLUG, StoryTarget.MIRROR);
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, SLUG, "qits-platform-idp");
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, SLUG, 4);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG, SLUG, List.of(StoryIdentities.OPERATOR, StoryTarget.SERVICE));
  }

  private static void in(String label) {
    ReportAssertions.assertEdge(
        CATEGORY_SLUG, SLUG, NetworkEdge.HTTP, StoryIdentities.OPERATOR, StoryTarget.SERVICE, label);
  }
}
