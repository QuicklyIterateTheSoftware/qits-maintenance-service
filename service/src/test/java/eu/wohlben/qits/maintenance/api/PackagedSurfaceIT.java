package eu.wohlben.qits.maintenance.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.testdb.EmbeddedPg;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * The surface of the <em>packaged artifact</em> — the fast-jar under {@code ./mvnw verify
 * -DskipITs=false}, the GraalVM binary under {@code -Dnative} — because that is where a whole class
 * of failure is visible and nowhere else.
 *
 * <p>Every other test here is a {@code @QuarkusTest}: it augments and runs in the build JVM, with
 * the full classpath present, reflection unrestricted, its datasource keys handed to it by a config
 * source and its peers replaced by an {@code @Alternative}. A native image has none of those. What
 * this asserts is exactly what that difference can lose:
 *
 * <ul>
 *   <li>the build-time route prefixes — {@code /maintenance/api} and {@code /maintenance/q} — which
 *       qits-gateway routes verbatim and no unprefixed form falls back to;
 *   <li>the shipped datasource <b>expression</b>: the launched process is handed {@code
 *       QITS_RESOURCE_DB_*}, the generic contract a deployment supplies, rather than the datasource
 *       keys, so the jar's own indirection is what is under test;
 *   <li>Flyway's migration surviving as a classpath resource, proven by reading a written row back
 *       over JDBC rather than through the API that wrote it;
 *   <li>every response type reaching Jackson through {@code Response.entity(...)}, which the
 *       build-time analysis cannot see — that is what {@link ApiWireReflection} is for, and a
 *       missing entry there is a 500 in the binary while the JVM suite stays green. The 202 from a
 *       queued scan is exactly such a response;
 *   <li><b>the client is served, and does not swallow the API.</b> Quinoa is disabled by default in
 *       test mode, so no {@code @QuarkusTest} builds or serves the SPA and every assertion about
 *       the served client would pass against a process with no client in it.
 * </ul>
 *
 * <p><b>This is also the only place the identity contract is real.</b> A {@code @QuarkusTest} runs
 * under the {@code test} profile, where qits-auth-core ships a dev user; the launched artifact runs
 * as a deployment does, so the roles have to arrive the way the edge sends them — in {@code
 * X-Qits-User} and {@code X-Qits-Roles}. A request with neither is asserted to be refused.
 *
 * <p><b>And the peers here are REAL calls to a port nothing listens on.</b> The profile points every
 * target at a dead loopback address, so the scan this launches reads no catalog and FAILS in
 * milliseconds — which is the honest end-to-end proof that the worker, the store and the API carry
 * a failure all the way to a readable row without any of the suite's fakes involved.
 *
 * <p>ITs are skipped by default ({@code skipITs} in the root pom) because they need a {@code
 * package} to have happened. Ask for them explicitly.
 */
@QuarkusIntegrationTest
@TestProfile(PackagedSurfaceIT.PackagedUnderTarget.class)
public class PackagedSurfaceIT {

  /**
   * The database this IT hands the launched process, on a name of its own.
   *
   * <p>Package-private rather than private because {@link TokenValidationBootstrapIT} extends the
   * profile below and reads the very same store back over JDBC — one name for one database, in the
   * class that owns it.
   */
  static final String DATABASE = "maintenance_packaged_it";

  /**
   * The event bus's own database, on a name of its own, handed over as the second resource triple.
   *
   * <p><b>It is not optional, and that is the whole point of the second triple.</b> The bus jar is
   * dark outside a deployment, and dark stops publishing and dialling rather than connecting — so a
   * packaged process handed no eventstream url dies at Flyway naming the missing variable, which is
   * exactly the refuse-to-boot stance this IT exists to keep honest.
   */
  static final String EVENTSTREAM_DATABASE = "maintenance_packaged_it_eventstream";

  /**
   * The one string that identifies a response as the CLIENT's index.html rather than anything else
   * this process serves. The client is mounted at the root of this service's own host now, so this
   * is what {@code baseHref} in qits-platform-spa-maintenance's angular.json spells — the one value
   * set in another repository, which is why the probes below still assert it.
   */
  private static final String BASE_HREF = "<base href=\"/\">";

  /**
   * Hands the launched artifact its two databases the way a deployment does — as the generic
   * resource triples {@code QITS_RESOURCE_DB_*} and {@code QITS_RESOURCE_EVENTSTREAM_*}, not as the
   * datasource keys — and points every peer at a port nothing listens on.
   *
   * <p>Both the domain jar and qits-eventstream ship {@code jdbc.url=${QITS_RESOURCE_…_URL}} and its
   * two siblings, so supplying the variables leaves the <b>shipped</b> expressions themselves under
   * test.
   *
   * <p>The urls travel through system properties rather than static fields: a test profile is
   * instantiated in more than one classloader, so a field written by one copy is not the field the
   * other reads, while the process has exactly one property table.
   */
  public static class PackagedUnderTarget implements QuarkusTestProfile {

    private static final String URL_PROPERTY = "qits.test.packaged-surface-it.db-url";

    private static final String EVENTSTREAM_URL_PROPERTY =
        "qits.test.packaged-surface-it.eventstream-url";

    @Override
    public Map<String, String> getConfigOverrides() {
      String dead = "http://127.0.0.1:" + deadPort();
      return Map.ofEntries(
          Map.entry("QITS_RESOURCE_DB_URL", databaseUrl()),
          Map.entry("QITS_RESOURCE_DB_USERNAME", EmbeddedPg.USER),
          Map.entry("QITS_RESOURCE_DB_PASSWORD", EmbeddedPg.PASSWORD),
          Map.entry("QITS_RESOURCE_EVENTSTREAM_URL", eventstreamUrl()),
          Map.entry("QITS_RESOURCE_EVENTSTREAM_USERNAME", EmbeddedPg.USER),
          Map.entry("QITS_RESOURCE_EVENTSTREAM_PASSWORD", EmbeddedPg.PASSWORD),
          // A launched artifact runs in NORMAL mode, where the shipped %dev/%test darkness does not
          // apply — so the bus is turned off here by hand. There is no qits-events on the far side
          // of this IT, and enabled the subscriber would redial a stream nobody serves while the
          // sweeper paged a log that is not there. It stops publishing, sweeping and dialling; the
          // datasource above is opened and migrated either way, which is why the triple is not
          // optional.
          Map.entry("qits.eventstream.enabled", "false"),
          Map.entry("qits.maintenance.targets.projects-url", dead),
          Map.entry("qits.maintenance.targets.githost-url", dead),
          Map.entry("qits.maintenance.targets.ci-url", dead),
          Map.entry("qits.maintenance.targets.deployments-url", dead),
          Map.entry("qits.maintenance.registries.maven-url", dead),
          Map.entry("qits.maintenance.registries.npm-url", dead),
          Map.entry("qits.maintenance.registries.oci-url", dead),
          Map.entry("qits.maintenance.mirror.maven-url", dead),
          Map.entry("qits.maintenance.targets.configuration-url", dead),
          // A dead peer answers instantly, but the shipped timeout is a minute — bound the wait so
          // a broken assumption fails the IT rather than hanging it.
          Map.entry("qits.maintenance.call-timeout", "PT2S"),
          // The clock must not start a scan beside the one this IT starts itself.
          Map.entry("quarkus.scheduler.enabled", "false"));
    }

    private static synchronized String databaseUrl() {
      String recorded = System.getProperty(URL_PROPERTY);
      if (recorded != null) {
        return recorded;
      }
      // localhost resolves for the launched process too — it is a child of this JVM on this host.
      String url = EmbeddedPg.url(DATABASE);
      System.setProperty(URL_PROPERTY, url);
      return url;
    }

    private static synchronized String eventstreamUrl() {
      String recorded = System.getProperty(EVENTSTREAM_URL_PROPERTY);
      if (recorded != null) {
        return recorded;
      }
      String url = EmbeddedPg.url(EVENTSTREAM_DATABASE);
      System.setProperty(EVENTSTREAM_URL_PROPERTY, url);
      return url;
    }

    /** A port taken and released, so a connection to it is refused rather than hanging. */
    private static int deadPort() {
      try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
        return socket.getLocalPort();
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
  }

  /** What qits-gateway asserts for an authenticated operator. */
  private static RequestSpecification asAdmin() {
    return given().header("X-Qits-User", "packaged-it").header("X-Qits-Roles", "qits:admin");
  }

  @Test
  public void aScanIsA202AndItsFailureIsCarriedAllTheWayIntoAReadableRow() {
    String id =
        asAdmin()
            .contentType(ContentType.JSON)
            .body("{\"scope\":\"ALL\"}")
            .when()
            .post("/maintenance/api/scans")
            .then()
            // The 202 body goes out through Response.entity(...), which the native analysis cannot
            // see: a missing ApiWireReflection entry is a 500 here and green everywhere else.
            .statusCode(202)
            .contentType(ContentType.JSON)
            .body("id", Matchers.notNullValue())
            .extract()
            .path("id");

    // qits-projects is a dead port, so the catalog cannot be read and the scan did nothing.
    assertEquals("FAILED", awaitClosed(id));

    asAdmin()
        .when()
        .get("/maintenance/api/scans/" + id)
        .then()
        .statusCode(200)
        .body("scope", Matchers.equalTo("ALL"))
        .body("message", Matchers.containsString("the catalog could not be read"))
        .body("finishedAt", Matchers.notNullValue());

    // The round trip above would look identical against any database at all, so read the row back
    // out of the postgres this JVM handed the process through the resource triple. That is the
    // whole claim: the shipped expression resolved, and Flyway's migration survived as a classpath
    // resource — exactly the shape a native image drops.
    assertTrue(scanRows(id) == 1, "the packaged process must have written its scan row");
  }

  @Test
  public void thereIsNoAnonymousSurface() {
    given().when().get("/maintenance/api/repositories").then().statusCode(401);
  }

  @Test
  public void theRoutesAreWhereTheEdgeRoutesThemAndAMistypedOneIsNever200() {
    asAdmin().when().get("/maintenance/api/repositories").then().statusCode(200);
    asAdmin().when().get("/maintenance/api/dependencies").then().statusCode(200);
    asAdmin().when().get("/maintenance/api/bumps").then().statusCode(200);
    // The dependency graph's own noun. It is under the same /maintenance/api prefix, so it needs no
    // second entry in `quarkus.quinoa.ignored-path-prefixes` — but it is a fourth top-level route
    // and the segment it claims is asserted here like the other three.
    asAdmin().when().get("/maintenance/api/artifacts").then().statusCode(200);
    // …and the adoption journey is the fifth, for the same reason and with the same consequence:
    // one prefix entry covers the whole surface, and the segment is asserted rather than assumed.
    // It answers 400 rather than 200 because a by-release lookup names both halves of its key — and
    // 400 is this SERVICE answering, which is exactly what the assertion is about. The segment
    // reaching the client's index.html instead would be a 200 carrying html.
    asAdmin().when().get("/maintenance/api/adoption/by-release").then().statusCode(400);
    // The closure sits under /repositories rather than claiming a segment of its own, so it needs
    // no prefix entry at all — but it is the one qits-projects reads, so it is asserted here too.
    asAdmin()
        .when()
        .get("/maintenance/api/repositories/qits-nothing-here/downstream")
        .then()
        .statusCode(200);

    // The edge path-routes verbatim by prefix, so there is no unprefixed form to fall back to — and
    // at the root an unprefixed /api/repositories is the CLIENT's ground, which is why the check is
    // that it never answers as the API.
    asAdmin()
        .when()
        .get("/api/repositories")
        .then()
        .statusCode(200)
        .body(Matchers.containsString(BASE_HREF));

    String body =
        asAdmin().when().get("/maintenance/api/nope").then().statusCode(404).extract().asString();
    assertFalse(body.contains("\"pins\""), "a mistyped path must not answer with data: " + body);
  }

  /**
   * The client is mounted at the root, and its {@code <base href>} agrees. The two are configured
   * in different repositories — {@code quarkus.quinoa.ui-root-path} here, {@code baseHref} in
   * qits-platform-spa-maintenance's angular.json — and a disagreement serves a page that loads and
   * then fetches its own JavaScript from a path that 404s. Nothing on this side notices, which is
   * why the string is asserted rather than the status alone.
   *
   * <p><b>It answers anonymously, and that is not a hole in "no anonymous surface".</b> That rule is
   * about this service's DATA: every route this API serves is {@code @RolesAllowed} and the
   * test above pins a 401 for an unauthenticated read. What is served here is a static bundle with
   * no configuration in it.
   */
  @Test
  public void theClientIsServedAtTheRootWithABaseHrefThatMatches() {
    given()
        .when()
        .get("/")
        .then()
        .statusCode(200)
        .contentType(ContentType.HTML)
        .body(Matchers.containsString(BASE_HREF));
  }

  /**
   * A deep link is the SPA fallback doing its job: {@code /repositories/qits-ci} has no file behind
   * it, and {@code enable-spa-routing} is what makes a reload or a pasted link reach the Angular
   * router instead of a 404. An operator shares exactly these addresses — and the project-scoped
   * form of the same page, {@code /qits/dependencies}, has to survive a reload the same way.
   */
  @Test
  public void aDeepLinkFallsBackToTheClientSoTheAngularRouterOwnsIt() {
    given()
        .when()
        .get("/repositories/qits-ci")
        .then()
        .statusCode(200)
        .contentType(ContentType.HTML)
        .body(Matchers.containsString(BASE_HREF));

    given()
        .when()
        .get("/qits/dependencies")
        .then()
        .statusCode(200)
        .contentType(ContentType.HTML)
        .body(Matchers.containsString(BASE_HREF));
  }

  /**
   * THE HALF THAT COSTS SOMETHING IF IT IS WRONG. The SPA fallback is a late-order catch-all, so a
   * path that matches no route is rerouted to index.html and answers {@code 200 text/html} — unless
   * {@code quarkus.quinoa.ignored-path-prefixes} claims it first. The one entry there is the whole
   * {@code /maintenance} prefix, which is what makes both probes below 404s.
   *
   * <p>The stake here is the client's own polling: {@code GET /maintenance/api/bumps/<id>} while a
   * bump runs, which would hand a JSON parser an HTML document.
   *
   * <p><b>What is asserted is the status and the absence of the client's page — not the absence of
   * HTML.</b> An ignored path falls to Quarkus' own not-found handler, which answers {@code 404
   * text/html}: a correct refusal wearing a browser's content type.
   *
   * <p>Each entry in the list gets a case here. Add a literal route, add its prefix entry, add its
   * line below — the same commit.
   */
  @Test
  public void aMistypedMachinePathIs404AndNeverThePage() {
    asAdmin()
        .when()
        .get("/maintenance/api/nope")
        .then()
        .statusCode(404)
        .body(Matchers.not(Matchers.containsString(BASE_HREF)));

    given()
        .when()
        .get("/maintenance/q/nope")
        .then()
        .statusCode(404)
        .body(Matchers.not(Matchers.containsString(BASE_HREF)));
  }

  /**
   * THE OLD ADDRESS IS NO LONGER A DOOR INTO THE CLIENT. The whole {@code /maintenance} prefix is
   * ignored by SPA routing, so neither the bare segment nor the trailing-slash form serves the page.
   * An old bookmark is the edge's problem, answered there with a redirect to this host's root.
   */
  @Test
  public void theOldSegmentIsNoLongerADoorIntoTheClient() {
    given().when().get("/maintenance").then().statusCode(404);
    given()
        .when()
        .get("/maintenance/")
        .then()
        .statusCode(404)
        .body(Matchers.not(Matchers.containsString(BASE_HREF)));
  }

  @Test
  public void theReadinessEndpointIsWhereTheDeploymentLooksForIt() {
    given()
        .when()
        .get("/maintenance/q/health/ready")
        .then()
        .statusCode(200)
        .body("status", Matchers.equalTo("UP"));
  }

  @Test
  public void theApiDocumentAndItsUiAreServedUnderTheSegment() {
    // Both live under quarkus.http.non-application-root-path, which sits OUTSIDE quarkus.rest.path
    // and carries /maintenance on its own; at / they would be unreachable through qits-gateway.
    given().when().get("/maintenance/q/openapi").then().statusCode(200);
    given().when().get("/maintenance/q/swagger-ui/").then().statusCode(200);
  }

  private static String awaitClosed(String id) {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
    while (Instant.now().isBefore(deadline)) {
      String status =
          asAdmin()
              .when()
              .get("/maintenance/api/scans/" + id)
              .then()
              .statusCode(200)
              .extract()
              .path("status");
      if ("SUCCEEDED".equals(status) || "FAILED".equals(status)) {
        return status;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
    throw new AssertionError("scan " + id + " never finished");
  }

  private static int scanRows(String scanId) {
    String url = EmbeddedPg.url(DATABASE);
    try (Connection connection =
            DriverManager.getConnection(url, EmbeddedPg.USER, EmbeddedPg.PASSWORD);
        PreparedStatement query =
            connection.prepareStatement("select count(*) from mt_scan where id = ?::uuid")) {
      query.setString(1, scanId);
      try (ResultSet found = query.executeQuery()) {
        found.next();
        return found.getInt(1);
      }
    } catch (Exception e) {
      throw new IllegalStateException("could not read the resource database back", e);
    }
  }
}
