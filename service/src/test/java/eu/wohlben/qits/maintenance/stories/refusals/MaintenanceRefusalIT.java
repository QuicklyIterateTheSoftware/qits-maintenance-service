package eu.wohlben.qits.maintenance.stories.refusals;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.maintenance.stories.bump.BumpIT;
import eu.wohlben.qits.maintenance.stories.support.StoryCatalog;
import eu.wohlben.qits.maintenance.stories.support.StoryIdentities;
import eu.wohlben.qits.maintenance.stories.support.StoryNetwork;
import eu.wohlben.qits.maintenance.stories.support.StoryProfile;
import eu.wohlben.qits.maintenance.stories.support.StoryTarget;
import eu.wohlben.qits.maintenance.testdb.EmbeddedPg;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * <b>The doors, and what is behind each of them.</b>
 *
 * <p>What this service's write surface can do is push a branch into every repository on the
 * platform, and what its read surface shows is every pin every one of them holds. So there is no
 * anonymous route here and there must never be one — and the two stories below are the two ways a
 * request fails to become work:
 *
 * <ul>
 *   <li><b>the credential</b>: no identity at all is 401 and a role that is not one of the two
 *       every route names is 403. Both are refused before any resource method runs, which the
 *       diagram says by having <i>no arrow leaving this process at all</i> — not to the store, not
 *       to a peer, and above all not to qits-ci.
 *   <li><b>the request</b>: an authenticated operator asking for something that does not exist or
 *       does not parse. Each one is refused by name in the platform's one-key envelope, and none of
 *       them starts anything: {@code mt_scan} and {@code mt_bump} are where they were.
 * </ul>
 *
 * <p><b>It runs last</b>, after the bump stories, and that is not tidiness: this class posts to the
 * very bump route those stories drive, and a refusal landing while somebody else's bump was in
 * flight would be a 409 wearing a 401's clothes.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MaintenanceRefusalIT {

  static final String CATEGORY = "refusals";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String CREDENTIAL = "There is no anonymous route here, and no third role that opens one";

  static final String CREDENTIAL_SLUG = Slugs.slug(CREDENTIAL);

  static final String MALFORMED = "A request this service cannot make sense of starts no work";

  static final String MALFORMED_SLUG = Slugs.slug(MALFORMED);

  private static final String BUMP_ROUTE =
      StoryTarget.REPOSITORIES
          + "/"
          + StoryCatalog.REPOSITORY
          + "/groups/"
          + StoryCatalog.DEFAULT_GROUP
          + "/bumps";

  @BeforeAll
  static void tapEverySideOfThisService() {
    StoryNetwork.install();
  }

  @UserStory(value = CREDENTIAL, category = CATEGORY)
  @UserStoryDescription(
      """
      Two tracks of identity reach this service and neither of them is optional. A request with no
      Authorization header is a PERSON: the platform edge performed the login, stripped every
      client-supplied X-Qits-* header and asserted the one it decided on. A request with a bearer
      is a MACHINE, validated against qits-idp. Both land as roles, which is why every
      route names the same pair — an operator presses Bump in a browser and a scheduled machine may
      ask for the same thing, so a machine-only guard would lock the operator out of the button
      this service exists to offer. What is NOT negotiable is that something has to arrive: with no
      identity the answer is 401, and with a real platform role that is neither of the two the
      answer is 403. Neither ever reaches a resource method, which is why nothing leaves this
      process while they are being refused — no store read, no peer call, and no branch pushed
      anywhere.
      """)
  @UserflowRunsAfter(BumpIT.class)
  @Order(1)
  void nothingHereIsAnonymousAndNoThirdRoleOpensIt(Interactions story) {
    // The tap sees a request and never a narrative role, so each caller is named before it acts.
    NetworkCapture.actor(StoryIdentities.ANONYMOUS);

    given().get(StoryTarget.REPOSITORIES).then().statusCode(401);
    given()
        .contentType(ContentType.JSON)
        .body("{\"scope\":\"ALL\"}")
        .post(StoryTarget.SCANS)
        .then()
        .statusCode(401);
    given().contentType(ContentType.JSON).post(BUMP_ROUTE).then().statusCode(401);
    story
        .note("with no identity at all, the read is 401 — and so are both of the calls that queue"
            + " work, which is the half that matters: one reads every repository on the platform"
            + " and the other asks for a branch to be pushed into one")
        .as("no-identity-no-surface");

    // The other answer, and the one that proves the roles→guard mapping ran rather than being waved
    // through: qits:reader is a real platform role and it is not one of the two every route here
    // names. It authenticates perfectly and covers nothing.
    NetworkCapture.actor(StoryIdentities.WRONG_ROLE);
    StoryIdentities.withRole(given(), StoryIdentities.READER_ROLE)
        .get(StoryTarget.REPOSITORIES)
        .then()
        .statusCode(403);
    StoryIdentities.withRole(given(), StoryIdentities.READER_ROLE)
        .contentType(ContentType.JSON)
        .body("{\"scope\":\"ALL\"}")
        .post(StoryTarget.SCANS)
        .then()
        .statusCode(403);
    StoryIdentities.withRole(given(), StoryIdentities.READER_ROLE)
        .contentType(ContentType.JSON)
        .post(BUMP_ROUTE)
        .then()
        .statusCode(403);
    story
        .note("a real platform role that is neither qits:admin nor qits:system gets the OTHER"
            + " answer, 403: the caller became an identity and that identity covers nothing here")
        .as("authenticated-and-covers-nothing");
  }

  @UserStory(value = MALFORMED, category = CATEGORY)
  @UserStoryDescription(
      """
      The operator is who they say they are and the request still cannot be honoured. A scope that
      is not one of the three is refused rather than treated as everything — quietly widening a
      scan to ALL would be this service deciding what somebody meant. A repository the inventory
      does not hold has no pins to show and no coordinate to name in a payload; a group that
      repository never declared has no branch; and an id that is not a uuid is the same question
      from the caller's side as an id that names nothing. Every one is the platform's one-key
      envelope with the sentence in it, and — this is the half that is worth asserting — every one
      of them starts NOTHING. A 202 is what queues work here, and none of these was one.
      """)
  @UserflowRunsAfter(BumpIT.class)
  @Order(2)
  void aMalformedRequestIsRefusedByNameAndQueuesNothing(Interactions story, Network network) {
    NetworkCapture.actor(StoryIdentities.OPERATOR);
    // Read over JDBC against the same embedded postgres the launched process was handed, so the
    // numbers do not come from the API that is under test.
    int scansBefore = rows("mt_scan");
    int bumpsBefore = rows("mt_bump");

    StoryIdentities.operator(given())
        .contentType(ContentType.JSON)
        .body("{\"scope\":\"SOMETIMES\"}")
        .post(StoryTarget.SCANS)
        .then()
        .statusCode(400)
        .contentType(ContentType.JSON)
        .body("message", containsString("INTERNAL, EXTERNAL or ALL"));
    story
        .note("a scope that is not one of the three is refused rather than widened to everything:"
            + " a scan of the whole catalog is minutes of other services' reads")
        .as("an-unknown-scope-is-not-a-guess");

    StoryIdentities.operator(given())
        .get(StoryTarget.REPOSITORIES + "/nothing-like-this")
        .then()
        .statusCode(404)
        .contentType(ContentType.JSON)
        .body("message", containsString("nothing-like-this"));
    StoryIdentities.operator(given())
        .contentType(ContentType.JSON)
        .post(
            StoryTarget.REPOSITORIES
                + "/"
                + StoryCatalog.REPOSITORY
                + "/groups/invented/bumps")
        .then()
        .statusCode(404)
        .contentType(ContentType.JSON)
        .body("message", containsString("invented"));
    story
        .note("a repository the inventory does not hold and a group a repository never declared are"
            + " both 404 by name — the second one covers 'never scanned' as well as 'not in the"
            + " catalog', because neither has anything to bump")
        .as("named-in-the-refusal");

    StoryIdentities.operator(given())
        .get(StoryTarget.BUMPS + "/not-a-uuid")
        .then()
        .statusCode(404)
        .contentType(ContentType.JSON)
        .body("message", not(nullValue()));
    story
        .note("and an id that is not a uuid is the same question from the caller's side as an id"
            + " that names nothing: 404, not a 500 about parsing")
        .as("a-malformed-id-is-just-unknown");

    // THE HALF THAT IS WORTH ASSERTING. A 202 is what queues work here, and none of the four
    // answers above was one — so the two tables a scan and a bump each land in before they do
    // anything are exactly where they were.
    assertEquals(scansBefore, rows("mt_scan"), "a refused request must not have opened a scan");
    assertEquals(bumpsBefore, rows("mt_bump"), "a refused request must not have opened a bump");
    story
        .note("none of them started anything: a scan row and a bump row are each written before"
            + " their work begins, and both tables are where they were")
        .as("nothing-was-queued");

    // Two of the four refusals DID reach the store — that is how "no such repository" is answered —
    // so the edge is declared rather than pretended away. What the story claims is the five arrows
    // that are absent, not this one.
    network.declare(
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "a name that is not in the inventory is looked up and is not there");
  }

  /**
   * How many rows one of the store's tables holds. The table name is a literal from this class and
   * never anything a request carried, which is why it is concatenated rather than bound — a {@code
   * count(*)} has no parameter position for an identifier.
   */
  private static int rows(String table) {
    String url = EmbeddedPg.url(StoryProfile.DATABASE);
    try (Connection connection =
            DriverManager.getConnection(url, EmbeddedPg.USER, EmbeddedPg.PASSWORD);
        Statement sql = connection.createStatement();
        ResultSet found = sql.executeQuery("select count(*) from " + table)) {
      found.next();
      return found.getInt(1);
    } catch (Exception unreadable) {
      throw new IllegalStateException("could not read " + table + " back", unreadable);
    }
  }

  @AfterAll
  static void bothRefusalStoriesAreComplete() {
    // --- the credential -------------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY_SLUG, CREDENTIAL_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY_SLUG, CREDENTIAL_SLUG, "no-identity-no-surface");
    ReportAssertions.assertStepId(
        CATEGORY_SLUG, CREDENTIAL_SLUG, "authenticated-and-covers-nothing");

    in(CREDENTIAL_SLUG, StoryIdentities.ANONYMOUS, "GET " + StoryTarget.REPOSITORIES + " -> 401");
    in(CREDENTIAL_SLUG, StoryIdentities.ANONYMOUS, "POST " + StoryTarget.SCANS + " -> 401");
    in(CREDENTIAL_SLUG, StoryIdentities.ANONYMOUS, "POST " + BUMP_ROUTE + " -> 401");
    in(CREDENTIAL_SLUG, StoryIdentities.WRONG_ROLE, "GET " + StoryTarget.REPOSITORIES + " -> 403");
    in(CREDENTIAL_SLUG, StoryIdentities.WRONG_ROLE, "POST " + StoryTarget.SCANS + " -> 403");
    in(CREDENTIAL_SLUG, StoryIdentities.WRONG_ROLE, "POST " + BUMP_ROUTE + " -> 403");

    // THE STORY'S TITLE, ASSERTED AS A SHAPE. Six requests in — two of them at the route that
    // pushes a branch into somebody else's tree — and NOT ONE ARROW OUT. No store read, because no
    // resource method ran; no peer call, because nothing got as far as deciding to make one.
    ReportAssertions.assertNoEdgesFrom(CATEGORY_SLUG, CREDENTIAL_SLUG, StoryTarget.SERVICE);
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, CREDENTIAL_SLUG, StoryTarget.CI);
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, CREDENTIAL_SLUG, StoryTarget.GITHOST);
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, CREDENTIAL_SLUG, 6);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG,
        CREDENTIAL_SLUG,
        List.of(StoryIdentities.ANONYMOUS, StoryIdentities.WRONG_ROLE));

    // --- the request ----------------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY_SLUG, MALFORMED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(
        CATEGORY_SLUG, MALFORMED_SLUG, "an-unknown-scope-is-not-a-guess");
    ReportAssertions.assertStepId(CATEGORY_SLUG, MALFORMED_SLUG, "named-in-the-refusal");
    ReportAssertions.assertStepId(CATEGORY_SLUG, MALFORMED_SLUG, "a-malformed-id-is-just-unknown");
    ReportAssertions.assertStepId(CATEGORY_SLUG, MALFORMED_SLUG, "nothing-was-queued");

    in(MALFORMED_SLUG, StoryIdentities.OPERATOR, "POST " + StoryTarget.SCANS + " -> 400");
    in(
        MALFORMED_SLUG,
        StoryIdentities.OPERATOR,
        "GET " + StoryTarget.REPOSITORIES + "/nothing-like-this -> 404");
    in(
        MALFORMED_SLUG,
        StoryIdentities.OPERATOR,
        "POST "
            + StoryTarget.REPOSITORIES
            + "/"
            + StoryCatalog.REPOSITORY
            + "/groups/invented/bumps -> 404");
    in(MALFORMED_SLUG, StoryIdentities.OPERATOR, "GET " + StoryTarget.BUMPS + "/not-a-uuid -> 404");

    ReportAssertions.assertDeclaredEdge(
        CATEGORY_SLUG,
        MALFORMED_SLUG,
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "a name that is not in the inventory is looked up and is not there");

    // Four refusals, one lookup that found nothing, and five arrows that are not here. The one that
    // pays most is qits-ci: a group that does not exist must be refused on THIS side rather than
    // discovered by a pipeline that already cloned somebody's repository.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, MALFORMED_SLUG, 5);
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, MALFORMED_SLUG, StoryTarget.CI);
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, MALFORMED_SLUG, StoryTarget.GITHOST);
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, MALFORMED_SLUG, StoryTarget.PROJECTS);
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, MALFORMED_SLUG, StoryTarget.ARTIFACTS);
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, MALFORMED_SLUG, StoryTarget.MIRROR);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG, MALFORMED_SLUG, List.of(StoryIdentities.OPERATOR, StoryTarget.SERVICE));
  }

  private static void in(String slug, String actor, String label) {
    ReportAssertions.assertEdge(
        CATEGORY_SLUG, slug, NetworkEdge.HTTP, actor, StoryTarget.SERVICE, label);
  }
}
