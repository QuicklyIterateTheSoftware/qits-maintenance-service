package eu.wohlben.qits.maintenance.bus;

import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.ReleaseOrigin;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.sbom.SbomIngestService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * <b>An internal package was published, so its latest version moves now rather than at the next
 * poll.</b> Consumes qits-ci's {@code SoftwareRelease} — one per declared artifact of a green
 * release pipeline — and writes it into {@code mt_latest}, forward only.
 *
 * <p><b>It makes TWO writes and they are two different facts.</b> {@code mt_latest} says a version
 * EXISTS — one column per dependency, moved forward only. An {@code mt_artifact} row says THIS
 * release has a bill of materials worth reading — one row per released version, PENDING, which the
 * ingest picks up off the queue afterwards. Neither is derivable from the other: a column holding
 * the newest version says nothing about what any release contained, and a graph of one release says
 * nothing about whether a higher one exists. See {@code SbomIngestService}.
 *
 * <p><b>…and a DAEMON release makes the second write only</b> — the artifact row, PENDING, whose
 * document is fetched and ingested like any other artifact's. {@code daemon} used to be discarded
 * here with a DEBUG, on the reading — written into {@code mt_artifact.ecosystem}'s own V3 comment as
 * a decision — that nothing in any manifest pins a daemon binary. That sentence stopped being true
 * when the qits CLI's version became a pom pin: qits-ci pins {@code
 * eu.wohlben.qits:qits-platform-access-cli-binary}, whose version IS the {@code daemons}-store
 * coordinate of the binary the same release published, and with no {@code mt_artifact} row the GC
 * had nothing to derive a keep from — the store collects at {@code window=P0D} keeping the last two
 * versions, so the pin rotted and the release pipelines that fetch the CLI 404'd. See {@link
 * #daemon}, {@code control/CarriedDaemons}, and {@code Ecosystem.DAEMON_WIRE_NAME} for why the word
 * is a string here and not a fifth enum constant.
 *
 * <p>This is the fast path the six-hourly internal scan used to be. Everything downstream is
 * unchanged and needs no help: pending is computed from {@code mt_pin ⋈ mt_latest} on every read, so
 * a column that moved is a repository page that shows an arrow the moment it is refreshed, and a
 * scheduled scan that finds a group with pending changes asks for its bump exactly as before. The
 * daily scan is the reconciliation belt behind this — see the schedules block in the maintenance
 * jar's {@code META-INF/microprofile-config.properties}.
 *
 * <h2>Why the bus rather than a call, and why forward-only</h2>
 *
 * <p>Only qits-ci knows that a package is in a registry, and it knows it at the instant its release
 * pipeline goes green. The alternative — qits-ci reaching into this service on every release — would
 * make an inventory write a synchronous leg of somebody else's release and lose it whenever this
 * service was mid-cutover. A {@link QitsDurableEventListener} closes that: a release published while
 * this process was restarting is caught up from the log.
 *
 * <p><b>The write is {@link MaintenanceStore#recordLatestIfNewer}, never {@code recordLatest}.</b> A
 * poll asks a registry what the newest version is and the answer replaces what was there; an
 * announcement is only ever evidence that THIS version exists, never that a higher one does not. A
 * catch-up frame from before a scan would otherwise rewind a column the scan had already moved, and
 * every pin of that dependency would read as up to date until the next scan. The guard is
 * {@code VersionOrder}'s — the same comparison the pending rule makes.
 *
 * <h2>The match, and why by the wire strings</h2>
 *
 * <p>{@code packageType} is qits-ci's own vocabulary and its values are the literals {@code npm},
 * {@code maven}, {@code docker}, {@code daemon} and {@code docs}. Three of them are ecosystems this
 * service inventories and {@link #ECOSYSTEMS} is that map; {@code daemon} is the fourth and takes
 * the artifact row alone; {@code docs}, and anything a future qits-ci adds, is a DEBUG and a settled
 * event. {@code docs} stays out on the rule that was always the real one — <b>nothing pins it</b>.
 * An api-docs bundle is named by no manifest and fetched by no build, so a row for one would be a
 * keep nobody asked for; a daemon binary is named by a pom property that resolves to it.
 *
 * <p>They are compared as the literal wire values rather than through qits-ci's {@code
 * CiArtifact.Type} enum, which lives in a module this service does not and should not depend on. The
 * payload is decoded into a local record by the eventstream lib's {@link CanonicalJson}, so no
 * qits-ci jar is on the path at all; {@code bus/ForeignEventContractTest} is where that transcription
 * is pinned, and {@code bus/EventWireReflection} is where the record is registered for the native
 * image.
 *
 * <h2>{@code packageName} joins mt_pin's naming directly</h2>
 *
 * <p>That is the whole reason no translation table is needed here, and it holds per ecosystem
 * because both sides copied the manifest rather than inventing a spelling:
 *
 * <ul>
 *   <li><b>maven</b> — {@code eu.wohlben.qits:qits-eventstream}, {@code groupId:artifactId}, which is
 *       exactly what {@code PomParser} records as a pin's name;
 *   <li><b>npm</b> — {@code @qits/ui-components}, the scoped package name {@code NpmParser} records;
 *   <li><b>docker</b> — {@code qits/qits-ci}, unqualified because no registry-qualified reference is
 *       portable across this platform, which is also why {@code DockerParser} records the image
 *       without its tag or host.
 * </ul>
 *
 * <p><b>ROLLOUT, Deploy B step: confirm the maven spelling against ONE live frame.</b> The three
 * above are read off the {@code artifacts:} declarations committed in the platform's own
 * {@code .config/qits/ci-event-release.yml} files, which is what qits-ci copies into the payload
 * verbatim — so they are as good as a static reading gets, and they are still a static reading of
 * another repository's file. Maven is the one worth checking by hand because it is the only name
 * carrying a separator: a frame that arrived as {@code qits-eventstream} rather than
 * {@code eu.wohlben.qits:qits-eventstream} would match no pin, write a row nothing joins to, and say
 * nothing about it at any log level above DEBUG. Read one real frame's payload after the first
 * internal release following this deploy, and compare it with a {@code mt_pin.name} of the same
 * artifact.
 *
 * <h2>{@code repository} does NOT, and it is the one field that is translated</h2>
 *
 * <p>The same payload's {@code repository} is qits-projects' repository ROW ID — measured live on
 * 2026-09-02 — while everything downstream of {@code mt_artifact.repository} joins on the catalog
 * NAME. So this listener resolves it before the row is written, through
 * {@code MaintenanceStore.repositoryName} and {@code mt_repository.catalog_id} (V5), and an
 * unknown spelling is stored verbatim rather than dropped. See {@link #repository}.
 *
 * <h2>Failure: what is retried and what is swallowed</h2>
 *
 * <p><b>Retryable, and left to throw:</b> anything the store raises out of its own database work. A
 * postgres that will not answer is a condition rather than a verdict, so the claim rolls back and
 * the release stays owed for the next catch-up sweep.
 *
 * <p><b>Poison, and swallowed with a WARN:</b> a payload that will not parse, and one that names no
 * package or no version. The same bytes fail identically on every later offer, and a throw would
 * hold this consumer's watermark behind one bad event for ever — the seam has no dead letter and
 * says so.
 */
@ApplicationScoped
public class SoftwareReleaseListener implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(SoftwareReleaseListener.class);

  /**
   * This consumption's storage key: it names every {@code consumed_event} row and the {@code
   * consumer_watermark} this listener is caught up by.
   *
   * <p><b>Never change it.</b> A new value is a brand-new consumer initializing at the HEAD of the
   * log, silently skipping every release in between — and the old watermark is orphaned. It names
   * the consumption, not the class, and it survives a rename of either.
   */
  static final String CONSUMER_ID = "maintenance-internal-latest";

  /** The one event name this listener wants — {@code SoftwareRelease}'s signature. */
  static final String SIGNATURE = "SoftwareRelease";

  /**
   * qits-ci's {@code packageType} vocabulary, as the wire spells it, mapped onto the three
   * ecosystems this inventory holds.
   *
   * <p>{@code daemon} is deliberately absent and is NOT an omission: it is handled one branch below,
   * as the string it is. Putting it in this map would make it an {@link Ecosystem}, which would put
   * a daemon binary in {@code mt_latest} and in the pending rule — two questions nothing can answer
   * about it. (The SBOM route is NOT one of them: it is keyed by the released artifact's type, so a
   * daemon's document is fetched by the stored word without the word becoming an ecosystem.) {@code docs} is absent for the original reason, which
   * still holds: nothing pins an api-docs bundle.
   */
  static final Map<String, Ecosystem> ECOSYSTEMS =
      Map.of(
          "maven", Ecosystem.MAVEN,
          "npm", Ecosystem.NPM,
          "docker", Ecosystem.DOCKER);

  /**
   * The {@code SoftwareRelease} payload fields this listener reads, as a local record bound by
   * {@link CanonicalJson} — a copy of qits-ci's wire shape rather than a dependency on its module.
   *
   * <p>{@code eventId} and {@code occurredAt} are not here and must not be: they are {@code
   * QitsEvent}'s own accessors, which the library's canonical mix-in keeps out of every payload, so
   * identity and time are read off the frame. Public so {@code bus/EventWireReflection} and the
   * contract test can name it.
   */
  public record SoftwareReleasePayload(
      String repository,
      String version,
      String packageType,
      String packageName,
      String projectId,
      String section,
      String runId) {

    /**
     * The three facts the daily SBOM check reads off the row: the project a ticket is filed in, the
     * release.yml section ({@code artifacts}|{@code contracts}), and the qits-ci run. All three are
     * optional on the wire — a frame from a qits-ci that predates {@code section} and {@code runId}
     * leaves the keys out and binds them null — and a {@code runId} that is not a uuid is dropped
     * rather than failing the decode, because one malformed optional field must not make a whole
     * release unreadable.
     */
    ReleaseOrigin origin() {
      return new ReleaseOrigin(trimmed(projectId), normalizedSection(), uuid(runId));
    }

    private String normalizedSection() {
      String value = trimmed(section);
      return value == null ? null : value.toLowerCase(java.util.Locale.ROOT);
    }

    private static UUID uuid(String value) {
      String text = trimmed(value);
      if (text == null) {
        return null;
      }
      try {
        return UUID.fromString(text);
      } catch (IllegalArgumentException notAUuid) {
        return null;
      }
    }
  }

  @Inject MaintenanceStore store;

  @Inject eu.wohlben.qits.maintenance.automation.UpstreamReplan upstream;

  /**
   * The second write this listener makes, and it is a different fact from the first.
   *
   * <p>{@code mt_latest} records that a version EXISTS — one column per dependency, moved forward
   * only. The artifact row records that THIS release has contents worth reading, one row per
   * released version, and it is what the whole dependency graph is built from afterwards. Neither
   * is derivable from the other: a column holding the newest version says nothing about what any
   * release contained, and a graph of one release says nothing about whether a higher one exists.
   */
  @Inject SbomIngestService sboms;

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(SIGNATURE);
  }

  /**
   * {@code selects} is left at its default — every {@code SoftwareRelease} is claimed, and the
   * package type is filtered here.
   *
   * <p>The seam asks a predicate to be pure and cheap, and "is this an ecosystem we inventory" is
   * both. What it is not is worth a claim row saved: the alternative is a predicate that has to
   * decode the payload to answer, asked once and then asked again in this method, and one that
   * throws on an unreadable payload leaves the event owed for ever. The type map is three entries
   * and a settle is a return.
   */
  @Override
  public void onFrame(EventFrame frame) {
    SoftwareReleasePayload release = decode(frame);
    if (release == null) {
      // Warned in decode. Returning settles it: the same bytes fail identically on every later
      // offer, and an event nothing can read must not hold the watermark.
      return;
    }
    String packageType = packageType(release);
    Ecosystem ecosystem = ECOSYSTEMS.get(packageType);
    boolean daemon = Ecosystem.DAEMON_WIRE_NAME.equals(packageType);
    if (ecosystem == null && !daemon) {
      // An api-docs bundle, or a type qits-ci added after this was written. Both are ordinary
      // traffic on a bus this consumer shares, so DEBUG rather than WARN.
      LOG.debugf(
          "%s %s released %s of type '%s', which this inventory does not hold; it is settled",
          frame.name(), frame.id(), release.packageName(), release.packageType());
      return;
    }
    String name = trimmed(release.packageName());
    String version = trimmed(release.version());
    if (name == null || version == null) {
      LOG.warnf(
          "%s %s carries no (packageName, version) to record a latest under; it is skipped",
          frame.name(), frame.id());
      return;
    }
    if (daemon) {
      daemon(frame, release, name, version);
      return;
    }
    // The store throws out of here on a database that will not answer, which is exactly right: the
    // claim rolls back and the next sweep offers this release again.
    boolean moved =
        store.recordLatestIfNewer(ecosystem, name, version, "event:" + frame.id(), Instant.now());
    if (moved) {
      LOG.infof(
          "%s %s moved the latest %s %s to %s", frame.name(), frame.id(), ecosystem, name, version);
      // A queue submit, never a call: the claim transaction holds no HTTP open (qits-1133).
      upstream.latestMoved(ecosystem, name);
    } else {
      LOG.debugf(
          "%s %s announced %s %s at %s, which is not newer than what is recorded; nothing moved",
          frame.name(), frame.id(), ecosystem, name, version);
    }

    // AND THE SECOND FACT, which is a different one. `mt_latest` says a version EXISTS; the artifact
    // row says this release has a bill of materials worth reading. It is written whether or not the
    // column above moved, because a catch-up frame that is not the newest release is still a
    // release whose contents nothing has recorded.
    //
    // IT IS A ROW AND A QUEUE SUBMIT, never a fetch. The claim and this write are one transaction:
    // holding it open across an HTTP call to qits-artifacts would make a slow peer into an event
    // redelivered for ever, and this listener's watermark would sit behind it. The row IS the
    // outbox — see SbomIngestService.
    sboms.announced(
        ecosystem, name, version, repository(release), occurredAt(frame), release.origin());
  }

  /**
   * <b>A released daemon binary: the artifact row, and NOT the latest column.</b>
   *
   * <p>The row is what the GC's keep-set is derived from — {@code control/CarriedDaemons} turns a
   * pom pin on the co-released maven coordinate into a keep for this binary — so a daemon release
   * this listener discarded was a version the {@code daemons} store was free to collect out from
   * under a pin that still named it. It is also the binary's SBOM outbox: written PENDING, its
   * fetch queued off the claim exactly as {@link SbomIngestService#announced} queues a jar's, and
   * its components joined into the graph once read.
   *
   * <p><b>{@code mt_latest} is deliberately not written, and it is not an omission either.</b> That
   * column exists to be compared against a pin of the same ecosystem, and there is no such pin: what
   * a pom holds is the MAVEN coordinate, whose own {@code mt_latest} is what moves it. A daemon
   * column would also be a column {@code LatestResolver} cannot refresh — there is no registry to
   * ask — so the daily scan would leave it at whatever the last frame said and the freshness of the
   * row would be unknowable. And {@code recordLatestIfNewer} takes an {@link Ecosystem}, which
   * {@code daemon} is not.
   *
   * <p>The repository translation and the frame's moment are the same two the ordinary path uses,
   * for the same two reasons; so is the failure policy — the store throws only on a database that
   * will not answer, and the claim rolls back.
   */
  private void daemon(
      EventFrame frame, SoftwareReleasePayload release, String name, String version) {
    sboms.announcedDaemon(
        name, version, repository(release), occurredAt(frame), release.origin());
    LOG.infof(
        "%s %s released the daemon binary %s %s; it is recorded so a pin of its co-released"
            + " artifact can keep it",
        frame.name(), frame.id(), name, version);
  }

  /**
   * <b>The frame's {@code repository} is qits-projects' ROW ID, and this is where it becomes a
   * NAME.</b>
   *
   * <p>Measured live on 2026-09-02: the field arrives as {@code daf73ae4-…}, not as
   * {@code qits-eventstream-javalib}. Every read on this side joins {@code mt_artifact.repository}
   * to a repository NAME — the repository detail page's transitives, {@code GET
   * /repositories/{name}/dependents}, and {@code DependentDto.repository}, which the client renders
   * as the link to that page — so a uuid stored here is a row that answers nobody and says nothing
   * about it. The translation belongs at the WRITE, once per release, rather than at every read.
   *
   * <p>{@code MaintenanceStore.repositoryName} is one query and answers all three cases: a name
   * stays a name, a known {@code catalog_id} becomes its row's name, and <b>an unknown spelling is
   * kept verbatim</b> — a release of a repository this inventory has never scanned still happened,
   * and dropping the string would lose the fact along with the spelling.
   *
   * <p>It is inside this listener's existing failure policy and needs no arm of its own: the store
   * throws only on a database that will not answer, which is the retryable case that is already
   * left to propagate.
   */
  private String repository(SoftwareReleasePayload release) {
    return store.repositoryName(trimmed(release.repository()));
  }

  /**
   * The publisher's moment, off the frame.
   *
   * <p>Never this service's clock: {@code occurred_at} is what "the newest release of each
   * dependent" is ordered by, and a catch-up frame processed today announces a release from
   * yesterday. Stamping now would put every event a disconnect delayed at the front of an ordering
   * it does not belong at the front of.
   */
  private static Instant occurredAt(EventFrame frame) {
    return frame.occurredAt() == null ? Instant.now() : frame.occurredAt();
  }

  /** The type as the wire spells it, lower-cased so a publisher's capitalisation cannot matter. */
  private static String packageType(SoftwareReleasePayload release) {
    String type = trimmed(release.packageType());
    return type == null ? "" : type.toLowerCase(java.util.Locale.ROOT);
  }

  /** Null on anything that will not read as this payload, warned about once, never thrown. */
  private static SoftwareReleasePayload decode(EventFrame frame) {
    try {
      return CanonicalJson.payloadTo(frame.payload(), SoftwareReleasePayload.class);
    } catch (RuntimeException unreadable) {
      LOG.warnf(
          "%s %s carried an unreadable payload: %s",
          frame.name(), frame.id(), unreadable.toString());
      return null;
    }
  }

  private static String trimmed(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
