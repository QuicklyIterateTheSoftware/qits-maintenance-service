package eu.wohlben.qits.maintenance.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.entity.MtArtifact;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.SbomStatus;
import eu.wohlben.qits.maintenance.sbom.ParsedSbom;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The three SBOM tables, against a real PostgreSQL the suite spawns itself.
 *
 * <p><b>The reverse query is the subject, and its default view is the load-bearing part.</b> "Who
 * ships a copy of this" has to answer with the NEWEST release of each dependent and not with every
 * release ever: a library released fifty times would otherwise answer fifty times over, and
 * forty-nine of those answers are about versions nobody can change any more. {@code all=true} is
 * the archaeology, and this is where both are pinned.
 *
 * <p>Every method uses a dependency name of its own — this module has no {@code InventoryReset},
 * and the whole point of these reads is that they cross artifacts.
 */
@QuarkusTest
class SbomGraphStoreTest {

  @Inject MaintenanceStore store;

  private String dependency;

  @BeforeEach
  void setUp() {
    dependency = "com.fasterxml:jackson-" + UUID.randomUUID();
  }

  /**
   * The session is cleared before every read, for the reason {@code MaintenanceStoreTest} states: a
   * store WRITE runs in its own transaction on its own session, and a read from the
   * request-bound one would answer from the cache the write never touched.
   */
  private void detached() {
    store.getEntityManager().clear();
  }

  /** One released artifact whose document names {@link #dependency} at {@code embedded}. */
  private UUID release(String name, String version, String repository, String embedded, boolean direct) {
    UUID id =
        store.upsertArtifact(
            Ecosystem.MAVEN,
            name,
            version,
            repository,
            // The publisher's moment, a day apart per major so "newest" is a fact rather than a
            // tie — the ordering under test is occurred_at's and not the insertion order's.
            Instant.parse("2026-09-01T10:00:00Z")
                .plusSeconds(86_400L * Integer.parseInt(version.substring(0, 1))));
    store.replaceGraph(
        id,
        List.of(
            new ParsedSbom.Component(
                "c-1", "pkg:maven/x/y@" + embedded, Ecosystem.MAVEN, dependency, embedded, direct)),
        List.of(new ParsedSbom.Edge(-1, 0)),
        Instant.now());
    return id;
  }

  // --- the reverse query -------------------------------------------------------------------------

  @Test
  void theDefaultViewIsTheNewestReleaseOfEachDependentArtifact() {
    String library = "eu.wohlben.qits:qits-lib-" + UUID.randomUUID();
    String service = "eu.wohlben.qits:qits-svc-" + UUID.randomUUID();
    release(library, "1.0.0", "qits-lib", "2.18.0", true);
    release(library, "3.0.0", "qits-lib", "2.18.2", true);
    release(library, "2.0.0", "qits-lib", "2.18.1", true);
    release(service, "9.0.0", "qits-svc", "2.17.0", false);
    detached();

    List<MaintenanceStore.Dependent> newest = store.dependents(Ecosystem.MAVEN, dependency, true);

    assertEquals(2, newest.size(), "one row per dependent artifact NAME");
    // Sorted by the dependent's name, so the assertion does not depend on insertion order.
    List<String> names = newest.stream().map(row -> row.artifact().name).sorted().toList();
    assertEquals(List.of(library, service).stream().sorted().toList(), names);

    MaintenanceStore.Dependent theLibrary =
        newest.stream().filter(row -> row.artifact().name.equals(library)).findFirst().orElseThrow();
    assertEquals(
        "3.0.0",
        theLibrary.artifact().version,
        "the newest release of the library, not the first or the last written");
    assertEquals("2.18.2", theLibrary.component().version);
    assertEquals("qits-lib", theLibrary.artifact().repository);
    assertTrue(theLibrary.component().direct);
  }

  @Test
  void allTrueIsEveryIngestedVersionRatherThanTheNewestOfEach() {
    String library = "eu.wohlben.qits:qits-lib-" + UUID.randomUUID();
    release(library, "1.0.0", "qits-lib", "2.18.0", true);
    release(library, "2.0.0", "qits-lib", "2.18.1", true);
    release(library, "3.0.0", "qits-lib", "2.18.2", true);
    detached();

    assertEquals(3, store.dependents(Ecosystem.MAVEN, dependency, false).size());
    assertEquals(1, store.dependents(Ecosystem.MAVEN, dependency, true).size());
  }

  /** And within one dependent, the versions come back newest first. */
  @Test
  void theFullViewIsOrderedByDependentNameThenNewestFirst() {
    String library = "eu.wohlben.qits:qits-lib-" + UUID.randomUUID();
    release(library, "1.0.0", "qits-lib", "2.18.0", true);
    release(library, "3.0.0", "qits-lib", "2.18.2", true);
    release(library, "2.0.0", "qits-lib", "2.18.1", true);
    detached();

    List<String> versions =
        store.dependents(Ecosystem.MAVEN, dependency, false).stream()
            .map(row -> row.artifact().version)
            .toList();

    assertEquals(List.of("3.0.0", "2.0.0", "1.0.0"), versions);
  }

  @Test
  void aDependencyNothingEmbedsAnswersWithNothing() {
    assertEquals(List.of(), store.dependents(Ecosystem.MAVEN, "nobody:ships-this", true));
  }

  /** The ecosystem is part of the join and is not a label: two worlds may spell one name. */
  @Test
  void theJoinIsOnTheEcosystemAsWellAsTheName() {
    release("eu.wohlben.qits:qits-lib-" + UUID.randomUUID(), "1.0.0", "qits-lib", "2.18.0", true);
    detached();

    assertEquals(1, store.dependents(Ecosystem.MAVEN, dependency, true).size());
    assertEquals(0, store.dependents(Ecosystem.NPM, dependency, true).size());
  }

  // --- the graph itself --------------------------------------------------------------------------

  @Test
  void replacingAGraphDeletesTheOldComponentsAndTheirEdges() {
    UUID id = release("eu.wohlben.qits:qits-lib-" + UUID.randomUUID(), "1.0.0", "qits-lib", "2.18.0", true);
    detached();
    assertEquals(1, store.components(id).size());
    assertEquals(1, store.edges(id).size());

    store.replaceGraph(
        id,
        List.of(
            new ParsedSbom.Component("c-a", "pkg:npm/a@1", Ecosystem.NPM, "a", "1", true),
            new ParsedSbom.Component("c-b", null, null, "an-unmapped-blob", "7", false)),
        List.of(new ParsedSbom.Edge(-1, 0), new ParsedSbom.Edge(0, 1)),
        Instant.now());
    detached();

    assertEquals(2, store.components(id).size());
    assertEquals(2, store.edges(id).size());
    assertEquals(
        0,
        store.dependents(Ecosystem.MAVEN, dependency, false).size(),
        "the replaced components must stop answering the reverse query");
  }

  /** A component whose purl named a world this service does not inventory is STORED, not dropped. */
  @Test
  void anUnmappedComponentIsStoredWithANullEcosystem() {
    UUID id =
        store.upsertArtifact(
            Ecosystem.MAVEN,
            "eu.wohlben.qits:qits-lib-" + UUID.randomUUID(),
            "1.0.0",
            "qits-lib",
            Instant.now());
    store.replaceGraph(
        id,
        List.of(
            new ParsedSbom.Component(
                "c-go", "pkg:golang/github.com/spf13/cobra@1.8.0", null, "cobra", "1.8.0", false)),
        List.of(),
        Instant.now());
    detached();

    var only = store.components(id).get(0);
    assertNull(only.ecosystem, "a null ecosystem is how 'shown but never matched' is spelled");
    assertEquals("cobra", only.name);
    assertEquals("pkg:golang/github.com/spf13/cobra@1.8.0", only.purl);
  }

  /** An edge pointing outside the component list is dropped rather than written as a broken FK. */
  @Test
  void anEdgeNamingAComponentThatIsNotThereIsSkipped() {
    UUID id =
        store.upsertArtifact(
            Ecosystem.MAVEN,
            "eu.wohlben.qits:qits-lib-" + UUID.randomUUID(),
            "1.0.0",
            "qits-lib",
            Instant.now());
    store.replaceGraph(
        id,
        List.of(new ParsedSbom.Component("c-a", "pkg:npm/a@1", Ecosystem.NPM, "a", "1", true)),
        List.of(new ParsedSbom.Edge(-1, 0), new ParsedSbom.Edge(0, 7), new ParsedSbom.Edge(-1, -1)),
        Instant.now());
    detached();

    assertEquals(1, store.edges(id).size());
  }

  // --- the row itself ----------------------------------------------------------------------------

  @Test
  void oneRowPerReleasedVersionAndAnAnnouncementNeverRewritesAnAnsweredOne() {
    String library = "eu.wohlben.qits:qits-lib-" + UUID.randomUUID();
    UUID first =
        store.upsertArtifact(
            Ecosystem.MAVEN, library, "1.0.0", "qits-lib", Instant.parse("2026-09-01T10:00:00Z"));
    store.markArtifactFailed(first, "qits-artifacts was unreachable");

    UUID second =
        store.upsertArtifact(
            Ecosystem.MAVEN, library, "1.0.0", "elsewhere", Instant.parse("2026-09-02T10:00:00Z"));
    detached();

    assertEquals(first, second);
    MtArtifact row = store.artifact(first).orElseThrow();
    assertEquals(SbomStatus.FAILED.name(), row.sbomStatus);
    assertEquals("qits-artifacts was unreachable", row.sbomError);
    assertEquals("qits-lib", row.repository);
    assertEquals(Instant.parse("2026-09-01T10:00:00Z"), row.occurredAt);
  }

  /** And a re-queue is the one thing that puts a terminal row back to work. */
  @Test
  void aRequeueClearsTheErrorAndPutsTheRowBackToPending() {
    String library = "eu.wohlben.qits:qits-lib-" + UUID.randomUUID();
    UUID id = store.upsertArtifact(Ecosystem.MAVEN, library, "1.0.0", null, Instant.now());
    store.markArtifactMissing(id);
    detached();
    assertEquals(SbomStatus.MISSING.name(), store.artifact(id).orElseThrow().sbomStatus);

    UUID again =
        store.requeueArtifact(Ecosystem.MAVEN, library, "1.0.0", "qits-lib", Instant.now());
    detached();

    assertEquals(id, again);
    MtArtifact row = store.artifact(id).orElseThrow();
    assertEquals(SbomStatus.PENDING.name(), row.sbomStatus);
    assertNull(row.sbomError);
    assertEquals("qits-lib", row.repository, "a backfill may name the repository nothing announced");
  }

  /** The manual backfill of a coordinate nobody announced mints the row from nothing. */
  @Test
  void aRequeueOfAnUnknownCoordinateCreatesTheRow() {
    String library = "eu.wohlben.qits:qits-lib-" + UUID.randomUUID();
    Instant now = Instant.parse("2026-09-03T12:00:00Z");

    UUID id = store.requeueArtifact(Ecosystem.NPM, library, "2.0.0", null, now);
    detached();

    MtArtifact row = store.artifact(id).orElseThrow();
    assertEquals("npm", row.ecosystem);
    assertEquals(SbomStatus.PENDING.name(), row.sbomStatus);
    assertEquals(now, row.occurredAt, "nobody announced it, so now is the honest moment");
    assertNull(row.ingestedAt);
    assertNotNull(row.id);
  }

  @Test
  void theNewestPerNameListingKeepsOneRowPerArtifact() {
    String library = "eu.wohlben.qits:qits-lib-" + UUID.randomUUID();
    store.upsertArtifact(
        Ecosystem.MAVEN, library, "1.0.0", "qits-lib", Instant.parse("2026-09-01T10:00:00Z"));
    store.upsertArtifact(
        Ecosystem.MAVEN, library, "2.0.0", "qits-lib", Instant.parse("2026-09-05T10:00:00Z"));
    detached();

    List<MtArtifact> newest =
        store.newestArtifactPerName().stream()
            .filter(row -> row.name.equals(library))
            .toList();

    assertEquals(1, newest.size());
    assertEquals("2.0.0", newest.get(0).version);
  }

  @Test
  void aRepositorysArtifactsAreReadableByTheNameTheReleaseAnnounced() {
    String repository = "qits-lib-" + UUID.randomUUID();
    store.upsertArtifact(
        Ecosystem.MAVEN, "eu.wohlben.qits:a", "1.0.0", repository, Instant.now());
    store.upsertArtifact(Ecosystem.NPM, "@qits/a", "1.0.0", repository, Instant.now());
    store.upsertArtifact(Ecosystem.MAVEN, "eu.wohlben.qits:b", "1.0.0", "somewhere", Instant.now());
    detached();

    assertEquals(2, store.artifactsOfRepository(repository).size());
  }

  /**
   * THE NARROW READ THE PIN SOURCE ASKS ITS CO-RELEASE QUESTION WITH: one repository, the two or
   * three versions somebody's manifest pins right now, and nothing of that repository's history.
   */
  @Test
  void theArtifactsOfNamedReleasesAreReadBackByRepositoryAndVersionTogether() {
    String repository = "qits-daemon-" + UUID.randomUUID();
    store.upsertArtifact(
        Ecosystem.MAVEN, "eu.wohlben.qits:protocol", "2026.917.1", repository, Instant.now());
    store.upsertArtifact(
        Ecosystem.DOCKER, "qits/agent", "2026.917.1", repository, Instant.now());
    // A later release of the same repository, which is what a version term that did not bite would
    // hand the collector as a keep for a version nobody pins.
    store.upsertArtifact(
        Ecosystem.DOCKER, "qits/agent", "2026.918.9", repository, Instant.now());
    // …and somebody else's release at the very version asked about.
    store.upsertArtifact(Ecosystem.DOCKER, "qits/other", "2026.917.1", "elsewhere", Instant.now());
    detached();

    List<MtArtifact> read =
        store.artifactsOfReleases(List.of(repository), List.of("2026.917.1"));

    assertEquals(2, read.size());
    assertTrue(read.stream().allMatch(row -> row.version.equals("2026.917.1")));
    assertTrue(read.stream().anyMatch(row -> row.name.equals("qits/agent")));
    assertTrue(read.stream().anyMatch(row -> row.name.equals("eu.wohlben.qits:protocol")));

    // An empty term on either side answers nothing and costs no round trip to say so.
    assertTrue(store.artifactsOfReleases(List.of(repository), List.of()).isEmpty());
    assertTrue(store.artifactsOfReleases(List.of(), List.of("2026.917.1")).isEmpty());
  }

  // --- the daemon row: a keep, never an outbox ----------------------------------------------------

  /**
   * <b>A DAEMON BINARY GETS A ROW, and the column takes the word.</b> {@code ecosystem} is {@code
   * varchar(32)} with no check constraint, so the literal {@code daemon} stores and reads back
   * exactly as written — which is what the pin source's daemon derivation joins on, and why V3's
   * comment saying a daemon is "never a row here" is carried as corrected on {@code MtArtifact}.
   */
  @Test
  void aReleasedDaemonBinaryIsStoredUnderTheLiteralWordAndIsNotAnEcosystem() {
    String repository = "qits-platform-access-cli-" + UUID.randomUUID();
    // The version carries the uniqueness: a row is keyed by (ecosystem, name, version) and this
    // module's tests share one database, so a fixed one would answer with a sibling test's row.
    String version = "2026.918." + UUID.randomUUID();
    UUID id =
        store.upsertDaemonArtifact("qits-platform-access-cli", version, repository, Instant.now());
    detached();

    MtArtifact row = store.artifact(id).orElseThrow();
    assertEquals(Ecosystem.DAEMON_WIRE_NAME, row.ecosystem);
    assertEquals("qits-platform-access-cli", row.name);
    assertEquals(version, row.version);
    assertEquals(repository, row.repository);
    assertTrue(
        Ecosystem.of(row.ecosystem).isEmpty(),
        "the row exists and the word is still not one of the four this service resolves");
  }

  /**
   * <b>PENDING AT THE WRITE, an outbox like any other row</b> (qits-703). qits-artifacts stores a
   * bill of materials for every released daemon binary and {@code SbomClient} addresses a row by its
   * stored type, so the row waits for its document exactly as a jar's does — and the sweep may
   * re-offer it, because the ingest now answers it.
   */
  @Test
  void aDaemonRowIsPendingAndTheSweepOffersItLikeAnyOther() {
    UUID id =
        store.upsertDaemonArtifact(
            "qits-platform-access-cli", "2026.918." + UUID.randomUUID(), "qits-cli", Instant.now());
    detached();

    MtArtifact row = store.artifact(id).orElseThrow();
    assertEquals(SbomStatus.PENDING.name(), row.sbomStatus);
    assertNull(row.sbomError);
    assertTrue(
        store.pendingArtifacts().stream().anyMatch(pending -> pending.id.equals(id)),
        "a daemon row nobody has read yet is in the queue the sweep re-offers");
  }

  /**
   * The release's ORIGIN (V14) is the one thing a redelivered frame may still write onto a known
   * row — and only into a column that is null. A value already stored is never overwritten.
   */
  @Test
  void theOriginOfARereleasedRowIsFilledWhereNullAndNeverOverwritten() {
    String name = "eu.wohlben.qits:origin-" + UUID.randomUUID();
    UUID run = UUID.randomUUID();
    UUID id = store.upsertArtifact(Ecosystem.MAVEN, name, "1", "r", Instant.now());
    store.upsertArtifact(
        Ecosystem.MAVEN,
        name,
        "1",
        "r",
        Instant.now(),
        new eu.wohlben.qits.maintenance.model.ReleaseOrigin("qits", "artifacts", run));
    store.upsertArtifact(
        Ecosystem.MAVEN,
        name,
        "1",
        "r",
        Instant.now(),
        new eu.wohlben.qits.maintenance.model.ReleaseOrigin("other", "contracts", UUID.randomUUID()));
    detached();

    MtArtifact row = store.artifact(id).orElseThrow();
    assertEquals("qits", row.projectId);
    assertEquals("artifacts", row.section);
    assertEquals(run, row.runId);
  }

  // --- V13: the rows the old rule wrote terminal are re-queued -------------------------------------

  /** The sentence the old rule wrote — {@code MaintenanceStore.DAEMON_SBOM_UNREAD}, now deleted. */
  private static final String OLD_DAEMON_SENTENCE =
      "a daemon binary's bill of materials is not read by this service: nothing it inventories pins"
          + " a daemon, and the row exists to keep the binary the pin of its co-released artifact"
          + " names";

  /**
   * <b>V13 re-queues exactly the rows the old daemon rule wrote, and nothing else.</b> Flyway has
   * already run it against an empty schema at boot, so it is replayed here — the shipped file, read
   * off the classpath — against rows written the way that rule wrote them, beside three that share
   * one of its terms and must not move: a daemon row FAILED for another reason, a maven row carrying
   * the same sentence, and a daemon row already INGESTED.
   */
  @Test
  void theMigrationRequeuesOnlyTheDaemonRowsTheOldRuleWroteTerminal() throws Exception {
    String suffix = UUID.randomUUID().toString();
    UUID unread = store.upsertDaemonArtifact("qits-cli-a", "1." + suffix, "r", Instant.now());
    UUID otherFailure = store.upsertDaemonArtifact("qits-cli-b", "1." + suffix, "r", Instant.now());
    UUID maven =
        store.upsertArtifact(Ecosystem.MAVEN, "x:y-" + suffix, "1." + suffix, "r", Instant.now());
    UUID ingested = store.upsertDaemonArtifact("qits-cli-c", "1." + suffix, "r", Instant.now());
    store.markArtifactFailed(unread, OLD_DAEMON_SENTENCE);
    store.markArtifactFailed(otherFailure, "connection refused");
    store.markArtifactFailed(maven, OLD_DAEMON_SENTENCE);
    store.replaceGraph(ingested, List.of(), List.of(), Instant.now());

    String sql;
    try (var in =
        getClass()
            .getClassLoader()
            .getResourceAsStream(
                "db/maintenance/migration/V13__requeue_daemon_sboms.sql")) {
      assertNotNull(in, "the migration ships on the classpath");
      sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    // Over the raw JDBC connection, as Flyway runs it: the file's comments carry apostrophes and
    // colons that Hibernate's native-query parameter parsing has no business reading.
    eu.wohlben.qits.db.DbRetry.runInNewTx(
        "replay V13",
        () ->
            store
                .getEntityManager()
                .unwrap(org.hibernate.Session.class)
                .doWork(
                    connection -> {
                      try (var statement = connection.createStatement()) {
                        statement.executeUpdate(sql);
                      }
                    }));
    detached();

    MtArtifact moved = store.artifact(unread).orElseThrow();
    assertEquals(SbomStatus.PENDING.name(), moved.sbomStatus);
    assertNull(moved.sbomError);
    assertEquals(SbomStatus.FAILED.name(), store.artifact(otherFailure).orElseThrow().sbomStatus);
    assertEquals(
        "connection refused", store.artifact(otherFailure).orElseThrow().sbomError);
    assertEquals(SbomStatus.FAILED.name(), store.artifact(maven).orElseThrow().sbomStatus);
    assertEquals(SbomStatus.INGESTED.name(), store.artifact(ingested).orElseThrow().sbomStatus);
  }

  /** A redelivered daemon release is a read and a return, exactly as the ordinary upsert is. */
  @Test
  void aRedeliveredDaemonReleaseLeavesTheRowAloneRatherThanDuplicatingIt() {
    String version = "2026.918." + UUID.randomUUID();
    UUID first = store.upsertDaemonArtifact("qits-platform-access-cli", version, "a", Instant.now());
    UUID again = store.upsertDaemonArtifact("qits-platform-access-cli", version, "b", Instant.now());
    detached();

    assertEquals(first, again);
    assertEquals("a", store.artifact(first).orElseThrow().repository);
  }

  /**
   * And the read the pin source derives its daemon keeps with: the same narrow query, answering for
   * a row whose ecosystem is a string rather than an enum value.
   */
  @Test
  void theDaemonOfAReleaseIsReadBackBesideTheMavenCoordinateThatCarriesIt() {
    String repository = "qits-platform-access-cli-" + UUID.randomUUID();
    String version = "2026.918." + UUID.randomUUID();
    store.upsertArtifact(
        Ecosystem.MAVEN,
        "eu.wohlben.qits:qits-platform-access-cli-binary",
        version,
        repository,
        Instant.now());
    store.upsertDaemonArtifact("qits-platform-access-cli", version, repository, Instant.now());
    detached();

    List<MtArtifact> read = store.artifactsOfReleases(List.of(repository), List.of(version));

    assertEquals(2, read.size());
    assertTrue(
        read.stream().anyMatch(row -> Ecosystem.DAEMON_WIRE_NAME.equals(row.ecosystem)),
        "the binary and the coordinate whose version names it are one release");
  }
}
