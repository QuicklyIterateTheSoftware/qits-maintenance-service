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
 * whichever story drains <b>first</b>. A story that reads an inventory an earlier story's scan wrote
 * is therefore not merely convenient ordering: "the listing answered without asking the git host" is
 * a claim about a request that was never made, and it is only checkable once the scan that DID ask
 * has drained its own edges. That is why the reading stories run after {@code ScanCycleIT} rather
 * than beside it, every story method carries {@code @UserflowRunsAfter}, and {@code
 * UserflowClassOrderer} — registered as junit's secondary orderer in the test {@code
 * application.properties} — is what turns those annotations into an order.
 *
 * <h2>Who owns the boot calls is NOT decided by order</h2>
 *
 * <p>It used to be. The idp's two boot reads happen before any story exists, so under the same
 * cursor rule they landed in whichever story drained first — {@code TokenValidationBootstrapIT} in
 * the full chain, which is the story that is <em>about</em> them, but any class run alone ({@code
 * -Dit.test=ScanCycleIT}) inherited them and failed its own edge count by two. So the boot calls
 * are now claimed rather than raced for: {@code TokenValidationBootstrapIT} installs through {@link
 * #installClaimingBootCalls()}, which keeps the idp source over the whole recording, and every
 * other class installs through {@link #install()}, which — when nothing has claimed them — drops
 * the backlog the idp had already recorded the first time the source is registered. A story class
 * therefore runs without the bootstrap ahead of it and draws the diagram it draws in the chain
 * ({@code InventoryIT} still needs the scan ahead of it, for the rows, not for the edges), while an
 * idp request a story itself CAUSES still lands in that story, so {@code assertNoEdgesTo(..., idp)}
 * keeps its teeth.
 *
 * <p>The skip is fixed ONCE per JVM, at the first registration, and never recomputed. That is what
 * keeps the source prefix-stable: the framework re-registers under the same id with the cursor
 * kept, so a supplier whose offset moved between classes would shift the list under that cursor and
 * drop or repeat edges. The consequence is that the first install decides — 0 when the bootstrap
 * claimed first, the backlog otherwise — and a claim arriving after a plain install has already
 * fixed the backlog changes nothing: the boot calls stay skipped, and the bootstrap story fails its
 * own {@code assertEdge} on the JWKS read rather than drawing edges another story already passed
 * over. The orderer makes that unreachable in the full chain; it is documented because it is the
 * answer a reordering would get. The offset is parked in a system property rather than a field for
 * the reason {@code StoryProfile} parks its database url there: a second classloader's copy of this
 * class must see the value the first one fixed.
 *
 * <h2>Idempotence</h2>
 *
 * <p>Every call below is idempotent: {@link NetworkTaps#restAssured(String)} installs at most one
 * filter per service name (RestAssured's filter list <i>appends</i>), and {@link
 * NetworkCapture#source} re-registering under an id replaces the supplier but keeps its cursor. So
 * every story class may call {@link #install()} (or, for the bootstrap, {@link
 * #installClaimingBootCalls()}) from its own {@code @BeforeAll} without the diagram
 * doubling an edge — and a class that installs the tap must pin at least one edge, or a
 * {@code @BeforeAll} dropped in a later edit would silently empty every diagram in it.
 */
public final class StoryNetwork {

  /**
   * How many leading idp requests every registration in this JVM skips — the boot backlog, or 0
   * once the bootstrap has claimed it. Unset until the first idp registration fixes it.
   */
  private static final String IDP_SKIP_PROPERTY = "qits.test.userflows-it.idp-boot-skip";

  private StoryNetwork() {}

  /**
   * {@link #install()} for {@code TokenValidationBootstrapIT}, the one story that is about the boot
   * calls: it claims them, so the idp source is the whole recording, discovery read and JWKS fetch
   * included. A claim made after another class's install already fixed the skip changes nothing
   * (see the class javadoc).
   */
  public static void installClaimingBootCalls() {
    fixIdpSkip(0);
    install();
  }

  /**
   * Install the incoming tap and register all six far sides. Unless the bootstrap claimed them
   * first, the idp requests already recorded at the first call — the boot backlog — are skipped.
   */
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
   * the discovery document and the JWKS it names (qits-708). Those reach a diagram only when the
   * bootstrap claimed them; anything the idp is asked later reaches the story that asked.
   *
   * <p>The label carries the status the mock <i>answered</i> with, which is the half a method and a
   * path cannot supply: {@code "GET /idp/jwks -> 200"} is evidence that the keys were served, not
   * merely that they were asked for.
   */
  private static void idp() {
    int skip = fixIdpSkip(MockIdp.attach().recordedRequests().size());
    NetworkCapture.source(
        MockIdp.SERVICE_NAME,
        () ->
            MockIdp.attach().recordedRequests().stream()
                .skip(skip)
                .map(
                    request ->
                        NetworkEdge.http(
                            StoryTarget.SERVICE,
                            MockIdp.SERVICE_NAME,
                            request.method() + " " + request.path() + " -> " + request.status()))
                .toList());
  }

  /**
   * Fix the idp skip to {@code proposed} if nothing has fixed it yet, and answer the value in force
   * either way. Once set it never moves, which is what keeps the idp list prefix-stable under the
   * cursor the framework keeps across re-registration.
   */
  private static synchronized int fixIdpSkip(int proposed) {
    String fixed = System.getProperty(IDP_SKIP_PROPERTY);
    if (fixed != null) {
      return Integer.parseInt(fixed);
    }
    System.setProperty(IDP_SKIP_PROPERTY, Integer.toString(proposed));
    return proposed;
  }
}
