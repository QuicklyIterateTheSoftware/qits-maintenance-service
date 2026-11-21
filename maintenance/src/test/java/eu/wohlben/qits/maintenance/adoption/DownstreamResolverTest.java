package eu.wohlben.qits.maintenance.adoption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.bootstrap.graph.Ecosystem;
import eu.wohlben.qits.bootstrap.graph.manifest.ParsedPin;
import eu.wohlben.qits.maintenance.model.GroupSource;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.model.RepositoryArchetype;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.sbom.ParsedSbom;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The closure itself: who is downstream, how far, and through what.</b>
 *
 * <p>This is the piece that fixes the release trains' one real defect. A train derived its
 * membership once, at the release, with a single one-hop pass — so a library's journey named the
 * frontend that pins it and stopped there, for ever. The first test below is that bug, written as
 * the assertion it should always have had: <b>exactly</b> the frontend at depth 1 and the service at
 * depth 2, reached through the gitlink pin that makes the second hop exist at all.
 *
 * <p><b>Every fixture name carries a uuid</b>, because this module has no {@code InventoryReset} and
 * the resolver reads the WHOLE inventory: a fixed name would let one method's estate answer another
 * method's question. What it deliberately does NOT do is empty the store — a closure rooted at a
 * repository nothing else names is unaffected by whatever else is in there, and proving that is
 * worth more than an isolated database.
 *
 * <p>The {@link DownstreamResolver#MAX_REPOSITORIES} bound has no test of its own and that is a
 * deliberate omission: reaching it means seeding five hundred repositories that then sit in the
 * shared database for every method after it. The bound is asserted as a value, the depth bound is
 * driven for real, and the two share one guard.
 */
@QuarkusTest
class DownstreamResolverTest {

  @Inject MaintenanceStore store;

  @Inject DownstreamResolver resolver;

  /** The suffix every name in one method carries — see the class comment. */
  private String run;

  @BeforeEach
  void aRunOfItsOwn() {
    run = "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  // --- the fixture ------------------------------------------------------------------------------

  private void scanned(
      String repository, String catalogId, RepositoryArchetype archetype, ParsedPin... pins) {
    store.replaceInventory(
        repository,
        "qits",
        catalogId,
        archetype == null ? null : archetype.name(),
        "main",
        RepositoryStatus.OK,
        "sha",
        null,
        List.of(pins),
        List.of(),
        GroupSource.DEFAULT,
        candidate -> PinKind.INTERNAL,
        Instant.now());
  }

  private static ParsedPin npm(String name) {
    return ParsedPin.of(Ecosystem.NPM, "package.json", name, "0.0.1", null, "dependency:" + name);
  }

  /**
   * The submodule pin, which is a pin on a REPOSITORY NAME rather than on a published package —
   * {@code GitmodulesParser} stores the url's basename, and that is the whole frontend→service hop.
   */
  private static ParsedPin gitlink(String repository) {
    return ParsedPin.of(
        Ecosystem.GITLINK,
        ".gitmodules",
        repository,
        "c0ffee11d00d2233445566778899aabbccddeeff",
        null,
        "gitlink:service/src/main/webui");
  }

  /** One release of one repository, as the bus writes it. */
  private void published(String repository, Ecosystem ecosystem, String name, String version) {
    store.upsertArtifact(ecosystem, name, version, repository, Instant.now());
  }

  /**
   * A row a scan RETIRED — {@code ScanService.reconcile}'s ABSENT, {@code retireRenamed}'s ABSENT, or
   * a project's UNDECLARED. It keeps its {@code catalog_id}, which is what a rename shares with the
   * live row, and it has no pins: retiring a row deletes them.
   */
  private void retired(
      String repository, String catalogId, RepositoryArchetype archetype, RepositoryStatus status) {
    store.markRepository(
        repository,
        "qits",
        catalogId,
        archetype == null ? null : archetype.name(),
        status,
        "dropped from the catalog",
        Instant.now());
  }

  /**
   * One release filed under {@code storedAs} — a name, an old name or a catalog id, whatever the bus
   * wrote — whose bill of materials embeds {@code embedded}: the evidence side's whole input.
   */
  private void shipping(String storedAs, String artifact, String embedded) {
    UUID release =
        store.upsertArtifact(Ecosystem.MAVEN, artifact, "1.0.0", storedAs, Instant.now());
    store.replaceGraph(
        release,
        List.of(new ParsedSbom.Component("c-1", null, Ecosystem.MAVEN, embedded, "1.0.0", false)),
        List.of(new ParsedSbom.Edge(-1, 0)),
        Instant.now());
  }

  private static List<String> names(DownstreamResolver.Closure closure) {
    return closure.downstream().stream().map(DownstreamResolver.Downstream::repository).toList();
  }

  private static DownstreamResolver.Downstream entry(
      DownstreamResolver.Closure closure, String repository) {
    return closure.downstream().stream()
        .filter(row -> repository.equals(row.repository()))
        .findFirst()
        .orElseThrow(() -> new AssertionError(repository + " is not in the closure"));
  }

  // --- the bug the trains had -------------------------------------------------------------------

  /**
   * <b>THE SECOND HOP, WHICH IS THE WHOLE REASON THIS CLASS EXISTS.</b>
   *
   * <p>A library publishes an npm package; a frontend pins it; a service carries that frontend as a
   * {@code webui} submodule. The release train's derivation asked "who pins the released
   * coordinate", once, and answered `[frontend]` — the service was invisible to it because a
   * gitlink is not a released coordinate and because the answer was never re-asked from the
   * frontend's side. Here it is two rows, and the second one names the first as its {@code via}.
   */
  @Test
  void aLibraryReachesTheServiceBehindTheFrontendThroughTheGitlinkPin() {
    String library = "qits-ui-components-jslib" + run;
    String pkg = "@qits/ui-components" + run;
    String frontend = "qits-ci-frontend" + run;
    String frontendPkg = "@qits/ci-spa" + run;
    String service = "qits-ci-service" + run;

    scanned(library, "r-lib" + run, RepositoryArchetype.LIBRARY);
    published(library, Ecosystem.NPM, pkg, "2026.905.1");
    scanned(frontend, "r-fe" + run, RepositoryArchetype.FRONTEND, npm(pkg));
    published(frontend, Ecosystem.NPM, frontendPkg, "2026.905.2");
    // The service pins the FRONTEND REPOSITORY, not its package: that is what a submodule is.
    scanned(service, "r-svc" + run, RepositoryArchetype.SERVICE, gitlink(frontend));

    DownstreamResolver.Closure closure = resolver.of(library);

    assertEquals(library, closure.repository());
    assertEquals("r-lib" + run, closure.catalogId());
    assertEquals(List.of(frontend, service), names(closure), "depth ascending, then name");

    assertEquals(1, entry(closure, frontend).depth());
    assertEquals(List.of(library), entry(closure, frontend).via());
    assertEquals("FRONTEND", entry(closure, frontend).archetype());
    assertEquals("r-fe" + run, entry(closure, frontend).catalogId());

    assertEquals(2, entry(closure, service).depth(), "the hop the release trains could not make");
    assertEquals(List.of(frontend), entry(closure, service).via());
    assertEquals("r-svc" + run, entry(closure, service).catalogId());
  }

  /** The evidence side is real too: an SBOM naming the package with no manifest pin behind it. */
  @Test
  void aRepositoryThatOnlySHIPSTheCoordinateIsDownstreamOfIt() {
    String library = "qits-lib" + run;
    String pkg = "eu.wohlben.qits:qits-lib" + run;
    String consumer = "qits-consumer" + run;

    scanned(library, null, RepositoryArchetype.LIBRARY);
    published(library, Ecosystem.MAVEN, pkg, "1.0.0");
    // No pin at all — a transitive, which is exactly what no manifest can name.
    scanned(consumer, null, RepositoryArchetype.SERVICE);
    UUID release =
        store.upsertArtifact(
            Ecosystem.MAVEN, "eu.wohlben.qits:qits-consumer" + run, "3.0.0", consumer,
            Instant.now());
    store.replaceGraph(
        release,
        List.of(new ParsedSbom.Component("c-1", null, Ecosystem.MAVEN, pkg, "1.0.0", false)),
        List.of(new ParsedSbom.Edge(-1, 0)),
        Instant.now());

    assertEquals(List.of(consumer), names(resolver.of(library)));
  }

  // --- termination and bounds -------------------------------------------------------------------

  /**
   * <b>A cycle is a finite walk.</b> Two repositories that end up consuming each other's releases
   * are a real shape in an estate this size, and the honest answer is "the other one, once" rather
   * than a request that never returns.
   */
  @Test
  void aCycleTerminatesAndTheRootIsNeverItsOwnDownstream() {
    String left = "qits-left" + run;
    String right = "qits-right" + run;
    String leftPkg = "@qits/left" + run;
    String rightPkg = "@qits/right" + run;

    scanned(left, null, RepositoryArchetype.LIBRARY, npm(rightPkg));
    published(left, Ecosystem.NPM, leftPkg, "1.0.0");
    scanned(right, null, RepositoryArchetype.LIBRARY, npm(leftPkg));
    published(right, Ecosystem.NPM, rightPkg, "1.0.0");

    assertEquals(List.of(right), names(resolver.of(left)));
    assertEquals(List.of(left), names(resolver.of(right)));
  }

  /** And a longer one: three repositories round a ring, walked once each. */
  @Test
  void aThreeWayCycleNamesEachOtherRepositoryExactlyOnce() {
    List<String> repositories = new ArrayList<>();
    List<String> packages = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      repositories.add("qits-ring-" + i + run);
      packages.add("@qits/ring-" + i + run);
    }
    for (int i = 0; i < 3; i++) {
      // Each pins the NEXT one round the ring.
      scanned(repositories.get(i), null, RepositoryArchetype.LIBRARY, npm(packages.get((i + 1) % 3)));
      published(repositories.get(i), Ecosystem.NPM, packages.get(i), "1.0.0");
    }

    DownstreamResolver.Closure closure = resolver.of(repositories.get(0));
    // 0 is pinned by 2, which is pinned by 1. Two rows, one each, and the root is not among them.
    assertEquals(2, closure.downstream().size());
    assertEquals(1, entry(closure, repositories.get(2)).depth());
    assertEquals(2, entry(closure, repositories.get(1)).depth());
  }

  /**
   * <b>The depth bound truncates rather than throwing</b>, and it is a bound on a chain no estate
   * has: a library → a component library → a frontend → a service → the wrapper is four.
   */
  @Test
  void aChainLongerThanTheDepthBoundIsCutAtTheBoundWithNoFailure() {
    int length = DownstreamResolver.MAX_DEPTH + 3;
    List<String> repositories = new ArrayList<>();
    List<String> packages = new ArrayList<>();
    for (int i = 0; i < length; i++) {
      repositories.add("qits-chain-" + i + run);
      packages.add("@qits/chain-" + i + run);
    }
    for (int i = 0; i < length; i++) {
      // Each link pins the one BEFORE it, so the closure of link 0 is the whole chain.
      ParsedPin[] pins = i == 0 ? new ParsedPin[0] : new ParsedPin[] {npm(packages.get(i - 1))};
      scanned(repositories.get(i), null, RepositoryArchetype.LIBRARY, pins);
      published(repositories.get(i), Ecosystem.NPM, packages.get(i), "1.0.0");
    }

    DownstreamResolver.Closure closure = resolver.of(repositories.get(0));

    assertEquals(DownstreamResolver.MAX_DEPTH, closure.downstream().size());
    assertEquals(
        DownstreamResolver.MAX_DEPTH,
        closure.downstream().get(closure.downstream().size() - 1).depth());
    assertEquals(500, DownstreamResolver.MAX_REPOSITORIES, "the other bound, pinned as a value");
  }

  // --- the wrapper ------------------------------------------------------------------------------

  /**
   * <b>The wrapper is one documented filter and it is the ARCHETYPE, not the ecosystem.</b>
   *
   * <p>The home repository gitlink-pins every submodule on the platform, so a gitlink-aware walk
   * that did not exclude it would put it on every answer this route ever gives — and then expand it,
   * which reaches the whole estate in one hop. The trains dealt with that by pretending GITLINK did
   * not exist, which is what cost them the frontend→service hop.
   */
  @Test
  void theWrapperIsNeitherAnAnswerNorAWayThrough() {
    String library = "qits-wrapped" + run;
    String other = "qits-unrelated" + run;
    String wrapper = "qits-qits" + run;

    scanned(library, null, RepositoryArchetype.LIBRARY);
    scanned(other, null, RepositoryArchetype.SERVICE);
    // The estate rather than a member of it: it submodules both.
    scanned(wrapper, null, RepositoryArchetype.PROJECT, gitlink(library), gitlink(other));

    assertTrue(
        resolver.of(library).downstream().isEmpty(),
        "the wrapper is excluded, and nothing else consumes the library");
  }

  // --- via, and the two spellings ----------------------------------------------------------------

  /**
   * <b>{@code via} names every upstream one hop nearer the root</b>, not just the first one found.
   * Two paths to one repository is the ordinary shape — a service that pins a library directly AND
   * carries a frontend that pins it — and both are worth showing.
   */
  @Test
  void viaNamesEveryUpstreamAtTheLevelAboveInNameOrder() {
    String library = "qits-two-paths" + run;
    String pkg = "@qits/two-paths" + run;
    String middleA = "qits-aaa-middle" + run;
    String middleB = "qits-bbb-middle" + run;
    String packageA = "@qits/aaa" + run;
    String packageB = "@qits/bbb" + run;
    String service = "qits-zzz-service" + run;

    scanned(library, null, RepositoryArchetype.LIBRARY);
    published(library, Ecosystem.NPM, pkg, "1.0.0");
    scanned(middleA, null, RepositoryArchetype.LIBRARY, npm(pkg));
    published(middleA, Ecosystem.NPM, packageA, "1.0.0");
    scanned(middleB, null, RepositoryArchetype.LIBRARY, npm(pkg));
    published(middleB, Ecosystem.NPM, packageB, "1.0.0");
    scanned(service, null, RepositoryArchetype.SERVICE, npm(packageA), npm(packageB));

    DownstreamResolver.Closure closure = resolver.of(library);

    assertEquals(List.of(middleA, middleB, service), names(closure));
    assertEquals(List.of(middleA, middleB), entry(closure, service).via());
    assertEquals(2, entry(closure, service).depth());
  }

  /**
   * <b>The uuid spelling resolves, and the answer is always the NAME.</b> qits-projects addresses a
   * repository by its own row id and has nothing else — its adapter calls this route with exactly
   * that — so a 404 or an empty answer for the id of the very repository that released would be the
   * wedge V5 measured, wearing a different route.
   */
  @Test
  void theRootIsAddressableByCatalogIdAndTheAnswerIsSpelledAsTheCatalogNames() {
    String library = "qits-addressed" + run;
    String catalogId = "row-" + UUID.randomUUID();
    String pkg = "@qits/addressed" + run;
    String consumer = "qits-addressed-consumer" + run;

    scanned(library, catalogId, RepositoryArchetype.LIBRARY);
    published(library, Ecosystem.NPM, pkg, "1.0.0");
    scanned(consumer, null, RepositoryArchetype.SERVICE, npm(pkg));

    DownstreamResolver.Closure byId = resolver.of(catalogId);

    assertEquals(library, byId.repository(), "asked by id, answered by name");
    assertEquals(catalogId, byId.catalogId());
    assertEquals(List.of(consumer), names(byId));
    assertEquals(names(resolver.of(library)), names(byId), "both spellings, one answer");
  }

  /**
   * <b>An unknown repository is an empty closure and never a refusal.</b> qits-projects asks this on
   * its announce path; a repository this inventory has never scanned must cost that announce
   * nothing.
   */
  @Test
  void anUnknownRepositoryIsAnEmptyClosureRatherThanARefusal() {
    DownstreamResolver.Closure closure = resolver.of("qits-never-scanned" + run);

    assertEquals("qits-never-scanned" + run, closure.repository(), "the spelling is kept");
    assertNull(closure.catalogId());
    assertTrue(closure.downstream().isEmpty());
  }

  // --- renamed and retired repositories (qits-2375) ---------------------------------------------

  /**
   * <b>A rename is reported ONCE, under the live name</b> (qits-2375). Measured live: the closure of
   * {@code qits-eventstream-javalib} named both {@code qits-edge-platform-service} — the ABSENT row
   * the rename left, sharing its {@code catalog_id} — and {@code qits-edge-service}, because the
   * release before the rename filed its bill of materials under the old name and the old private
   * translation only knew catalog ids.
   */
  @Test
  void aRenamedConsumerIsReportedOnceUnderItsLiveNameAndNeverTheOldOne() {
    String library = "qits-eventstream-javalib" + run;
    String pkg = "eu.wohlben.qits:qits-eventstream" + run;
    String catalogId = "row-" + UUID.randomUUID();
    String live = "qits-edge-service" + run;
    String old = "qits-edge-platform-service" + run;

    scanned(library, null, RepositoryArchetype.LIBRARY);
    published(library, Ecosystem.MAVEN, pkg, "1.0.0");
    scanned(live, catalogId, RepositoryArchetype.SERVICE);
    retired(old, catalogId, RepositoryArchetype.SERVICE, RepositoryStatus.ABSENT);
    // The release before the rename, filed under the old name, and one after it under the new.
    shipping(old, "eu.wohlben.qits:qits-edge-before" + run, pkg);
    shipping(live, "eu.wohlben.qits:qits-edge-after" + run, pkg);

    DownstreamResolver.Closure closure = resolver.of(library);

    assertEquals(List.of(live), names(closure), "the live name once, the old one never");
    assertEquals(catalogId, entry(closure, live).catalogId());
    assertEquals("SERVICE", entry(closure, live).archetype());
  }

  /**
   * <b>An UNDECLARED repository with no successor is not downstream</b>: its project dropped it, it
   * will never release again, and the only way it reaches a closure is an old bill of materials.
   */
  @Test
  void anUndeclaredConsumerWithNoLiveSuccessorIsOmitted() {
    String library = "qits-lib-undeclared" + run;
    String pkg = "eu.wohlben.qits:qits-lib-undeclared" + run;
    String gone = "qits-ci-runner-daemon" + run;

    scanned(library, null, RepositoryArchetype.LIBRARY);
    published(library, Ecosystem.MAVEN, pkg, "1.0.0");
    retired(gone, "row-" + UUID.randomUUID(), RepositoryArchetype.DAEMON, RepositoryStatus.UNDECLARED);
    shipping(gone, "eu.wohlben.qits:qits-ci-runner" + run, pkg);

    assertTrue(resolver.of(library).downstream().isEmpty());
  }

  /** And the catalog's ABSENT, with nothing answering for its id, is the same: omitted. */
  @Test
  void anAbsentConsumerWithNoLiveSuccessorIsOmitted() {
    String library = "qits-lib-absent" + run;
    String pkg = "eu.wohlben.qits:qits-lib-absent" + run;
    String gone = "qits-dropped" + run;

    scanned(library, null, RepositoryArchetype.LIBRARY);
    published(library, Ecosystem.MAVEN, pkg, "1.0.0");
    retired(gone, "row-" + UUID.randomUUID(), RepositoryArchetype.SERVICE, RepositoryStatus.ABSENT);
    shipping(gone, "eu.wohlben.qits:qits-dropped" + run, pkg);

    assertTrue(resolver.of(library).downstream().isEmpty());
  }

  /**
   * <b>A retired consumer is not a way through either.</b> Expanding it would add everything IT
   * once published to — here a live repository that pins the retired one's package and is
   * downstream of the root by nothing else.
   */
  @Test
  void aRetiredConsumerIsNotExpanded() {
    String library = "qits-lib-dead-end" + run;
    String pkg = "eu.wohlben.qits:qits-lib-dead-end" + run;
    String gone = "qits-dead-end" + run;
    String goneMaven = "eu.wohlben.qits:qits-dead-end" + run;
    String beyond = "qits-beyond" + run;

    scanned(library, null, RepositoryArchetype.LIBRARY);
    published(library, Ecosystem.MAVEN, pkg, "1.0.0");
    retired(gone, "row-" + UUID.randomUUID(), RepositoryArchetype.LIBRARY, RepositoryStatus.ABSENT);
    shipping(gone, goneMaven, pkg);
    scanned(
        beyond,
        null,
        RepositoryArchetype.SERVICE,
        ParsedPin.of(Ecosystem.MAVEN, "pom.xml", goneMaven, "1.0.0", null, "dependency:" + goneMaven));

    assertTrue(
        resolver.of(library).downstream().isEmpty(),
        "neither the retired repository nor what is downstream only of it");
  }

  /**
   * <b>What a live repository released under its OLD name is still its coordinate.</b> The package
   * was filed under the name it had before the rename; a consumer pinning it is downstream of the
   * repository that now carries that name's catalog id — and asking by the old name walks from the
   * live one.
   */
  @Test
  void anArtifactFiledUnderARetiredOldNameCountsAsTheLiveRepositorysCoordinate() {
    String catalogId = "row-" + UUID.randomUUID();
    String live = "qits-renamed-jslib" + run;
    String old = "qits-renamed-platform-jslib" + run;
    String pkg = "@qits/renamed" + run;
    String consumer = "qits-renamed-consumer" + run;

    scanned(live, catalogId, RepositoryArchetype.LIBRARY);
    retired(old, catalogId, RepositoryArchetype.LIBRARY, RepositoryStatus.ABSENT);
    published(old, Ecosystem.NPM, pkg, "1.0.0");
    scanned(consumer, null, RepositoryArchetype.FRONTEND, npm(pkg));

    assertEquals(List.of(consumer), names(resolver.of(live)));

    DownstreamResolver.Closure byOldName = resolver.of(old);
    assertEquals(live, byOldName.repository(), "asked by the old name, answered by the live one");
    assertEquals(catalogId, byOldName.catalogId());
    assertEquals(List.of(consumer), names(byOldName));
  }

  /**
   * <b>A catalog id two rows share answers with the LIVE one</b>, whatever the name order
   * (qits-1735's rule, which the old private translation lacked). The retired name sorts after the
   * live one here — exactly the row a last-wins put in name order let answer.
   */
  @Test
  void aSharedCatalogIdResolvesToTheLiveRowEvenWhenTheRetiredNameSortsAfterIt() {
    String library = "qits-lib-tie" + run;
    String pkg = "eu.wohlben.qits:qits-lib-tie" + run;
    String catalogId = "row-" + UUID.randomUUID();
    String live = "qits-a-live" + run;
    String old = "qits-z-retired" + run;

    scanned(library, null, RepositoryArchetype.LIBRARY);
    published(library, Ecosystem.MAVEN, pkg, "1.0.0");
    scanned(live, catalogId, RepositoryArchetype.SERVICE);
    retired(old, catalogId, RepositoryArchetype.SERVICE, RepositoryStatus.ABSENT);
    // Filed under the catalog id, as qits-ci's SoftwareRelease spells it.
    shipping(catalogId, "eu.wohlben.qits:qits-tie-service" + run, pkg);

    DownstreamResolver.Closure closure = resolver.of(library);

    assertEquals(List.of(live), names(closure));
    assertEquals(live, resolver.of(catalogId).repository(), "and the root reads the same way");
  }
}
