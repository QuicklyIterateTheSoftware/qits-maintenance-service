package eu.wohlben.qits.maintenance.bus;

import static eu.wohlben.qits.maintenance.bus.ForeignEventContractTest.frame;
import static eu.wohlben.qits.maintenance.bus.ForeignEventContractTest.softwareReleasePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.maintenance.latest.VersionOrder;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.ReleaseOrigin;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.sbom.SbomIngestService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The listener's decision, in isolation from the bus and the database.
 *
 * <p>{@link RecordingStore} stands in for the write seam and keeps its own {@code (ecosystem, name)}
 * map, so the forward-only rule is exercised for real rather than mocked away — the stand-in applies
 * the same {@link VersionOrder} comparison {@code MaintenanceStore.recordLatestIfNewer} does, and
 * {@code MaintenanceStoreTest} is where that method is proved against a real PostgreSQL. This test
 * is about which frames reach it and with what.
 *
 * <p>The payloads come from {@link ForeignEventContractTest}'s transcription through the real
 * {@link eu.wohlben.qits.eventstream.control.CanonicalJson}, so the bytes here are the bytes qits-ci
 * publishes rather than a JSON literal this file guessed.
 */
class SoftwareReleaseListenerTest {

  /**
   * A {@link MaintenanceStore} that writes to a map instead of a database, applying the same
   * forward-only rule.
   */
  private static final class RecordingStore extends MaintenanceStore {

    final Map<String, String> latest = new LinkedHashMap<>();
    final Map<String, String> sourceUrls = new LinkedHashMap<>();
    final List<String> writes = new ArrayList<>();

    /**
     * The catalog, as {@code mt_repository} holds it: a name per catalog id. The real method is one
     * query over {@code name = ?1 or catalog_id = ?1} and {@code MaintenanceStoreTest} is where it
     * is proved against a real PostgreSQL; here the three answers are what the listener is judged
     * on.
     */
    final Map<String, String> nameByCatalogId = new LinkedHashMap<>();

    RuntimeException failWith;

    @Override
    public String repositoryName(String spelling) {
      if (failWith != null) {
        throw failWith;
      }
      if (spelling == null) {
        return null;
      }
      if (nameByCatalogId.containsValue(spelling)) {
        return spelling;
      }
      return nameByCatalogId.getOrDefault(spelling, spelling);
    }

    @Override
    public boolean recordLatestIfNewer(
        Ecosystem ecosystem, String name, String version, String sourceUrl, Instant now) {
      if (failWith != null) {
        throw failWith;
      }
      String key = ecosystem.wireName() + ":" + name;
      String current = latest.get(key);
      if (current != null && !VersionOrder.newer(ecosystem, current, version)) {
        return false;
      }
      latest.put(key, version);
      sourceUrls.put(key, sourceUrl);
      writes.add(key + "=" + version);
      return true;
    }
  }

  /**
   * The other half of the write seam: the outbox row an announced release leaves behind.
   *
   * <p>It records the ARGUMENTS rather than doing anything, because what this test is about is
   * which frames reach it and with what — the row's own behaviour (idempotent by coordinate,
   * PENDING, fetched off the queue afterwards) is {@code SbomIngestServiceTest}'s, against a real
   * PostgreSQL.
   */
  private static final class RecordingIngest extends SbomIngestService {

    record Announced(
        Ecosystem ecosystem,
        String name,
        String version,
        String repository,
        Instant occurredAt,
        ReleaseOrigin origin) {}

    final List<Announced> announced = new ArrayList<>();

    @Override
    public UUID announced(
        Ecosystem ecosystem,
        String name,
        String version,
        String repository,
        Instant occurredAt,
        ReleaseOrigin origin) {
      announced.add(new Announced(ecosystem, name, version, repository, occurredAt, origin));
      return UUID.randomUUID();
    }

    /**
     * The daemon arm records a null ecosystem, which is the point of it: {@code daemon} is a stored
     * string and not an {@link Ecosystem}. The row it writes is PENDING with a fetch queued behind
     * it, like any other — {@code SbomIngestServiceTest} proves that half against a real
     * PostgreSQL.
     */
    @Override
    public UUID announcedDaemon(
        String name, String version, String repository, Instant occurredAt, ReleaseOrigin origin) {
      announced.add(new Announced(null, name, version, repository, occurredAt, origin));
      return UUID.randomUUID();
    }
  }


  /** The upstream hook, recording what it was told instead of queueing a re-plan (qits-1133). */
  static final class RecordingUpstream extends eu.wohlben.qits.maintenance.automation.UpstreamReplan {
    final List<String> moved = new java.util.ArrayList<>();

    @Override
    public void latestMoved(eu.wohlben.qits.maintenance.model.Ecosystem ecosystem, String name) {
      moved.add(ecosystem.wireName() + ":" + name);
    }
  }

  private SoftwareReleaseListener listener;
  private RecordingStore store;
  private RecordingIngest sboms;
  private RecordingUpstream upstream;

  @BeforeEach
  void setUp() {
    store = new RecordingStore();
    sboms = new RecordingIngest();
    listener = new SoftwareReleaseListener();
    listener.store = store;
    listener.sboms = sboms;
    upstream = new RecordingUpstream();
    listener.upstream = upstream;
  }

  private void release(String packageType, String packageName, String version) {
    listener.onFrame(
        frame("SoftwareRelease", softwareReleasePayload(packageType, packageName, version)));
  }

  @Test
  void itSubscribesToTheOneSignatureUnderItsOwnStorageKey() {
    assertEquals(Set.of("SoftwareRelease"), listener.signatures());
    assertEquals("maintenance-internal-latest", listener.consumerId());
  }

  /**
   * The three internal names, each spelled the way its own manifest parser records a pin — which is
   * the whole reason no translation table exists in the listener. A name that did not join would
   * write a row nothing reads and say nothing about it.
   */
  @Test
  void theThreeInternalNamesLandUnderTheSpellingMtPinUses() {
    release("maven", "eu.wohlben.qits:qits-eventstream", "2026.901.1");
    release("npm", "@qits/ui-components", "3.2.0");
    release("docker", "qits/qits-ci", "2026.901.2");

    assertEquals("2026.901.1", store.latest.get("maven:eu.wohlben.qits:qits-eventstream"));
    assertEquals("3.2.0", store.latest.get("npm:@qits/ui-components"));
    assertEquals("2026.901.2", store.latest.get("docker:qits/qits-ci"));
  }

  @Test
  void aReleaseMovesTheLatestForwardAndRecordsTheFrameAsItsSource() {
    EventFrame published =
        frame(
            "SoftwareRelease",
            softwareReleasePayload("maven", "eu.wohlben.qits:qits-eventstream", "2026.901.1"));
    listener.onFrame(published);

    assertEquals("2026.901.1", store.latest.get("maven:eu.wohlben.qits:qits-eventstream"));
    assertEquals(
        "event:" + published.id(),
        store.sourceUrls.get("maven:eu.wohlben.qits:qits-eventstream"),
        "a row a registry never answered has to say so, so a surprising value can be traced");
    assertEquals(
        List.of("maven:eu.wohlben.qits:qits-eventstream"),
        upstream.moved,
        "and the upstream hook is told the latest moved (qits-1133)");
  }

  /**
   * The rule the whole listener exists to get right: an announcement is evidence that THIS version
   * exists, never that a higher one does not.
   */
  @Test
  void aStaleFrameNeverRewindsAVersionAlreadyRecorded() {
    release("maven", "eu.wohlben.qits:qits-eventstream", "2026.901.5");
    release("maven", "eu.wohlben.qits:qits-eventstream", "2026.825.74539");

    assertEquals("2026.901.5", store.latest.get("maven:eu.wohlben.qits:qits-eventstream"));
    assertEquals(1, store.writes.size(), "the caught-up frame must write nothing at all");
    assertEquals(1, upstream.moved.size(), "and a frame that moved nothing tells the hook nothing");
  }

  /** The ordinary redelivery: the same release offered twice is a read and a return. */
  @Test
  void anEqualVersionSettlesQuietlyAndWritesNothing() {
    release("npm", "@qits/ui-components", "3.2.0");
    release("npm", "@qits/ui-components", "3.2.0");

    assertEquals(1, store.writes.size(), "a redelivery of one release must not rewrite the row");
    assertEquals("3.2.0", store.latest.get("npm:@qits/ui-components"));
  }

  /** npm ranks prereleases by rules maven does not share, and the guard is the ecosystem's own. */
  @Test
  void theForwardGuardIsTheEcosystemsOwnOrder() {
    release("npm", "@qits/angular", "1.0.0");
    release("npm", "@qits/angular", "1.0.0-rc.1");

    assertEquals(
        "1.0.0", store.latest.get("npm:@qits/angular"), "semver puts a prerelease below its release");
  }

  /**
   * {@code docs} is a real release of a thing no manifest this service parses ever pins, and a type
   * qits-ci adds later reads the same way. Both settle. {@code daemon} moves no column either — see
   * the daemon block below — for a different reason: its row is a keep, not a pin.
   */
  @Test
  void aPackageTypeThisInventoryDoesNotHoldSettlesWithoutAWrite() {
    release("daemon", "qits-platform-access-cli", "2026.901.1");
    release("docs", "@apidocs/qits-ci", "2026.901.1");
    release("cargo", "qits-something", "1.0.0");

    assertTrue(store.writes.isEmpty(), "nothing here is a pin any manifest holds");
  }

  @Test
  void aReleaseNamingNoVersionIsPoisonAndIsSettled() {
    release("maven", "eu.wohlben.qits:qits-eventstream", "");

    assertTrue(store.writes.isEmpty());
  }

  @Test
  void anUnreadablePayloadIsPoisonAndIsSettled() {
    listener.onFrame(frame("SoftwareRelease", "not json at all"));

    assertTrue(store.writes.isEmpty());
  }

  /**
   * The other half of the failure rule: a store that could not answer is a condition rather than a
   * verdict, so it is left to throw — the claim rolls back and the next catch-up sweep offers the
   * release again.
   */
  @Test
  void aStoreThatWillNotAnswerIsLeftToThrowSoTheReleaseStaysOwed() {
    store.failWith = new IllegalStateException("the database is not there");

    assertThrows(
        IllegalStateException.class,
        () -> release("maven", "eu.wohlben.qits:qits-eventstream", "2026.901.1"));
  }

  // --- the second write: the sbom outbox ----------------------------------------------------------

  /**
   * <b>A release leaves TWO facts behind and they are different ones.</b> {@code mt_latest} says a
   * version EXISTS; the artifact row says THIS release has contents worth reading. Neither is
   * derivable from the other, and the row is written whether or not the column moved.
   */
  @Test
  void aReleaseAlsoOpensThePendingArtifactRowTheIngestPicksUp() {
    EventFrame published =
        frame(
            "SoftwareRelease",
            softwareReleasePayload("maven", "eu.wohlben.qits:qits-eventstream", "2026.901.1"));

    listener.onFrame(published);

    assertEquals(1, sboms.announced.size());
    RecordingIngest.Announced row = sboms.announced.get(0);
    assertEquals(Ecosystem.MAVEN, row.ecosystem());
    assertEquals("eu.wohlben.qits:qits-eventstream", row.name());
    assertEquals("2026.901.1", row.version());
    assertEquals(
        "qits-eventstream-javalib",
        row.repository(),
        "SoftwareRelease.repository is another context's spelling, resolved to the catalog name "
            + "this side joins on — see the three cases below");
    assertEquals(
        published.occurredAt(),
        row.occurredAt(),
        "the publisher's moment, never this service's clock: it is what 'newest per dependent' "
            + "is ordered by, and a catch-up frame announces yesterday's release");
  }

  /**
   * The redelivery arm. The listener offers the coordinate again — it has no way to know it is a
   * second offer — and the STORE is what makes that harmless, by leaving a row it already knows
   * exactly as it was. That half is proved against a real database in {@code
   * SbomIngestServiceTest}; here the claim is that the listener does not decide it for itself.
   */
  @Test
  void aRedeliveredReleaseOffersTheSameCoordinateAndNothingElse() {
    release("maven", "eu.wohlben.qits:qits-eventstream", "2026.901.1");
    release("maven", "eu.wohlben.qits:qits-eventstream", "2026.901.1");

    assertEquals(2, sboms.announced.size(), "the listener offers; the store is what dedupes");
    assertEquals(sboms.announced.get(0).name(), sboms.announced.get(1).name());
    assertEquals(sboms.announced.get(0).version(), sboms.announced.get(1).version());
    assertEquals(1, store.writes.size(), "and the latest column moved exactly once");
  }

  /**
   * A CATCH-UP FRAME still opens a row even though it moves no column. The two writes answer
   * different questions: an older release is not the newest version and its contents are still
   * unrecorded.
   */
  @Test
  void aFrameThatMovesNoColumnStillOpensItsArtifactRow() {
    release("maven", "eu.wohlben.qits:qits-eventstream", "2026.901.5");
    sboms.announced.clear();

    release("maven", "eu.wohlben.qits:qits-eventstream", "2026.825.74539");

    assertEquals(1, store.writes.size(), "the column did not move");
    assertEquals(1, sboms.announced.size(), "and the older release's contents are still a fact");
    assertEquals("2026.825.74539", sboms.announced.get(0).version());
  }

  /** Poison is settled before either write, so nothing is opened for a frame nothing can read. */
  @Test
  void poisonOpensNoArtifactRowEither() {
    listener.onFrame(frame("SoftwareRelease", "not json at all"));
    release("maven", "eu.wohlben.qits:qits-eventstream", "");
    // …and the daemon arm is inside the same gate: a binary with no version names nothing to keep.
    release("daemon", "qits-platform-access-cli", "");

    assertTrue(sboms.announced.isEmpty());
  }

  // --- the repository field: a row id on the wire, a name in the row ------------------------------

  /**
   * <b>THE FIELD IS A ROW ID, measured live on 2026-09-02.</b> qits-ci names the repository by
   * qits-projects' row uuid; every read on this side joins {@code mt_artifact.repository} to a
   * catalog NAME. Resolving at the write is what keeps the repository page's transitives,
   * {@code GET /repositories/{name}/dependents} and the client's link target from all reading a
   * uuid that matches nothing.
   */
  @Test
  void aRepositoryNamedByItsCatalogRowIdIsStoredUnderItsName() {
    store.nameByCatalogId.put("daf73ae4-1c9a-4f8e-9a51-0b0d0e0f1234", "qits-eventstream-javalib");

    listener.onFrame(
        frame(
            "SoftwareRelease",
            ForeignEventContractTest.softwareReleasePayload(
                "daf73ae4-1c9a-4f8e-9a51-0b0d0e0f1234",
                "maven",
                "eu.wohlben.qits:qits-eventstream",
                "2026.901.1")));

    assertEquals(1, sboms.announced.size());
    assertEquals("qits-eventstream-javalib", sboms.announced.get(0).repository());
  }

  /** A name is already the join key, so it is answered unchanged and nothing is invented. */
  @Test
  void aRepositoryAlreadyNamedByTheCatalogPassesThroughUnchanged() {
    store.nameByCatalogId.put("daf73ae4-1c9a-4f8e-9a51-0b0d0e0f1234", "qits-eventstream-javalib");

    release("maven", "eu.wohlben.qits:qits-eventstream", "2026.901.1");

    assertEquals("qits-eventstream-javalib", sboms.announced.get(0).repository());
  }

  /**
   * The third case, and the one to get right: a spelling the catalog knows neither way is KEPT. A
   * release of a repository this inventory has never scanned still happened, and an unknown
   * spelling must not lose the fact along with the string.
   */
  @Test
  void aSpellingTheCatalogKnowsNeitherWayIsKeptRawRatherThanDropped() {
    listener.onFrame(
        frame(
            "SoftwareRelease",
            ForeignEventContractTest.softwareReleasePayload(
                "8f7e6d5c-0000-4000-8000-1a2b3c4d5e6f",
                "maven",
                "eu.wohlben.qits:qits-eventstream",
                "2026.901.1")));

    assertEquals("8f7e6d5c-0000-4000-8000-1a2b3c4d5e6f", sboms.announced.get(0).repository());
  }

  /**
   * {@code docs} is the type that is still discarded, and it is discarded on the rule that was
   * always the real one: NOTHING PINS an api-docs bundle, so a row for one would be a keep nobody
   * asked for. The filter is the ecosystem map, one step before either write.
   */
  @Test
  void noArtifactRowIsEverOpenedForATypeNothingPins() {
    release("docs", "@apidocs/qits-ci", "2026.901.1");
    release("cargo", "qits-something", "1.0.0");

    assertTrue(sboms.announced.isEmpty());
    assertTrue(store.writes.isEmpty());
  }

  // --- the release's origin: what the daily SBOM check reads (qits-668) ---------------------------

  /** Project, section and run ride from the frame onto the row, on the ordinary arm and the daemon's. */
  @Test
  void theProjectSectionAndRunOfTheReleaseReachTheRow() {
    UUID run = UUID.randomUUID();
    listener.onFrame(
        frame(
            "SoftwareRelease",
            ForeignEventContractTest.softwareReleasePayload(
                "maven", "eu.wohlben.qits:qits-eventstream", "2026.901.1", "qits", "Contracts", run)));
    listener.onFrame(
        frame(
            "SoftwareRelease",
            ForeignEventContractTest.softwareReleasePayload(
                "daemon", "qits-platform-access-cli", "2026.901.1", "qits", "artifacts", run)));

    assertEquals(2, sboms.announced.size());
    assertEquals(new ReleaseOrigin("qits", "contracts", run), sboms.announced.get(0).origin());
    assertEquals(new ReleaseOrigin("qits", "artifacts", run), sboms.announced.get(1).origin());
  }

  /** A frame from a qits-ci without the fields carries no origin, and is recorded all the same. */
  @Test
  void aFrameWithoutTheOriginFieldsIsRecordedWithNone() {
    release("maven", "eu.wohlben.qits:qits-eventstream", "2026.901.1");

    assertEquals(ReleaseOrigin.NONE, sboms.announced.get(0).origin());
  }

  /** A run id that is not a uuid is dropped, never a reason to lose the release. */
  @Test
  void aRunIdThatIsNotAUuidIsDroppedRatherThanFailingTheDecode() {
    String payload =
        ForeignEventContractTest.softwareReleasePayload(
                "maven", "eu.wohlben.qits:qits-eventstream", "2026.901.1", "qits", null, null)
            .replace("\"projectId\"", "\"runId\":\"not-a-uuid\",\"projectId\"");
    listener.onFrame(frame("SoftwareRelease", payload));

    assertEquals(1, sboms.announced.size());
    assertEquals(new ReleaseOrigin("qits", null, null), sboms.announced.get(0).origin());
  }

  // --- the daemon arm: a row, and no column --------------------------------------------------------

  /**
   * <b>THE WHOLE POINT OF THIS CHANGE.</b> The qits CLI's version is a pom pin — qits-ci pins
   * {@code eu.wohlben.qits:qits-platform-access-cli-binary}, whose version IS the daemons-store
   * coordinate of the binary the same release published — so a daemon release this listener
   * discarded left no {@code mt_artifact} row, the pin source could derive no keep from one, and the
   * store collected the binary out from under a pin that still named it.
   */
  @Test
  void aDaemonReleaseOpensTheArtifactRowTheKeepIsDerivedFrom() {
    EventFrame published =
        frame(
            "SoftwareRelease",
            softwareReleasePayload("daemon", "qits-platform-access-cli", "2026.918.1"));

    listener.onFrame(published);

    assertEquals(1, sboms.announced.size());
    RecordingIngest.Announced row = sboms.announced.get(0);
    assertEquals("qits-platform-access-cli", row.name());
    assertEquals("2026.918.1", row.version());
    assertEquals(
        "qits-eventstream-javalib",
        row.repository(),
        "the repository translation is the ordinary path's, not a second one");
    assertEquals(
        published.occurredAt(),
        row.occurredAt(),
        "and so is the publisher's moment, for the same reason: it is what 'newest' is ordered by");
  }

  /**
   * <b>…AND NO {@code mt_latest} COLUMN.</b> That column exists to be compared against a pin of the
   * same ecosystem and there is none — what a pom holds is the MAVEN coordinate, whose own column
   * moves it — and {@code LatestResolver} could never refresh a daemon column anyway, there being no
   * registry to ask. A column nothing can refresh and nothing compares against is a stale number
   * with no way to tell.
   */
  @Test
  void aDaemonReleaseMovesNoLatestColumn() {
    release("daemon", "qits-platform-access-cli", "2026.918.1");

    assertTrue(store.writes.isEmpty(), "there is no pin of that ecosystem to compare against");
    assertEquals(1, sboms.announced.size(), "and the row is still written");
  }

  /**
   * The word stays OUT of the enum, which is what keeps the latest column and the pending rule from
   * being asked a question about a daemon binary that neither can answer. (The SBOM route is keyed
   * by the stored word instead, and does read a daemon's document.)
   * {@code Ecosystem.DAEMON_WIRE_NAME} is the string, and it is compared as one.
   */
  @Test
  void daemonIsNotAnEcosystemAndAskingForOneStillAnswersEmpty() {
    assertTrue(Ecosystem.of("daemon").isEmpty());
    assertTrue(Ecosystem.of(Ecosystem.DAEMON_WIRE_NAME).isEmpty());
    assertEquals("daemon", Ecosystem.DAEMON_WIRE_NAME, "the spelling is qits-artifacts' contract");
  }
}
