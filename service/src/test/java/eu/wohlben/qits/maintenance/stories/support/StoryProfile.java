package eu.wohlben.qits.maintenance.stories.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.maintenance.api.PackagedSurfaceIT;
import eu.wohlben.qits.maintenance.testdb.EmbeddedPg;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>One launched qits-platform-maintenance for the whole story catalogue</b>, and every seam a
 * story moves, declared once.
 *
 * <p>A {@code @TestProfile} is what failsafe launches a process for, so two profiles would be two
 * services — two boots, two databases, two inventories. For <em>this</em> service that is the
 * sharpest form of the problem: the inventory is a STORE, and every story after the first one reads
 * what an earlier story's scan wrote. A second launch would have the bump stories composing a
 * payload from an inventory nobody filled. Every story class names this one,
 * {@code TokenValidationBootstrapIT} included; it is a story class like the others and it happens
 * to be the oldest.
 *
 * <p>It extends {@link PackagedSurfaceIT.PackagedUnderTarget} rather than copying it. What a
 * launched qits-platform-maintenance needs in order to boot at all — the platform's generic resource
 * triple, {@code QITS_RESOURCE_DB_*}, which is the shipped indirection rather than the datasource
 * keys — is one answer, written out at length over there, and a second copy of it would be a second
 * place for it to drift. What is added here is only the seams these stories move.
 *
 * <p><b>Every key is a RUNTIME key.</b> A packaged process takes its configuration as {@code -D}
 * arguments on a jar that was already built, so a build-time key would be silently ignored and these
 * stories would prove the opposite of what they say. The one inherited key of that kind —
 * {@code quarkus.scheduler.enabled} — is not build-time in this Quarkus (it is a RUN_TIME config
 * root), which is exactly why it has to be turned back ON below rather than left alone.
 *
 * <h2>What is moved, and why each one</h2>
 *
 * <ul>
 *   <li><b>The database</b>, on a name of this catalogue's own. {@link PackagedSurfaceIT} and this
 *       run are two launches of one artifact and a shared schema would mean each reading rows the
 *       other wrote — {@code mt_repository} counts, an active bump's lock. Its url travels through a
 *       system property rather than a static field, because a test profile is instantiated in more
 *       than one classloader and a field written by one copy is not the field another reads.
 *   <li><b>The eight peer urls a story can reach</b>, and this is the inversion of what the parent
 *       does. (The ninth, qits-configuration, is reached by the config-pin sweep alone, and that
 *       timer is removed below — so it keeps its shipped address and nothing dials it.) Over there
 *       every target is a port nothing listens on, which is the right fixture for "a failure reaches
 *       a readable row with none of the suite's fakes involved". Here they are {@link StoryPeers}
 *       stand-ins that RECORD, because the outgoing half is where a scan's evidence is: what a
 *       manifest read looked like, which registry answered which pin, and what qits-ci was handed.
 *       Three of the eight are one stand-in behind three prefixes and two more behind two, exactly
 *       as {@code qits-artifacts} and {@code qits-mirror} really mount them — which is what
 *       keeps a maven lookup from being answerable by the mirror's route.
 *   <li><b>{@code qits.auth.machine.required}</b> and {@code quarkus.oidc.tenant-enabled} — THE
 *       GATE, as a pair. Both ship ON in normal mode, but {@link PackagedSurfaceIT.PackagedUnderTarget}
 *       turns both off for a run with no idp, so this profile turns both back on: together they are
 *       the difference between a service that validates machine bearers and one that does not, and
 *       no other suite in this repository turns them on at all. No audience travels with it any more:
 *       qits-auth-core's {@code MachineAuth} ships its own
 *       {@code qits.auth.machine.platform-audience=qits-platform} default.
 *   <li><b>{@code quarkus.oidc.auth-server-url}</b> — where the idp is. Discovery stays on, as it
 *       ships (qits-708): the issuer and the JWKS address are taken from the mock's discovery
 *       document, so the packaged artifact is otherwise exactly what ships.
 *   <li><b>{@code quarkus.oidc-client.qits.auth-server-url}</b> — the credential THIS service
 *       presents to every peer now that {@code PeerClient} sends only the bearer and never the
 *       forwarded pair (owner ruling 2026-10-18, qits-543). Pointed at {@link #idpTokenStubBaseUrl},
 *       a tiny stub answering {@code POST /idp/token} that is deliberately NOT wired into {@link
 *       StoryNetwork} — see that method's javadoc for why the mint must be answered and never
 *       drawn.
 *   <li><b>{@code qits.maintenance.call-timeout}</b>, bounded. A stand-in answers in microseconds
 *       and the outage arm closes the connection outright, so nothing here waits — but the shipped
 *       minute would turn a wrong assumption into a hung IT rather than a failed one.
 * </ul>
 *
 * <h2>The clock: one timer alive, and it is the one a story drives</h2>
 *
 * <p>The parent switches the scheduler off, which is right for a suite that starts all its own work.
 * This catalogue cannot: <b>a bump is finished by the sweep and by nothing else</b>. {@code
 * BumpService.dispatch} sends the trigger and returns with the row RUNNING; the poll that reads the
 * ci run and closes the row is only ever called from {@code BumpPollSchedule}. So the scheduler goes
 * back on — and then every other timer is silenced at its own shipped key, so that the only
 * background work that can draw an arrow is work a story is waiting for:
 *
 * <ul>
 *   <li>{@code scan.internal.cron} and {@code scan.external.cron} are set to {@code off}, which the
 *       scheduler's own {@code SchedulerUtils.isOff} understands — the trigger is not registered at
 *       all, rather than registered and skipped. {@code scan.enabled=false} says the same thing a
 *       second time, from the service's own side.
 *   <li>{@code bump.poll-interval} is a second rather than the shipped fifteen. The sweep is a no-op
 *       whenever no bump is active — it reads the store and queues nothing — so the only stories it
 *       can reach are the two that are holding a bump open on purpose.
 *   <li>{@code bump.internal.auto} is false, which is what silences the DISPATCHER sharing that
 *       interval. It was silent here by accident until 2026-09-11: it did nothing until a cron
 *       opened a window and this profile removes every cron. Now that it arms itself on debt, a
 *       tick landing mid-story would ask qits-ci for its queue and qits-projects about a held
 *       bump's release, and those arrows would be recorded against whichever story was draining.
 * </ul>
 *
 * <p><b>Two paths are therefore NOT covered by any story here, and that is a stated gap.</b> The
 * clock's own bumping ({@code BumpDispatcher}) is switched off just above, and {@code
 * RestartRecovery} resuming a bump across a restart needs a second boot of one process. Both keep their coverage in {@code MaintenanceApiTest}, which drives the sweep by hand.
 * A story that waited out a six-hour cron would be indistinguishable from a story that hung.
 */
public class StoryProfile extends PackagedSurfaceIT.PackagedUnderTarget {

  /**
   * The audience this service enforces, and it is the SHIPPED value:
   * {@code quarkus.oidc.token.audience=qits-platform} is spelled as a literal in
   * {@code application.properties}, so the audience under test is the deployed one and there is no
   * expression to feed. It is the PLATFORM's rather than this service's — qits-idp stamps
   * it onto every token it mints, whatever the client asked for — which is why every caller a story
   * presents carries it and the roles are what tell them apart.
   */
  public static final String PLATFORM_AUDIENCE = "qits-platform";

  /**
   * An audience that is genuinely not this platform's, which is what the denied story presents. A
   * peer's own name would not do: every token qits-idp mints carries {@code qits-platform},
   * so a sibling service's bearer is admitted here and its roles decide what it may do. The refusal
   * this catalogue can honestly claim is of a token cut for somewhere else entirely.
   */
  public static final String FOREIGN_AUDIENCE = "some-other-platform";

  /** This catalogue's own database on the one embedded postgres. */
  public static final String DATABASE = "maintenance_userflows_it";

  /**
   * And its own outbox database, for the same reason the store is its own: two launches of one
   * artifact must not share a claim ledger. The bus itself stays dark — the parent's
   * {@code qits.eventstream.enabled=false} is inherited — so nothing here dials, and no story draws
   * an event edge.
   */
  public static final String EVENTSTREAM_DATABASE = "maintenance_userflows_it_eventstream";

  /** Where the url is parked for whichever copy of this class is asked second. */
  private static final String URL_PROPERTY = "qits.test.userflows-it.db-url";

  private static final String EVENTSTREAM_URL_PROPERTY = "qits.test.userflows-it.eventstream-url";

  /** Where the idp-token stub parks its port, so a second classloader attaches to the first. */
  private static final String IDP_TOKEN_STUB_PORT_PROPERTY = "qits.test.userflows-it.idp-token-port";

  /**
   * The access token the stub mints — opaque is fine, since no story peer validates it. Public so a
   * story can assert that {@code PeerClient} presented exactly this bearer and not, say, nothing at
   * all ({@code ScanCycleIT}'s "outbound, this service is a MACHINE" proof, qits-543).
   */
  public static final String IDP_TOKEN_STUB_TOKEN = "story-maintenance-machine-token";

  /** The {@code Authorization} header value every outbound {@code PeerClient} call must carry. */
  public static final String IDP_TOKEN_STUB_AUTHORIZATION = "Bearer " + IDP_TOKEN_STUB_TOKEN;

  @Override
  public Map<String, String> getConfigOverrides() {
    MockIdp idp = MockIdp.ensureStarted();
    // The stand-ins are started HERE rather than in a story class, because the launched process
    // needs their addresses in its command line — which is built from exactly this map. `named` is
    // start-or-attach, so the second classloader to arrive gets the first one's ports.
    StoryCatalog.arm();

    // LinkedHashMap rather than Map.of: the order is the order this file explains them in, and a
    // reader diffing a launch command should find them in it.
    Map<String, String> overrides = new LinkedHashMap<>(super.getConfigOverrides());

    overrides.put("QITS_RESOURCE_DB_URL", databaseUrl());
    overrides.put("QITS_RESOURCE_EVENTSTREAM_URL", eventstreamUrl());

    overrides.put("qits.maintenance.targets.projects-url", url(StoryTarget.PROJECTS));
    overrides.put("qits.maintenance.targets.githost-url", url(StoryTarget.GITHOST));
    overrides.put("qits.maintenance.targets.ci-url", url(StoryTarget.CI));
    overrides.put(
        "qits.maintenance.registries.maven-url",
        url(StoryTarget.ARTIFACTS, StoryTarget.MAVEN_REGISTRY_PREFIX));
    overrides.put(
        "qits.maintenance.registries.npm-url",
        url(StoryTarget.ARTIFACTS, StoryTarget.NPM_REGISTRY_PREFIX));
    overrides.put(
        "qits.maintenance.registries.oci-url",
        url(StoryTarget.ARTIFACTS, StoryTarget.OCI_REGISTRY_PREFIX));
    overrides.put(
        "qits.maintenance.mirror.maven-url",
        url(StoryTarget.MIRROR, StoryTarget.MAVEN_MIRROR_PREFIX));
    overrides.put(
        "qits.maintenance.mirror.npm-url",
        url(StoryTarget.MIRROR, StoryTarget.NPM_MIRROR_PREFIX));
    // qits-artifacts' own API — the docs store a bump reads its changelog listings from (qits-893),
    // and the SBOM route beside it — is a bare host. Overridden like every other peer above, so the
    // stand-in that answers it is the one armed under this diagram's name rather than whatever the
    // shipped default would reach.
    overrides.put("qits.maintenance.targets.artifacts-url", url(StoryTarget.ARTIFACTS));
    overrides.put("qits.maintenance.call-timeout", "PT5S");

    // The clock. See the class comment: the sweep is what closes a bump, so the scheduler is on and
    // everything else it would run is removed at the key that removes it.
    overrides.put("quarkus.scheduler.enabled", "true");
    overrides.put("qits.maintenance.scan.enabled", "false");
    overrides.put("qits.maintenance.scan.internal.cron", "off");
    overrides.put("qits.maintenance.scan.external.cron", "off");
    // The sbom sweep is the third timer and it goes the same way as the two scans: no story drives
    // an ingest, and a sweep firing on the hour mid-catalogue would draw an artifacts arrow into
    // whichever story happened to be draining.
    overrides.put("qits.maintenance.sbom.sweep-cron", "off");
    // The daily SBOM check probes qits-artifacts and would draw its arrows into a story too.
    overrides.put("qits.maintenance.sbom.check.cron", "off");
    overrides.put("qits.maintenance.bump.poll-interval", "1s");
    // AND THE DISPATCHER IS OFF, at the shipped key that turns the clock's half of bumping off.
    // It used to be silent here by accident: it did nothing at all until a 02:00 cron opened a
    // window, and this profile removes every cron. Since the dispatch arms itself on DEBT, a tick
    // landing mid-story asks qits-ci for its queue and qits-projects about a held bump's release —
    // arrows into whichever story happened to be draining, which is the same reason the scans and
    // the sbom sweep are off. No story drives a dispatch; the gate has its own tests.
    overrides.put("qits.maintenance.bump.internal.auto", "false");
    // The legacy group-branch sweep (qits-1133) rides the automation-branch sweep's timer — the
    // first pass five minutes after boot — and is no more of a concern here than that sweep is.

    overrides.put("qits.auth.machine.required", "true");
    overrides.put("quarkus.oidc.tenant-enabled", "true");
    // No audience override needed beside it: qits-auth-core's MachineAuth now ships its own
    // qits.auth.machine.platform-audience=qits-platform default, the same platform audience
    // quarkus.oidc.token.audience enforces as a literal over there, so there is nothing left to
    // state here.
    overrides.put("quarkus.oidc.auth-server-url", idp.baseUrl());

    // The credential PeerClient now presents on every outbound call (qits-543): the qits named
    // oidc client, pointed at a stub that is NOT registered with StoryNetwork, so the mint is
    // answered and never drawn. See idpTokenStubBaseUrl's javadoc.
    overrides.put("quarkus.oidc-client.qits.auth-server-url", idpTokenStubBaseUrl() + "/idp");
    overrides.put("quarkus.oidc-client.qits.credentials.secret", "story-maintenance-client-secret");

    return Map.copyOf(overrides);
  }

  private static String url(String peer) {
    return StoryPeers.named(peer).baseUrl();
  }

  private static String url(String peer, String prefix) {
    return StoryPeers.named(peer).baseUrl(prefix);
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

  /**
   * The base url of a tiny stub that answers {@code POST /idp/token} with a canned bearer, started
   * once per JVM and parked by port the way {@link #databaseUrl} parks a url — a second classloader
   * attaches to the first instance's port rather than starting a second server.
   *
   * <p><b>Deliberately a server of its own, and deliberately outside {@link StoryPeers} /
   * {@link StoryNetwork}.</b> quarkus-oidc-client caches the bearer it mints and re-mints only once
   * it expires, so the mint belongs to whichever story makes this process's FIRST outbound peer call
   * in the run — which depends on story order, exactly the instability {@code
   * qits-deployments-service}'s equivalent stub (commit {@code c8e9340}) already named. Answering it
   * from a server nothing registers with {@link eu.wohlben.qits.userflows.NetworkCapture} is what
   * keeps it off every diagram: there is no source to attribute it to, so no story's edge count or
   * {@code assertNoEdgesTo} can see it move. The token says {@code expires_in: 3600} and is simply
   * answered, the same way the deployments stub's is.
   */
  private static synchronized String idpTokenStubBaseUrl() {
    String port = System.getProperty(IDP_TOKEN_STUB_PORT_PROPERTY);
    if (port != null) {
      return "http://localhost:" + port;
    }
    HttpServer server;
    try {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException("could not start the idp-token stub", e);
    }
    server.createContext("/idp/token", StoryProfile::answerIdpToken);
    server.start();
    String url = "http://localhost:" + server.getAddress().getPort();
    System.setProperty(IDP_TOKEN_STUB_PORT_PROPERTY, String.valueOf(server.getAddress().getPort()));
    return url;
  }

  private static void answerIdpToken(HttpExchange exchange) throws IOException {
    byte[] body =
        ("{\"access_token\":\""
                + IDP_TOKEN_STUB_TOKEN
                + "\",\"token_type\":\"Bearer\",\"expires_in\":3600}")
            .getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }
}
