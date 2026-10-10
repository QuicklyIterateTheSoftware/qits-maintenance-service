package eu.wohlben.qits.maintenance.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.entity.MtGroup;
import eu.wohlben.qits.maintenance.entity.MtPin;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.entity.MtScan;
import eu.wohlben.qits.maintenance.latest.LatestLookup;
import eu.wohlben.qits.maintenance.manifest.GroupConfig;
import eu.wohlben.qits.maintenance.manifest.ParsedPin;
import eu.wohlben.qits.maintenance.model.BumpStatus;
import eu.wohlben.qits.maintenance.model.BumpTrigger;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.GroupSource;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.model.ScanStatus;
import eu.wohlben.qits.maintenance.pending.Change;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The store, against a real PostgreSQL the suite spawns itself.
 *
 * <p>Every migration runs against an empty schema here, so the schema and the entities are checked
 * against each other rather than against a mapping tool's opinion.
 */
@QuarkusTest
class MaintenanceStoreTest {

  @Inject MaintenanceStore store;

  /** The sentence a dropped row carries. The shipped one is {@code ScanService}'s; the store takes
   * whatever its caller hands it, so the tests here supply their own. */
  private static final String DROPPED = "dropped from the catalog";

  private static ParsedPin pin(String name, String version, String location) {
    return ParsedPin.of(Ecosystem.MAVEN, "pom.xml", name, version, null, location);
  }

  @Test
  void anInventoryIsReplacedWholesaleRatherThanMerged() {
    String repository = "replace-" + UUID.randomUUID();
    store.replaceInventory(
        repository,
        "qits",
        null,
        null,
        "main",
        RepositoryStatus.OK,
        "sha1",
        null,
        List.of(pin("g:a", "1.0.0", "dependency:g:a"), pin("g:b", "2.0.0", "dependency:g:b")),
        List.of(GroupConfig.Group.ofKind("dependencies", PinKind.INTERNAL)),
        GroupSource.DEFAULT,
        candidate -> PinKind.INTERNAL,
        Instant.now());
    assertEquals(2, store.pins(repository).size());

    // The second scan found only one pin. The first scan's other row must be gone, not merged.
    store.replaceInventory(
        repository,
        "qits",
        null,
        null,
        "main",
        RepositoryStatus.OK,
        "sha2",
        null,
        List.of(pin("g:a", "1.1.0", "dependency:g:a")),
        List.of(GroupConfig.Group.ofKind("dependencies", PinKind.INTERNAL)),
        GroupSource.DEFAULT,
        candidate -> PinKind.INTERNAL,
        Instant.now());
    List<MtPin> pins = store.pins(repository);
    assertEquals(1, pins.size());
    assertEquals("1.1.0", pins.get(0).version);
    assertEquals("sha2", store.repository(repository).orElseThrow().headSha);
  }

  @Test
  void anUnreachableRepositoryKeepsThePinsItHad() {
    // A peer that could not be asked is not evidence that a repository stopped pinning anything.
    String repository = "unreachable-" + UUID.randomUUID();
    store.replaceInventory(
        repository,
        "qits",
        null,
        null,
        "main",
        RepositoryStatus.OK,
        "sha1",
        null,
        List.of(pin("g:a", "1.0.0", "dependency:g:a")),
        List.of(GroupConfig.Group.ofKind("dependencies", PinKind.INTERNAL)),
        GroupSource.DEFAULT,
        candidate -> PinKind.INTERNAL,
        Instant.now());

    store.markRepository(
        repository,
        "qits",
        null,
        null,
        RepositoryStatus.UNREACHABLE,
        "the git host said nothing",
        Instant.now());

    MtRepository row = store.repository(repository).orElseThrow();
    assertEquals(RepositoryStatus.UNREACHABLE.name(), row.status);
    assertEquals(1, store.pins(repository).size());
  }

  /**
   * <b>V5's column, and the whole reason it exists.</b> Another context spells this repository with
   * qits-projects' row id — qits-ci's {@code SoftwareRelease} does, measured live on 2026-09-02 —
   * while every join here is name-keyed. The catalog is where both spellings are known at once, so
   * a scan writes the id beside the name and {@code repositoryName} is the translation, in one
   * query and over all three cases.
   */
  @Test
  void aCatalogIdIsStoredBesideTheNameAndIsWhatTranslatesAnotherContextsSpelling() {
    String repository = "catalog-id-" + UUID.randomUUID();
    String catalogId = UUID.randomUUID().toString();
    store.replaceInventory(
        repository,
        "qits",
        catalogId,
        null,
        "main",
        RepositoryStatus.OK,
        "sha1",
        null,
        List.of(pin("g:a", "1.0.0", "dependency:g:a")),
        List.of(GroupConfig.Group.ofKind("dependencies", PinKind.INTERNAL)),
        GroupSource.DEFAULT,
        candidate -> PinKind.INTERNAL,
        Instant.now());

    assertEquals(catalogId, store.repository(repository).orElseThrow().catalogId);
    assertEquals(repository, store.repositoryName(catalogId), "an id is answered with its name");
    assertEquals(repository, store.repositoryName(repository), "a name is answered unchanged");
    // An unknown spelling is KEPT. A release of a repository this inventory has never scanned still
    // happened, and dropping the string would lose the fact along with the spelling.
    String stranger = UUID.randomUUID().toString();
    assertEquals(stranger, store.repositoryName(stranger));
  }

  /**
   * A catalog listing that carried no id says nothing about the id this row already has, and
   * clearing it would take the translation away from every graph row that still needs it.
   */
  @Test
  void aScanThatSawNoCatalogIdLeavesTheOneTheRowAlreadyCarries() {
    String repository = "catalog-id-kept-" + UUID.randomUUID();
    String catalogId = UUID.randomUUID().toString();
    store.replaceInventory(
        repository,
        "qits",
        catalogId,
        null,
        "main",
        RepositoryStatus.OK,
        "sha1",
        null,
        List.of(),
        List.of(),
        GroupSource.DEFAULT,
        candidate -> PinKind.INTERNAL,
        Instant.now());
    store.replaceInventory(
        repository,
        "qits",
        null,
        null,
        "main",
        RepositoryStatus.OK,
        "sha2",
        null,
        List.of(),
        List.of(),
        GroupSource.DEFAULT,
        candidate -> PinKind.INTERNAL,
        Instant.now());
    store.markRepository(
        repository, "qits", null, null, RepositoryStatus.UNREACHABLE, "nothing answered",
        Instant.now());

    MtRepository row = store.repository(repository).orElseThrow();
    assertEquals(catalogId, row.catalogId);
    assertEquals("sha2", row.headSha);
  }

  /**
   * <b>V6's column, and the one place it deliberately does NOT follow {@code catalog_id}.</b>
   *
   * <p>What a repository IS is a live classification qits-projects owns, so every scan writes what
   * the catalog said — <b>including writing null over a value, which the id above never does</b>.
   * The two are different kinds of fact: an id is a translation that {@code mt_artifact} rows still
   * need after the catalog stops answering it, while a stale archetype rescues nothing and hides a
   * re-classification. A LIBRARY promoted to a SERVICE over there must read as a SERVICE here on
   * the next pass, and a repository whose archetype was cleared must read as unclassified rather
   * than as whatever it used to be.
   *
   * <p>The string is stored VERBATIM and never validated, which is the other half of the design:
   * the vocabulary is foreign, the column has no check constraint, and a word this platform has not
   * heard of costs a repository its train placement rather than its inventory row.
   */
  @Test
  void anArchetypeIsRewrittenByEveryScan_includingBackToNothing() {
    String repository = "archetype-" + UUID.randomUUID();
    String catalogId = UUID.randomUUID().toString();

    scannedAs(repository, catalogId, "LIBRARY");
    assertEquals("LIBRARY", store.repository(repository).orElseThrow().archetype);

    // Re-classified over there. The inventory follows on the next scan.
    scannedAs(repository, catalogId, "SERVICE");
    assertEquals("SERVICE", store.repository(repository).orElseThrow().archetype);

    // A spelling this service has never heard of reaches the column intact — see the note above.
    scannedAs(repository, catalogId, "QUANTUM_MESH");
    assertEquals("QUANTUM_MESH", store.repository(repository).orElseThrow().archetype);

    // …and the catalog answering none CLEARS it, where the same listing leaves catalog_id standing.
    scannedAs(repository, null, null);
    MtRepository cleared = store.repository(repository).orElseThrow();
    assertNull(cleared.archetype, "the catalog is authoritative about what a repository is");
    assertEquals(catalogId, cleared.catalogId, "and it is still not authoritative about the id");

    // The unreachable path carries it too: the git host said nothing, the catalog still had an
    // opinion, and the row records the opinion it was given.
    store.markRepository(
        repository, "qits", null, "DAEMON", RepositoryStatus.UNREACHABLE, "no answer",
        Instant.now());
    assertEquals("DAEMON", store.repository(repository).orElseThrow().archetype);
  }

  /** One scan of a repository the catalog classifies as {@code archetype}. */
  private void scannedAs(String repository, String catalogId, String archetype) {
    store.replaceInventory(
        repository,
        "qits",
        catalogId,
        archetype,
        "main",
        RepositoryStatus.OK,
        "sha1",
        null,
        List.of(),
        List.of(),
        GroupSource.DEFAULT,
        candidate -> PinKind.INTERNAL,
        Instant.now());
  }

  /**
   * <b>THE INVENTORY FOLLOWS THE CATALOG OUT.</b> A row the listing does not name goes ABSENT and
   * loses the two tables that are a cache of the catalog's world; the row itself, its
   * {@code catalog_id} and the log tables stay.
   *
   * <p><b>The listing is built from the store rather than written out by hand</b>, and that is not
   * fussiness: reconciliation is global by nature and this class shares one database across its
   * methods, so a hand-written listing would drop every other test's repository as a side effect.
   */
  @Test
  void aRepositoryTheCatalogNoLongerListsGoesAbsentAndLosesItsPinsAndGroups() {
    String kept = "kept-" + UUID.randomUUID();
    String ghost = "ghost-" + UUID.randomUUID();
    String catalogId = UUID.randomUUID().toString();
    scanned(kept, null, "1.0.0");
    scanned(ghost, catalogId, "1.0.0");

    List<String> listing = everyRepositoryExcept(ghost);
    List<String> dropped = store.reconcileCatalog(listing, DROPPED, Instant.now());

    assertEquals(List.of(ghost), dropped, "only the unlisted row is reported");
    MtRepository row =
        store
            .repository(ghost)
            .orElseThrow(() -> new AssertionError("the row is KEPT, so the name still answers"));
    assertEquals(RepositoryStatus.ABSENT.name(), row.status);
    assertEquals(DROPPED, row.message);
    // The translation every mt_artifact row written under another context's spelling still needs.
    assertEquals(catalogId, row.catalogId, "the catalog_id survives the drop");
    assertTrue(store.pins(ghost).isEmpty(), "a repository the catalog dropped pins nothing");
    assertTrue(store.groups(ghost).isEmpty(), "and offers the clock no group to bump");

    // The listed one is untouched — reconciliation is about absence and nothing else.
    assertEquals(RepositoryStatus.OK.name(), store.repository(kept).orElseThrow().status);
    assertEquals(1, store.pins(kept).size());

    // And the next night says nothing new about it: the write is idempotent, the report is not.
    assertTrue(
        store.reconcileCatalog(listing, DROPPED, Instant.now()).isEmpty(),
        "a row already dropped for this reason is not reported again");
  }

  /**
   * <b>THE GUARD WITH TEETH.</b> An empty listing would mark every repository on the platform absent
   * and wipe every pin in one transaction — which is exactly what a catalog answering
   * {@code {"repositories":[]}} looks like from here. The caller refuses it a line earlier; this is
   * the belt under that brace, because the cost of the two disagreeing is the whole store.
   */
  @Test
  void anEmptyOrAbsentListingReconcilesNothingAtAll() {
    String repository = "no-listing-" + UUID.randomUUID();
    scanned(repository, null, "1.0.0");

    assertTrue(store.reconcileCatalog(List.of(), DROPPED, Instant.now()).isEmpty());
    assertTrue(store.reconcileCatalog(null, DROPPED, Instant.now()).isEmpty());

    MtRepository row = store.repository(repository).orElseThrow();
    assertEquals(RepositoryStatus.OK.name(), row.status, "nothing was marked");
    assertEquals(1, store.pins(repository).size(), "and nothing was wiped");
  }

  /**
   * A repository that comes BACK needs nothing of its own: the ordinary upsert path writes OK over
   * the ABSENT row with fresh pins and fresh groups, which is what makes the drop safe to make.
   */
  @Test
  void aRepositoryThatReturnsToTheCatalogIsScannedBackToOk() {
    String repository = "returning-" + UUID.randomUUID();
    scanned(repository, null, "1.0.0");
    store.reconcileCatalog(everyRepositoryExcept(repository), DROPPED, Instant.now());
    assertEquals(RepositoryStatus.ABSENT.name(), store.repository(repository).orElseThrow().status);

    scanned(repository, null, "2.0.0");

    MtRepository row = store.repository(repository).orElseThrow();
    assertEquals(RepositoryStatus.OK.name(), row.status);
    assertNull(row.message, "and the drop's sentence goes with the status");
    assertEquals(List.of("2.0.0"), store.pins(repository).stream().map(p -> p.version).toList());
    assertEquals(1, store.groups(repository).size(), "with its groups back");
  }

  /** One repository as a successful scan of it would leave it: one pin, one group, OK. */
  private void scanned(String repository, String catalogId, String version) {
    store.replaceInventory(
        repository,
        "qits",
        catalogId,
        null,
        "main",
        RepositoryStatus.OK,
        "sha1",
        null,
        List.of(pin("g:a", version, "dependency:g:a")),
        List.of(GroupConfig.Group.ofKind("dependencies", PinKind.INTERNAL)),
        GroupSource.DEFAULT,
        candidate -> PinKind.INTERNAL,
        Instant.now());
  }

  /** The catalog listing that drops exactly one name and leaves this database's others standing. */
  private List<String> everyRepositoryExcept(String dropped) {
    return store.repositories().stream()
        .map(row -> row.name)
        .filter(name -> !name.equals(dropped))
        .toList();
  }

  @Test
  void groupsKeepTheirDeclarationOrder() {
    String repository = "groups-" + UUID.randomUUID();
    store.replaceInventory(
        repository,
        "qits",
        null,
        null,
        "main",
        RepositoryStatus.OK,
        "sha1",
        null,
        List.of(),
        List.of(
            GroupConfig.Group.glob("angular", List.of("@angular/*")),
            GroupConfig.Group.glob("quarkus", List.of("io.quarkus:*")),
            GroupConfig.Group.ofKind("dependencies", PinKind.INTERNAL)),
        GroupSource.CONFIG,
        candidate -> PinKind.EXTERNAL,
        Instant.now());
    List<MtGroup> groups = store.groups(repository);
    assertEquals(List.of("angular", "quarkus", "dependencies"), groups.stream().map(g -> g.name).toList());
    assertEquals(List.of(0, 1, 2), groups.stream().map(g -> g.ordinal).toList());
  }

  @Test
  void aGroupsKindSurvivesTheRoundTripAndAGlobGroupCarriesNone() {
    // The column is what the split rides on: a scan writes the fallback pair with a kind and empty
    // patterns, a configured group with patterns and no kind, and the grouping is read back out of
    // these rows on every request.
    String repository = "kinds-" + UUID.randomUUID();
    store.replaceInventory(
        repository,
        "qits",
        null,
        null,
        "main",
        RepositoryStatus.OK,
        "sha1",
        null,
        List.of(pin("g:a", "1.0.0", "dependency:g:a")),
        List.of(
            GroupConfig.Group.glob("angular", List.of("@angular/*")),
            GroupConfig.Group.ofKind(GroupConfig.DEFAULT_GROUP, PinKind.INTERNAL),
            GroupConfig.Group.ofKind(GroupConfig.EXTERNAL_GROUP, PinKind.EXTERNAL)),
        GroupSource.CONFIG,
        candidate -> PinKind.INTERNAL,
        Instant.now());

    List<MtGroup> groups = store.groups(repository);
    assertEquals(List.of("angular", "dependencies", "external"), groups.stream().map(g -> g.name).toList());
    assertNull(groups.get(0).kind);
    assertEquals("[\"@angular/*\"]", groups.get(0).patterns);
    assertEquals(PinKind.INTERNAL.name(), groups.get(1).kind);
    assertEquals(PinKind.EXTERNAL.name(), groups.get(2).kind);
    // A kind group claims by kind and by nothing else, so its patterns are the empty array.
    assertEquals("[]", groups.get(1).patterns);
    assertEquals("[]", groups.get(2).patterns);

    // And the pin lands where its kind says, read the way every route reads it.
    assertEquals(
        "dependencies",
        eu.wohlben.qits.maintenance.pending.PendingChanges.groupOf(store.pins(repository).get(0), groups)
            .orElseThrow());
  }

  @Test
  void aRescanReplacesTheGroupingAsWellAsThePins() {
    // The rows are a cache of a file, and a repository that ADDS a maintenance.yml must not keep
    // the fallback pair beside the groups it just declared.
    String repository = "regroup-" + UUID.randomUUID();
    store.replaceInventory(
        repository,
        "qits",
        null,
        null,
        "main",
        RepositoryStatus.OK,
        "sha1",
        null,
        List.of(),
        List.of(
            GroupConfig.Group.ofKind(GroupConfig.DEFAULT_GROUP, PinKind.INTERNAL),
            GroupConfig.Group.ofKind(GroupConfig.EXTERNAL_GROUP, PinKind.EXTERNAL)),
        GroupSource.DEFAULT,
        candidate -> PinKind.INTERNAL,
        Instant.now());
    store.replaceInventory(
        repository,
        "qits",
        null,
        null,
        "main",
        RepositoryStatus.OK,
        "sha2",
        null,
        List.of(),
        List.of(GroupConfig.Group.glob("everything", List.of("*"))),
        GroupSource.CONFIG,
        candidate -> PinKind.INTERNAL,
        Instant.now());
    List<MtGroup> groups = store.groups(repository);
    assertEquals(List.of("everything"), groups.stream().map(g -> g.name).toList());
    assertNull(groups.get(0).kind);
  }

  /**
   * THE OPT-OUT NEEDS NO ERASE OF ITS OWN. A repository that adds {@code ignore: [gitlink]} is
   * scanned again and the scan simply carries no gitlink pins; because an inventory is replaced
   * wholesale rather than merged, the rows the earlier scans wrote are gone by the same delete that
   * rewrites everything else. This is the qits-qits wrapper's case: forty-seven gitlink rows that
   * stop existing the first night after the line is committed.
   */
  @Test
  void pinsOfANewlyIgnoredEcosystemDisappearOnTheNextScan() {
    String repository = "ignored-" + UUID.randomUUID();
    ParsedPin gitlink =
        ParsedPin.of(
            Ecosystem.GITLINK,
            "components/qits-ci/qits-ci-service",
            "qits-ci-service",
            "aa11bb22cc33dd44ee55ff6677889900aabbccdd",
            null,
            "gitlink:components/qits-ci/qits-ci-service");
    store.replaceInventory(
        repository,
        "qits",
        null,
        null,
        "main",
        RepositoryStatus.OK,
        "sha1",
        null,
        List.of(pin("g:a", "1.0.0", "dependency:g:a"), gitlink),
        List.of(
            GroupConfig.Group.ofKind(GroupConfig.DEFAULT_GROUP, PinKind.INTERNAL),
            GroupConfig.Group.ofKind(GroupConfig.EXTERNAL_GROUP, PinKind.EXTERNAL)),
        GroupSource.DEFAULT,
        candidate -> PinKind.INTERNAL,
        Instant.now());

    // The next scan read the same repository with `ignore: [gitlink]` in place: the maven pin is
    // still there and the gitlink was never parsed, so it is not in what the scan hands over.
    store.replaceInventory(
        repository,
        "qits",
        null,
        null,
        "main",
        RepositoryStatus.OK,
        "sha2",
        null,
        List.of(pin("g:a", "1.0.0", "dependency:g:a")),
        List.of(
            GroupConfig.Group.ofKind(GroupConfig.DEFAULT_GROUP, PinKind.INTERNAL),
            GroupConfig.Group.ofKind(GroupConfig.EXTERNAL_GROUP, PinKind.EXTERNAL)),
        GroupSource.DEFAULT,
        candidate -> PinKind.INTERNAL,
        Instant.now());

    List<MtPin> pins = store.pins(repository);
    assertEquals(1, pins.size());
    assertEquals(Ecosystem.MAVEN.wireName(), pins.get(0).ecosystem);
    assertTrue(
        pins.stream().noneMatch(row -> Ecosystem.GITLINK.wireName().equals(row.ecosystem)),
        "an ignored ecosystem's stored pins do not survive the rescan");
  }

  @Test
  void aFailedLookupIsWrittenBecauseItIsNotTheSameAsUpToDate() {
    String name = "g:" + UUID.randomUUID();
    store.recordLatest(
        Ecosystem.MAVEN,
        name,
        LatestLookup.failed("http://example/maven-metadata.xml", "HTTP 503"),
        Instant.now());
    var row = store.latest(Ecosystem.MAVEN, name).orElseThrow();
    assertNull(row.latest);
    assertEquals("HTTP 503", row.error);
    assertNotNull(row.sourceUrl);
  }

  @Test
  void aLatestRowIsOnePerDependencyAndIsOverwritten() {
    String name = "g:" + UUID.randomUUID();
    store.recordLatest(Ecosystem.MAVEN, name, LatestLookup.found("1.0.0", "u"), Instant.now());
    store.recordLatest(Ecosystem.MAVEN, name, LatestLookup.found("2.0.0", "u"), Instant.now());
    assertEquals("2.0.0", store.latest(Ecosystem.MAVEN, name).orElseThrow().latest);
  }

  /**
   * The bus's write, and the rule that separates it from the poll's: an announcement is evidence
   * that THIS version exists and never that a higher one does not, so a catch-up frame from before
   * the last scan must not rewind a column that scan filled.
   *
   * <p><b>Every write happens before the one read, and that is not style.</b> A {@code @QuarkusTest}
   * method runs inside ONE request context, so a read made outside a transaction is answered by the
   * request-bound session — while every store write runs in {@code DbRetry.inNewTx}, whose session is
   * bound to its own transaction. Read the row in between and the first session's first-level cache
   * answers every later read with the value it saw first, and an assertion about the last write fails
   * against a store that is perfectly correct. Measured here, 2026-09-01. Same rock as the API
   * suite's "every poll is a fresh HTTP request".
   */
  @Test
  void anAnnouncedLatestOnlyEverMovesForward() {
    String name = "g:" + UUID.randomUUID();

    assertTrue(
        store.recordLatestIfNewer(Ecosystem.MAVEN, name, "2026.901.5", "event:one", Instant.now()),
        "the first announcement of a dependency nothing has looked up is adopted");
    assertFalse(
        store.recordLatestIfNewer(Ecosystem.MAVEN, name, "2026.825.1", "event:stale", Instant.now()),
        "a caught-up frame from before the last scan must not rewind the column");
    assertFalse(
        store.recordLatestIfNewer(Ecosystem.MAVEN, name, "2026.901.5", "event:again", Instant.now()),
        "and the ordinary redelivery of one release writes nothing at all");
    assertTrue(
        store.recordLatestIfNewer(Ecosystem.MAVEN, name, "2026.902.1", "event:next", Instant.now()),
        "the next real release moves it on");

    var row = store.latest(Ecosystem.MAVEN, name).orElseThrow();
    assertEquals("2026.902.1", row.latest);
    assertEquals("event:next", row.sourceUrl);
  }

  /**
   * The other half of the same rule, stated as a row rather than as a return value: a frame that is
   * not newer writes NOTHING — not the version, not the source url, not {@code checked_at}. Stamping
   * the timestamp would say a lookup happened, and none did.
   */
  @Test
  void aFrameThatIsNotNewerLeavesTheRowExactlyAsItWas() {
    String name = "g:" + UUID.randomUUID();
    store.recordLatestIfNewer(Ecosystem.MAVEN, name, "2026.901.5", "event:one", Instant.now());
    store.recordLatestIfNewer(Ecosystem.MAVEN, name, "2026.825.1", "event:stale", Instant.now());
    store.recordLatestIfNewer(Ecosystem.MAVEN, name, "2026.901.5", "event:again", Instant.now());

    var row = store.latest(Ecosystem.MAVEN, name).orElseThrow();
    assertEquals("2026.901.5", row.latest);
    assertEquals("event:one", row.sourceUrl, "not even the source url is restamped");
  }

  /**
   * A row whose lookup FAILED holds no version, so the announcement is the first thing known about
   * that dependency and is adopted — and it clears the error with it, because "we could not find
   * out" is no longer true.
   */
  @Test
  void anAnnouncementAdoptsARowWhoseLookupHadFailedAndClearsItsError() {
    String name = "g:" + UUID.randomUUID();
    store.recordLatest(
        Ecosystem.MAVEN, name, LatestLookup.failed("http://example/x", "HTTP 503"), Instant.now());

    assertTrue(
        store.recordLatestIfNewer(Ecosystem.MAVEN, name, "1.2.3", "event:one", Instant.now()));

    var row = store.latest(Ecosystem.MAVEN, name).orElseThrow();
    assertEquals("1.2.3", row.latest);
    assertNull(row.error);
  }

  /** npm ranks prereleases by rules maven does not share, and the guard is the ecosystem's own. */
  @Test
  void theForwardGuardIsTheEcosystemsOwnVersionOrder() {
    String name = "@qits/" + UUID.randomUUID();
    assertTrue(store.recordLatestIfNewer(Ecosystem.NPM, name, "1.0.0", "event:one", Instant.now()));
    assertFalse(
        store.recordLatestIfNewer(Ecosystem.NPM, name, "1.0.0-rc.1", "event:two", Instant.now()),
        "semver puts a prerelease below its own release");
  }

  /**
   * The bus's debounce: a merge is a burst of pushes, and a scan of one repository already queued or
   * running covers every one of them.
   */
  @Test
  void aScanOfOneRepositoryIsPendingWhileItIsQueuedOrRunningAndNotAfter() {
    String repository = "debounce-" + UUID.randomUUID();
    assertFalse(store.scanPending(repository));

    UUID id = store.openScan(ScanScope.INTERNAL, repository, "EVENT", Instant.now());
    assertTrue(store.scanPending(repository), "REQUESTED is queued behind the worker");

    store.scanStatus(id, ScanStatus.RUNNING, null, Instant.now());
    assertTrue(store.scanPending(repository));

    store.scanStatus(id, ScanStatus.SUCCEEDED, "done", Instant.now());
    assertFalse(store.scanPending(repository), "a finished scan debounces nothing");

    // A whole-catalog scan is deliberately not counted: it covers this repository, but it runs for
    // minutes, and suppressing an event's rescan for its length would read a push at whatever
    // revision that scan happened to reach.
    store.openScan(ScanScope.ALL, null, "SCHEDULED", Instant.now());
    assertFalse(store.scanPending(repository));
  }

  @Test
  void aRestartClosesEveryScanADeadProcessLeftOpen() {
    // A scan's work is entirely in the process it ran in: reads it made, rows it wrote, a position
    // in a loop nothing recorded. A successor cannot resume one, so a row left RUNNING for ever is
    // indistinguishable from a slow scan — which is what the first live failure looked like.
    UUID running = store.openScan(ScanScope.ALL, null, "MANUAL", Instant.now());
    store.scanStatus(running, ScanStatus.RUNNING, null, Instant.now());
    UUID queued = store.openScan(ScanScope.INTERNAL, "qits-ci", "SCHEDULED", Instant.now());
    UUID done = store.openScan(ScanScope.ALL, null, "MANUAL", Instant.now());
    store.scanStatus(done, ScanStatus.SUCCEEDED, "all good", Instant.now());

    assertTrue(store.failInterruptedScans("interrupted by restart", Instant.now()) >= 2);

    for (UUID id : List.of(running, queued)) {
      MtScan row = store.scan(id).orElseThrow();
      assertEquals(ScanStatus.FAILED.name(), row.status);
      assertEquals("interrupted by restart", row.message);
      assertNotNull(row.finishedAt);
    }
    // A scan that had already ended keeps the ending it earned.
    assertEquals(ScanStatus.SUCCEEDED.name(), store.scan(done).orElseThrow().status);
    assertEquals("all good", store.scan(done).orElseThrow().message);
  }

  /** A row the store opens for a release-request automation, REQUESTED, carrying these changes. */
  private UUID automationRow(String repository, List<Change> changes) {
    return store.openAutomation(
        new MaintenanceStore.AutomationOpening(
            repository,
            "estate-pins",
            null,
            null,
            null,
            null,
            "workspace/ws-" + UUID.randomUUID(),
            null,
            "dev",
            BumpTrigger.MANUAL,
            changes,
            java.util.Map.of(),
            BumpStatus.REQUESTED,
            null),
        true,
        Instant.now());
  }

  @Test
  void theChangesAreStoredAsSentAndReadBackWhole() {
    String repository = "changes-" + UUID.randomUUID();
    UUID id =
        automationRow(
            repository,
            List.of(
                new Change(
                    "docker", "Dockerfile", "qits/build-images/maven-base", "1", "2", "line:3")));
    var changes = MaintenanceStore.readObjects(store.bump(id).orElseThrow().changes);
    assertEquals(1, changes.size());
    assertEquals("line:3", changes.get(0).get("location"));
    assertEquals("qits/build-images/maven-base", changes.get(0).get("name"));
  }

  @Test
  void aBumpThatWasDispatchedCarriesItsEventAndItsRuns() {
    String repository = "dispatch-" + UUID.randomUUID();
    UUID id = automationRow(repository, List.of());
    store.bumpDispatched(id, id.toString(), List.of("run-1", "run-2"));
    MtBump row = store.bump(id).orElseThrow();
    assertEquals(BumpStatus.RUNNING.name(), row.status);
    assertEquals("run-1,run-2", row.ciRunId);
    assertEquals(id.toString(), row.ciEventId);
  }

  /**
   * <b>WHEN THE DISPATCHER LAST REACHED A REPOSITORY — the newest MAIN-ONLY request it opened
   * there, and nothing else.</b> This is the column the dispatcher breaks its ties by: newest per
   * repository (an older one must not make a repository look starved), one entry per repository,
   * and MAIN_ONLY only.
   */
  @Test
  void theLastDispatchIsTheNewestMainOnlyRequestPerRepository() {
    String repository = "dispatched-" + UUID.randomUUID();
    Instant older = Instant.parse("2026-09-01T02:00:00Z");
    Instant newer = Instant.parse("2026-09-13T19:53:00Z");
    mainOnly(repository, older);
    mainOnly(repository, newer);

    assertEquals(newer, store.lastDispatchedAt().get(repository));
  }

  /**
   * <b>A REQUEST THE DISPATCHER DID NOT OPEN IS NOT IT REACHING A REPOSITORY.</b> A legacy group
   * bump's release ask is history and counts for nothing; a person's request is never remembered as
   * opened here at all.
   */
  @Test
  void aGroupBumpsAskIsNotWhatTheDispatcherLastDid() {
    String repository = "group-ask-" + UUID.randomUUID();
    Instant dispatched = Instant.parse("2026-09-01T02:00:00Z");
    mainOnly(repository, dispatched);
    store.recordOpenedRequest(
        "group-" + UUID.randomUUID(), repository, "maintenance/dependencies",
        eu.wohlben.qits.maintenance.entity.MtReleaseRequest.GROUP_BUMP, null,
        Instant.parse("2026-09-14T11:00:00Z"));

    assertEquals(dispatched, store.lastDispatchedAt().get(repository));
  }

  /**
   * <b>NEVER IS ABSENT, NOT A TIMESTAMP.</b> What "never" ranks as is the caller's decision (it goes
   * first), and a sentinel written here would be this class inventing one.
   */
  @Test
  void aRepositoryTheDispatcherHasNeverReachedIsAbsentFromTheMap() {
    assertFalse(store.lastDispatchedAt().containsKey("never-" + UUID.randomUUID()));
  }

  /** One main-only request the dispatcher opened for one repository at that instant. */
  private void mainOnly(String repository, Instant at) {
    store.recordOpenedRequest(
        "main-only-" + UUID.randomUUID(),
        repository,
        "main",
        eu.wohlben.qits.maintenance.entity.MtReleaseRequest.MAIN_ONLY,
        List.of(),
        at);
  }
}
