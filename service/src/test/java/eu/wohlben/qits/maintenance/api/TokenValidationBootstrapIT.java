package eu.wohlben.qits.maintenance.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.stories.support.StoryIdentities;
import eu.wohlben.qits.maintenance.stories.support.StoryNetwork;
import eu.wohlben.qits.maintenance.stories.support.StoryPeers;
import eu.wohlben.qits.maintenance.stories.support.StoryProfile;
import eu.wohlben.qits.maintenance.stories.support.StoryTarget;
import eu.wohlben.qits.maintenance.testdb.EmbeddedPg;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.Network;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
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
 * The whole service as it is <b>packaged</b> — like {@link PackagedSurfaceIT} beside it, but with
 * the OIDC tenant <b>on</b>, which no {@code @QuarkusTest} here can prove.
 *
 * <p>The shipped tenant is {@code quarkus.oidc.tenant-enabled=${qits.auth.machine.required:false}},
 * and <b>no other suite in this repository turns that gate on at all</b> — the test
 * application.properties says so in as many words ("the OIDC tenant is disabled because
 * qits.auth.machine.required defaults false"), which is what keeps a clone-alone {@code ./mvnw
 * verify} free of an issuer. The consequence is that the entire shipped {@code quarkus.oidc.*}
 * block — auth-server-url with {@code discovery-enabled=false} and {@code jwks-path=jwks} joined
 * onto it, the boot-time fetch that {@code connection-delay} retries, audience enforcement,
 * groups→roles mapping — is exercised NOWHERE else. This is the one place it runs. The far side is
 * {@link MockIdp}, whose recordings make the interaction assertable on <b>both ends</b>.
 *
 * <p><b>It is the first class of this repository's story catalogue</b>, and the one the rest of it
 * is ordered behind. The proof doubles as documentation, emitted under {@code target/userstories/}
 * with a network diagram beside the steps. The diagram is <b>observed, never narrated</b>: the
 * framework's shipped RestAssured tap sees what a story sends into this service, {@link MockIdp}'s
 * recording supplies what this service sent to the idp, the five {@link StoryPeers} stand-ins
 * supply what it sent anywhere else, and the extension drains all of them at story end. A story
 * method therefore asserts and notes; it draws nothing. The one exception is the store, which no
 * tap can stand in front of: the accepted story {@code declare}s that JDBC edge, and the framework
 * marks it {@code "declared": true} and draws it distinctly, so a claim never renders like
 * evidence. The stories are browserless (no {@code Flow} parameter), so no Chromium is involved.
 *
 * <p><b>THIS CLASS RUNS FIRST, and that is load-bearing rather than tidiness.</b> Every far side in
 * this catalogue is a cumulative recording attributed by a cursor, so traffic that happened before
 * any story ran — the startup JWKS fetch, which is half the subject of the first story below —
 * lands in whichever story drains <i>first</i>. Every other story class carries
 * {@code @UserflowRunsAfter} pointing back here, and {@code UserflowClassOrderer} (junit's
 * secondary orderer, registered in the test {@code application.properties}) is what turns that into
 * an order. The two methods here are {@code @Order}ed for the same reason, one level down.
 *
 * <p><b>The route both stories drive is {@code GET /maintenance/api/repositories}</b>, and it is
 * chosen as the least side-effectful read this service has. Every route this API serves is
 * {@code @RolesAllowed({"qits:admin", "qits:system"})} — a person presses <i>Bump now</i> in a
 * browser and a machine may ask for the same thing — so the machine role reaches all of them and
 * the choice is about what the request DOES, not about what it takes:
 *
 * <ul>
 *   <li>it names nothing. It is the one guarded read with neither a path parameter nor a query
 *       parameter — {@code /repositories/{name}} names a repository, {@code /dependencies} takes a
 *       glob, {@code /bumps} takes a repository and a limit, and {@code /scans/{id}} and {@code
 *       /bumps/{id}} name a row;
 *   <li>it reads THIS service's own store and no peer. The whole reason the inventory is a store
 *       rather than a page that asks the git host on each load is that this question has to be
 *       answerable without one — and the peers this catalogue stands up are <b>up and
 *       answering</b>, which makes that a stronger claim than it was when they were dead ports:
 *       the listing did not ask them, rather than not being able to;
 *   <li>and it cannot write. The two calls that can are {@code POST /scans}, which starts a run
 *       that reads every repository on the platform, and {@code POST
 *       /repositories/{name}/groups/{group}/bumps}, which asks qits-ci to push a branch into
 *       somebody else's tree. Both have stories of their own further down the catalogue.
 * </ul>
 *
 * <p><b>THERE IS NO CEILING TO SHOW ON THE ACCEPTED SIDE, and that is this repository's shape
 * rather than an omission.</b> qits-platform-system's namesake ends its accepted story on a door
 * the same bearer does <i>not</i> open, because a terminal socket there is {@code
 * @RolesAllowed("qits:admin")}. Here the two roles are named together on every route, deliberately
 * — a machine-only guard would lock the operator out of the button this service exists to offer —
 * so a machine bearer that reads the inventory may also request a bump. What bounds the credential
 * is the role SET, and that is what the denied story's third door proves.
 *
 * <p><b>ITs are skipped by default here and this one does NOT flip that.</b> {@code skipITs} is
 * {@code true} in the root pom because {@link PackagedSurfaceIT} is this module's other integration
 * test and a good half of it is about the CLIENT — the base href at the root, the deep links, the
 * fallback that must not swallow {@code /maintenance} — which the userflow pipeline deliberately
 * does not build ({@code -Dquarkus.quinoa=false}, since the qits-platform-spa-maintenance submodule
 * arrives EMPTY in a step container). A blanket {@code -DskipITs=false} would make that run red on
 * a test that is right. {@code .config/qits/ci-event-userflows.yml} names the story classes
 * instead, which is also what keeps the userflow pipeline about these stories and nothing else —
 * and keeps the property's own meaning ("run everything") intact for the {@code native} profile in
 * service/pom.xml that sets it.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TokenValidationBootstrapIT {

  static final String CATEGORY = "authentication";
  static final String ACCEPTED_SLUG =
      "on-start-the-dependency-inventory-fetches-the-platform-s-signing-keys";
  static final String DENIED_SLUG =
      "a-stranger-s-token-never-reads-the-platform-s-dependency-inventory";

  private static final String REPOSITORIES = StoryTarget.REPOSITORIES;

  /**
   * The bearers the two stories minted, kept so {@code @AfterAll} can pin that none of them reached
   * the published bundle. A token is the one value in a story that must never be readable
   * afterwards, and every one is minted fresh per call — a helper that handed the same string to
   * two stories would make the check weaker than it reads as.
   */
  private static String peerBearer;

  private static String strangersBearer;

  private static String wrongAudienceBearer;

  private static String readerBearer;

  /**
   * Wires every side of the diagram, once, before either story runs — the RestAssured tap for what
   * arrives here, and six cumulative sources for what left. It is one call because a class that
   * wired half of it would produce a diagram that is wrong rather than incomplete, and every
   * registration in it is idempotent, so each story class calls the same line from its own
   * {@code @BeforeAll}.
   */
  @BeforeAll
  static void tapEverySideOfThisService() {
    StoryNetwork.install();
  }

  @UserStory(
      value = "On start, the dependency inventory fetches the platform's signing keys",
      category = "authentication")
  @UserStoryDescription(
      """
      A freshly deployed qits-platform-maintenance must validate service bearers before any
      caller arrives: at startup it fetches the signing keys (JWKS) from qits-idp —
      discovery stays off, the path is configured — so the very first machine request is
      accepted. What that bearer then buys is this service's whole surface, and the read it
      opens here is the inventory itself: every repository on the platform with what each has
      pending. That answer is rows out of this service's own store, which is the point of
      keeping an inventory at all — the question "who is still on last month's release" has to
      be answerable without asking the git host anything.
      """)
  @Order(1)
  void serviceBootFetchesJwksAndAcceptsPlatformTokens(Interactions story, Network network) {
    MockIdp idp = MockIdp.attach();

    story.note(
        "qits-platform-maintenance starts with the OIDC tenant on, beside a reachable"
            + " qits-idp");
    given().get("/maintenance/q/health/ready").then().statusCode(200);

    // End (a), the idp side: the JWKS was served during startup — before this story presented any
    // token at all. Readiness above is deliberately independent of that fetch — the shipped config
    // explains why: tying it to another service's would make a cold boot a question of ordering —
    // so a 200 there is not the claim. The recording is. (Readiness draws no edge either: the tap
    // skips any path carrying a /q/ segment, and the probe root here is /maintenance/q.)
    //
    // The edge itself is drained from that recording and nothing here draws it; what is asserted is
    // that it happened, and the note carries the one thing a method, a path and a status cannot —
    // WHEN. This is also the story that owns it: the cursor gives pre-story traffic to whichever
    // story drains first, which is why this class runs first and pins its method order.
    assertTrue(
        idp.recordedRequests().stream().anyMatch(r -> "/idp/jwks".equals(r.path())),
        "the packaged service never fetched /idp/jwks at startup");
    story
        .note("the signing keys were fetched at startup, before this story presented any token")
        .as("jwks-fetched");

    // End (b), this service's side: those keys are what token validation now runs on. A platform
    // peer's bearer (aud = the platform, roles in `groups`) opens the inventory listing — nothing
    // named, nothing written, no peer asked.
    //
    // The actor is set BEFORE the call: the tap sees a request, never a narrative role, and this is
    // what makes the observed edge read `a platform service -> qits-platform-maintenance`. A bearer
    // is exactly the thing that cannot say which kind of caller it is.
    //
    // The counts are read out of the postgres this JVM handed the launched process, over JDBC and
    // BEFORE the request, so what is asserted is that the listing is that store's rows rather than
    // merely that something well-formed came back. A comparison rather than a fixed number on
    // purpose: this class runs first in a catalogue whose later stories fill the inventory, and it
    // must say the same thing whether it is run alone or with them. Those connections are the
    // TEST's own and are deliberately not in the diagram: the edge below is the launched process's
    // read, which is what the story is about.
    int stored = rows("mt_repository");
    int scansBefore = rows("mt_scan");
    int bumpsBefore = rows("mt_bump");
    NetworkCapture.actor(StoryIdentities.PEER);
    peerBearer = StoryIdentities.machineToken();
    StoryIdentities.bearer(given(), peerBearer)
        .get(REPOSITORIES)
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("$", hasSize(stored));
    story
        .note("a platform peer's bearer (aud=qits-platform, groups=[qits:system]) is"
            + " accepted, and the listing is this store's rows")
        .as("inventory-served");

    // End (c), the store's own side, and it is what makes this the least side-effectful read this
    // service has rather than merely a plausible one.
    //
    // THE EDGE IS DECLARED, because it is the one dependency in this story that no tap can reach:
    // the connection is opened inside the launched process, between it and a postgres neither the
    // RestAssured tap nor any peer's recording ever sees. It renders muted and marked [declared]
    // for exactly that reason. What is NOT in the label is the two absences the label used to fold
    // in — "no peer asked, no work queued" — because an absence drawn as an arrow is an arrow
    // meaning its own opposite. They are assertions, so they are steps:
    //
    //   - the read STARTED nothing. This service's other two machine-reachable calls queue work —
    //     a scan reads every repository on the platform, a bump asks qits-ci to push a branch into
    //     somebody else's tree — and each of them lands as a row before it does anything. Neither
    //     table moved, so the credential was spent on a read and on nothing else;
    //   - it asked no peer. Every address this service reads manifests and versions from —
    //     qits-projects, qits-githost, qits-ci, the three registries and the mirror — is a
    //     recording stand-in that is UP and would have answered. The listing did not ask, which is
    //     the inventory being a store rather than a page that asks the git host on each load. The
    //     diagram says the same thing by having no such edge, and @AfterAll pins both the whole
    //     edge count and a directed negative per peer, so a call appearing later would show.
    assertEquals(
        scansBefore, rows("mt_scan"), "reading the inventory must not have started a scan");
    assertEquals(
        bumpsBefore, rows("mt_bump"), "reading the inventory must not have requested a bump");
    network.declare(
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "the listing is read from the inventory tables");
    story
        .note("no peer was asked and no work was queued: mt_scan and mt_bump are where they were")
        .as("store-read");
  }

  @UserStory(
      value = "A stranger's token never reads the platform's dependency inventory",
      category = "authentication")
  @UserStoryDescription(
      """
      The flip side of trusting the platform's keys. A token signed by a key the published JWKS
      never carried, or minted for an audience that is not this platform's, is refused at the door —
      however well-formed it looks: both are 401 and not 403, because the credential never became an
      identity and there is no caller to have been forbidden. A token addressed here and signed
      correctly but carrying a role this service has never heard of gets the other answer, 403 —
      it authenticated and covers nothing. There is no anonymous route in this service and there
      must never be one: what the inventory shows is every repository on the platform and what
      each of them pins, and the same credential that reads it can ask for a branch to be pushed
      into any of them.
      """)
  @Order(2)
  void aStrangersTokenIsRefused(Interactions story) {
    MockIdp idp = MockIdp.attach();

    // The first two credentials are both an impostor's, so the actor is set once, up front — and
    // before the first call, because the tap reads it at the moment the request is sent.
    NetworkCapture.actor("an impostor");

    strangersBearer =
        idp.token()
            .audience(StoryProfile.PLATFORM_AUDIENCE)
            .groups(StoryIdentities.MACHINE_ROLE)
            .signedByUnknownKey()
            .mint();
    StoryIdentities.bearer(given(), strangersBearer).get(REPOSITORIES).then().statusCode(401);
    // Both refusals are the same edge — same actor, same route, same status — so the framework
    // dedupes them, the diagram draws one arrow, and the notes are what keep the two credentials
    // distinguishable. That is the right division: the graph says who reached what and got what,
    // the steps say why.
    story
        .note("a token signed by a key the published JWKS never carried is refused")
        .as("unknown-key-refused");

    // The audience half, which is what proves quarkus.oidc.token.audience=qits-platform is read
    // rather than assumed: this token is signed by the very key the JWKS published and is refused
    // anyway, because it was cut for somewhere that is not this platform. A PEER's token would not
    // do here — qits-idp stamps qits-platform onto every token it mints, so a sibling
    // service's bearer is admitted and its roles decide what it may do, which is the third door
    // below. The audience says which platform, never which service.
    wrongAudienceBearer =
        idp.token()
            .audience(StoryProfile.FOREIGN_AUDIENCE)
            .groups(StoryIdentities.MACHINE_ROLE)
            .mint();
    StoryIdentities.bearer(given(), wrongAudienceBearer).get(REPOSITORIES).then().statusCode(401);
    story
        .note("a token minted for an audience that is not this platform's is refused just the same —"
            + " 401 and not 403, because the credential never became an identity")
        .as("wrong-audience-refused");

    // The third door, and the one that proves the groups→roles mapping really ran rather than being
    // waved through: `qits:reader` is a real platform role and it is not one of the two every route
    // here names. Minted into a token addressed here it authenticates perfectly and still covers
    // nothing — which is the whole bound on a credential that, with the right role, would open the
    // bump button as well as the listing. A different caller and a different answer, so this is its
    // own arrow: the actor is renamed before the call that draws it.
    NetworkCapture.actor(StoryIdentities.WRONG_ROLE);
    readerBearer =
        idp.token()
            .subject("somebody-elses-service")
            .audience(StoryProfile.PLATFORM_AUDIENCE)
            .groups(StoryIdentities.READER_ROLE)
            .mint();
    StoryIdentities.bearer(given(), readerBearer).get(REPOSITORIES).then().statusCode(403);
    story
        .note("a token addressed here but carrying qits:reader authenticates and covers nothing:"
            + " 403, the other answer")
        .as("wrong-role-refused");
  }

  /**
   * How many rows one of the store's tables holds, read the way {@link PackagedSurfaceIT} reads its
   * scan row back: over JDBC against the same embedded postgres the launched process was handed, so
   * the number does not come from the API that is under test.
   *
   * <p>The table name is a literal from this class and never anything a request carried, which is
   * why it is concatenated rather than bound — a {@code count(*)} has no parameter position for an
   * identifier.
   */
  private static int rows(String table) {
    String url = EmbeddedPg.url(StoryProfile.DATABASE);
    try (Connection connection =
            DriverManager.getConnection(url, EmbeddedPg.USER, EmbeddedPg.PASSWORD);
        Statement sql = connection.createStatement();
        ResultSet found = sql.executeQuery("select count(*) from " + table)) {
      found.next();
      return found.getInt(1);
    } catch (Exception e) {
      throw new IllegalStateException("could not read " + table + " back", e);
    }
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    // The extension emits each report in its afterEach, so both are on disk before @AfterAll runs.
    // assertComplete also proves the network section: the sidecar's edges are canonical, the
    // networkHash recomputes from them, and every mermaid line is in the markdown.
    ReportAssertions.assertComplete(CATEGORY, ACCEPTED_SLUG, UserflowReport.PASSED);
    // Observed on the far side, drained from the mock's recording, and attributed to this story
    // because it is the first one that ran (see the class javadoc on ordering).
    ReportAssertions.assertEdge(
        CATEGORY,
        ACCEPTED_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.SERVICE,
        MockIdp.SERVICE_NAME,
        "GET /idp/jwks -> 200");
    // Observed on the near side, by the framework's shipped tap, with the actor this story set.
    ReportAssertions.assertEdge(
        CATEGORY,
        ACCEPTED_SLUG,
        NetworkEdge.HTTP,
        StoryIdentities.PEER,
        StoryTarget.SERVICE,
        "GET " + REPOSITORIES + " -> 200");
    // Declared, and asserted AS a declaration: assertDeclaredEdge fails if this ever became an
    // observation, which is the guard that keeps a claim from quietly starting to read like
    // evidence.
    ReportAssertions.assertDeclaredEdge(
        CATEGORY,
        ACCEPTED_SLUG,
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "the listing is read from the inventory tables");
    // "It reads THIS service's own store and no peer" is the story's own promise, and this is where
    // it is checkable rather than described: three edges and no fourth, plus a directed negative
    // per peer. Every one of those five is UP and answering in this catalogue, which is what makes
    // the absence a decision rather than a limitation.
    ReportAssertions.assertEdgeCount(CATEGORY, ACCEPTED_SLUG, 3);
    ReportAssertions.assertNoEdgesTo(CATEGORY, ACCEPTED_SLUG, StoryTarget.PROJECTS);
    ReportAssertions.assertNoEdgesTo(CATEGORY, ACCEPTED_SLUG, StoryTarget.GITHOST);
    ReportAssertions.assertNoEdgesTo(CATEGORY, ACCEPTED_SLUG, StoryTarget.CI);
    ReportAssertions.assertNoEdgesTo(CATEGORY, ACCEPTED_SLUG, StoryTarget.ARTIFACTS);
    ReportAssertions.assertNoEdgesTo(CATEGORY, ACCEPTED_SLUG, StoryTarget.MIRROR);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY, ACCEPTED_SLUG, List.of(StoryIdentities.PEER, StoryTarget.SERVICE));
    ReportAssertions.assertNotLeaked(CATEGORY, ACCEPTED_SLUG, peerBearer);
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "jwks-fetched");
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "inventory-served");
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "store-read");

    ReportAssertions.assertComplete(CATEGORY, DENIED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertEdge(
        CATEGORY,
        DENIED_SLUG,
        NetworkEdge.HTTP,
        "an impostor",
        StoryTarget.SERVICE,
        "GET " + REPOSITORIES + " -> 401");
    ReportAssertions.assertEdge(
        CATEGORY,
        DENIED_SLUG,
        NetworkEdge.HTTP,
        StoryIdentities.WRONG_ROLE,
        StoryTarget.SERVICE,
        "GET " + REPOSITORIES + " -> 403");
    // THE STORY'S TITLE, ASSERTED AS A SHAPE. "A stranger's token never READS the inventory" is a
    // claim about what did not leave this process: no JDBC edge, no peer call, nothing. Three
    // refused requests in and not one arrow out.
    ReportAssertions.assertNoEdgesFrom(CATEGORY, DENIED_SLUG, StoryTarget.SERVICE);
    ReportAssertions.assertEdgeCount(CATEGORY, DENIED_SLUG, 2);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY, DENIED_SLUG, List.of("an impostor", StoryIdentities.WRONG_ROLE));
    ReportAssertions.assertNotLeaked(CATEGORY, DENIED_SLUG, strangersBearer);
    ReportAssertions.assertNotLeaked(CATEGORY, DENIED_SLUG, wrongAudienceBearer);
    ReportAssertions.assertNotLeaked(CATEGORY, DENIED_SLUG, readerBearer);
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "unknown-key-refused");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "wrong-audience-refused");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "wrong-role-refused");
  }
}
