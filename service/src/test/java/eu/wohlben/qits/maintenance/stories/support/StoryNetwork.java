package eu.wohlben.qits.maintenance.stories.support;

import eu.wohlben.qits.servicemock.idp.MockIdp;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.NetworkTaps;

/**
 * <b>The whole capture wiring of this catalogue, in one call</b> — so a story class's
 * {@code @BeforeAll} is one line and no class can wire half of it.
 *
 * <p>This service is a <b>reader of other repositories</b>, which makes both halves of a diagram
 * load-bearing and for different reasons. What arrives is one operator pressing one button; what
 * leaves is the catalog, a head resolution, six manifest reads, a registry lookup per dependency and
 * — for a bump — a trigger into somebody else's pipeline and a release request opened at
 * qits-projects. A diagram drawn from the near side alone would say a scan is a POST. So there are
 * six feeds:
 *
 * <ul>
 *   <li>{@link NetworkTaps#restAssured} — the shipped incoming tap. Every request a story sends
 *       becomes {@code <actor> -> qits-platform-maintenance}, labelled {@code METHOD <scrubbed path>
 *       -> <status>} with the status this service really answered. The default skip is any path
 *       carrying a {@code /q/} segment, which is right here: {@code
 *       quarkus.http.non-application-root-path=/maintenance/q} is where health, openapi and
 *       swagger-ui live, and a diagram in which every node hangs off {@code /q/health/ready}
 *       documents nothing.
 *   <li>Five {@link NetworkCapture#source} registrations, one per {@link StoryPeers} — the outgoing
 *       half. Each is <b>cumulative</b>: the supplier hands over the whole recording every time it
 *       is asked and the framework remembers how much of it earlier stories consumed, so each peer
 *       call is attributed to exactly one story.
 *   <li>The idp's own request log, for the two calls a BOOT makes — the discovery read and the
 *       JWKS fetch that happened before any story existed.
 * </ul>
 *
 * <h2>Story order IS load-bearing, and this is why</h2>
 *
 * <p>A cumulative source is attributed by a cursor, so traffic recorded before a drain lands in
 * whichever story drains <b>first</b>. Two consequences this catalogue is built around:
 *
 * <ul>
 *   <li>The startup JWKS fetch belongs to the story that is <em>about</em> it, which means
 *       {@code TokenValidationBootstrapIT} has to run before everything else. Every other story
 *       method carries {@code @UserflowRunsAfter(TokenValidationBootstrapIT.class)}, and
 *       {@code UserflowClassOrderer} — registered as junit's secondary orderer in the test
 *       {@code application.properties} — is what turns those annotations into an order.
 *   <li>A story that reads an inventory an earlier story's scan wrote is not merely convenient
 *       ordering: "the listing answered without asking the git host" is a claim about a request that
 *       was never made, and it is only checkable once the scan that DID ask has drained its own
 *       edges. That is why the reading stories run after {@code ScanCycleIT} rather than beside it.
 * </ul>
 *
 * <h2>Idempotence</h2>
 *
 * <p>Every call below is idempotent: {@link NetworkTaps#restAssured(String)} installs at most one
 * filter per service name (RestAssured's filter list <i>appends</i>), and {@link
 * NetworkCapture#source} re-registering under an id replaces the supplier but keeps its cursor. So
 * every story class may call {@link #install()} from its own {@code @BeforeAll} without the diagram
 * doubling an edge — and a class that installs the tap must pin at least one edge, or a
 * {@code @BeforeAll} dropped in a later edit would silently empty every diagram in it.
 */
public final class StoryNetwork {

  private StoryNetwork() {}

  /** Install the incoming tap and register all six far sides. */
  public static void install() {
    NetworkTaps.restAssured(StoryTarget.SERVICE);
    farSide(StoryTarget.PROJECTS);
    farSide(StoryTarget.GITHOST);
    farSide(StoryTarget.CI);
    farSide(StoryTarget.ARTIFACTS);
    farSide(StoryTarget.MIRROR);
    idp();
  }

  /**
   * Register one peer's recording as this service's outgoing traffic.
   *
   * <p>The source id is the peer's own name and so is the edge's {@code to}: these are the services
   * the shipped configuration really dials, so the diagram names a deployment's dependency rather
   * than a test's loopback port.
   *
   * <p>The kind is {@link NetworkEdge#HTTP} on both sides of this service. It would be tempting to
   * call a registry lookup {@code package} — it does ask a package registry — but nothing is
   * downloaded: a {@code maven-metadata.xml} and a packument are documents read over HTTP to answer
   * one question, and this service never fetches an artifact at all.
   */
  private static void farSide(String peer) {
    NetworkCapture.source(
        peer,
        () ->
            StoryPeers.attach(peer).recordedRequests().stream()
                .map(request -> NetworkEdge.http(StoryTarget.SERVICE, peer, request.label()))
                .toList());
  }

  /**
   * The idp, whose whole contribution is the two calls this service made before any story ran:
   * the discovery document and the JWKS it names (qits-708).
   *
   * <p>The label carries the status the mock <i>answered</i> with, which is the half a method and a
   * path cannot supply: {@code "GET /idp/jwks -> 200"} is evidence that the keys were served, not
   * merely that they were asked for.
   */
  private static void idp() {
    NetworkCapture.source(
        MockIdp.SERVICE_NAME,
        () ->
            MockIdp.attach().recordedRequests().stream()
                .map(
                    request ->
                        NetworkEdge.http(
                            StoryTarget.SERVICE,
                            MockIdp.SERVICE_NAME,
                            request.method() + " " + request.path() + " -> " + request.status()))
                .toList());
  }
}
