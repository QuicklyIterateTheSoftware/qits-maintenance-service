package eu.wohlben.qits.maintenance.stories.support;

import eu.wohlben.qits.maintenance.api.PackagedSurfaceIT;
import eu.wohlben.qits.maintenance.testdb.EmbeddedPg;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import java.net.URI;
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
 *       as {@code qits-artifacts} and {@code qits-platform-mirror} really mount them — which is what
 *       keeps a maven lookup from being answerable by the mirror's route.
 *   <li><b>{@code qits.auth.machine.required}</b> — THE GATE. The shipped tenant is
 *       {@code quarkus.oidc.tenant-enabled=${qits.auth.machine.required:false}}, so this one key is
 *       the difference between a service that validates machine bearers and one that does not, and
 *       no other suite in this repository turns it on at all. No audience travels with it any more:
 *       qits-auth-core's {@code MachineAuth} ships its own
 *       {@code qits.auth.machine.platform-audience=qits-platform} default.
 *   <li><b>{@code quarkus.oidc.auth-server-url}</b> — where the idp is. Discovery stays off and
 *       {@code jwks-path} stays {@code jwks}, joined onto this URL, so the packaged artifact is
 *       otherwise exactly what ships.
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
    // qits-artifacts' own API — the docs store a bump reads its changelog listings from (qits-893),
    // and the SBOM route beside it — is a bare host. Left at its default it would leave through the
    // mirror's proxy below and be drawn as an arrow into the wrong peer.
    overrides.put("qits.maintenance.targets.artifacts-url", url(StoryTarget.ARTIFACTS));
    // THE npm MIRROR HAS NO KEY TO POINT (qits-472): the launched process derives
    // http://dev-qits-platform-mirror:8080/npm/npmjs itself. So the stand-in is reached the way the
    // derived address really goes out — as the JVM's plain-http proxy, which the JDK HttpClient
    // honours by default. Every other peer above is on 127.0.0.1, which the default
    // http.nonProxyHosts exempts, so only the one host no loopback stub could answer as is
    // proxied, and the request the stub records is the absolute-form one the derivation produced.
    URI mirror = URI.create(url(StoryTarget.MIRROR));
    overrides.put("http.proxyHost", mirror.getHost());
    overrides.put("http.proxyPort", String.valueOf(mirror.getPort()));
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
    // AND THE CUTOVER SWITCH IS OFF (qits-1133 R2). It ships ON, which retires group bumps: the
    // group door answers 410 and nothing writes a maintenance/<group> branch. The bump stories and
    // the refusal stories narrate that door, which an emergency still restores with the switch off,
    // so the catalogue keeps telling it until R5 removes the door and these stories with it. Off,
    // the legacy sweep riding the hourly timer is a no-op too, and draws no arrow mid-story.
    overrides.put("qits.maintenance.pre-run.upstream.enabled", "false");

    overrides.put("qits.auth.machine.required", "true");
    // No audience override needed beside it: qits-auth-core's MachineAuth now ships its own
    // qits.auth.machine.platform-audience=qits-platform default, the same platform audience
    // quarkus.oidc.token.audience enforces as a literal over there, so there is nothing left to
    // state here.
    overrides.put("quarkus.oidc.auth-server-url", idp.baseUrl());

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
}
