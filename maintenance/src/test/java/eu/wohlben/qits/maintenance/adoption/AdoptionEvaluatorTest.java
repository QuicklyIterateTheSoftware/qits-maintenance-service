package eu.wohlben.qits.maintenance.adoption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.manifest.ParsedPin;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.GroupSource;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.model.RepositoryArchetype;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.sbom.ParsedSbom;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The verdict at each hop: ADOPTED or PENDING, and which release of theirs proves it.</b>
 *
 * <p>The rules here are the retired release trains' match rules, kept verbatim because they were
 * the half of that feature that was right — a bill of materials is the evidence, the comparison is
 * inclusive and in the ecosystem's own order, and a component this service cannot map never matches.
 * What is new is the CHAIN: the evaluation walks the closure, and each ADOPTED hop's own release
 * becomes the requirement for the hop behind it.
 *
 * <p><b>Every fixture name carries a uuid</b>, because this module has no {@code InventoryReset} and
 * the walk reads the whole inventory. See {@code DownstreamResolverTest}, which makes the same
 * argument at more length.
 */
@QuarkusTest
class AdoptionEvaluatorTest {

  private static final Instant MARCH = Instant.parse("2026-03-01T10:00:00Z");
  private static final Instant APRIL = Instant.parse("2026-04-01T10:00:00Z");
  private static final Instant MAY = Instant.parse("2026-05-01T10:00:00Z");
  private static final Instant JUNE = Instant.parse("2026-06-01T10:00:00Z");

  @Inject MaintenanceStore store;

  @Inject AdoptionEvaluator evaluator;

  private String run;

  @BeforeEach
  void aRunOfItsOwn() {
    run = "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  // --- the fixture ------------------------------------------------------------------------------

  private void scanned(String repository, RepositoryArchetype archetype, ParsedPin... pins) {
    store.replaceInventory(
        repository,
        "qits",
        null,
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

  private static ParsedPin pin(Ecosystem ecosystem, String name) {
    return ParsedPin.of(
        ecosystem,
        ecosystem == Ecosystem.NPM ? "package.json" : "pom.xml",
        name,
        "0.0.1",
        null,
        "dependency:" + name);
  }

  /** A release with no document read yet — PENDING, and evidence of nothing. */
  private UUID releasedWithoutADocument(
      String repository, Ecosystem ecosystem, String name, String version, Instant when) {
    return store.upsertArtifact(ecosystem, name, version, repository, when);
  }

  /** A release whose bill of materials was read, naming whatever is passed. */
  private void released(
      String repository,
      Ecosystem ecosystem,
      String name,
      String version,
      Instant when,
      ParsedSbom.Component... components) {
    UUID id = releasedWithoutADocument(repository, ecosystem, name, version, when);
    store.replaceGraph(id, List.of(components), List.of(), Instant.now());
  }

  private static ParsedSbom.Component contains(Ecosystem ecosystem, String name, String version) {
    return new ParsedSbom.Component("c-" + name, null, ecosystem, name, version, true);
  }

  /**
   * A release as the LEDGER records it: the tree at its tag, and what that tree declared. This is
   * the second evidence kind, and it is the only one a frontend can ever produce — it publishes no
   * registry artifact, so there is deliberately no {@code released(...)} beside these calls.
   */
  private void declared(
      String repository,
      String version,
      String sha,
      Instant when,
      MaintenanceStore.ReleasePin... pins) {
    store.recordRelease(repository, version, sha, when, List.of(pins));
  }

  private static MaintenanceStore.ReleasePin declares(
      Ecosystem ecosystem, String name, String version) {
    return new MaintenanceStore.ReleasePin(ecosystem, name, version);
  }

  /** A submodule pin, whose "version" is the commit the parent's tree holds at that path. */
  private static MaintenanceStore.ReleasePin submodules(String repository, String sha) {
    return new MaintenanceStore.ReleasePin(Ecosystem.GITLINK, repository, sha);
  }

  /** A plausible 40-hex object name, seeded so two fixtures cannot collide. */
  private static String commit(String seed) {
    StringBuilder sha = new StringBuilder(Integer.toHexString(seed.hashCode()));
    while (sha.length() < 40) {
      sha.append("0123456789abcdef", sha.length() % 16, (sha.length() % 16) + 1);
    }
    return sha.substring(0, 40);
  }

  private static AdoptionEvaluator.Adopter adopter(
      AdoptionEvaluator.Journey journey, String repository) {
    return journey.adopters().stream()
        .filter(row -> repository.equals(row.repository()))
        .findFirst()
        .orElseThrow(() -> new AssertionError(repository + " is not in the journey"));
  }

  // --- the comparison ---------------------------------------------------------------------------

  /**
   * <b>Inclusive, and in the ecosystem's OWN order.</b> A consumer that took the released version
   * has adopted it and one that skipped straight past it has adopted it too; a consumer below it is
   * still carrying the old copy, which is precisely the state this question exists to show.
   *
   * <p>The two "past" cases are chosen so a string comparison gets them wrong: {@code 2026.821.10}
   * is lexically BELOW {@code 2026.821.9} and numerically above it, and an npm prerelease sorts
   * below the release it is a candidate for while sorting above it as text.
   */
  @Test
  void theComparisonIsInclusiveAndUsesTheEcosystemsOwnOrder() {
    String library = "qits-order-lib" + run;
    String mavenPackage = "eu.wohlben.qits:qits-order" + run;

    scanned(library, RepositoryArchetype.LIBRARY);
    released(library, Ecosystem.MAVEN, mavenPackage, "2026.821.9", MARCH);

    // Exactly the released version: adopted.
    String exact = "qits-order-exact" + run;
    scanned(exact, RepositoryArchetype.SERVICE, pin(Ecosystem.MAVEN, mavenPackage));
    released(
        exact,
        Ecosystem.MAVEN,
        "eu.wohlben.qits:exact" + run,
        "1.0.0",
        APRIL,
        contains(Ecosystem.MAVEN, mavenPackage, "2026.821.9"));

    // Past it, and lexically BELOW it: adopted, because the order is maven's.
    String past = "qits-order-past" + run;
    scanned(past, RepositoryArchetype.SERVICE, pin(Ecosystem.MAVEN, mavenPackage));
    released(
        past,
        Ecosystem.MAVEN,
        "eu.wohlben.qits:past" + run,
        "1.0.0",
        APRIL,
        contains(Ecosystem.MAVEN, mavenPackage, "2026.821.10"));

    // Behind it: pending, which is the whole point of asking.
    String behind = "qits-order-behind" + run;
    scanned(behind, RepositoryArchetype.SERVICE, pin(Ecosystem.MAVEN, mavenPackage));
    released(
        behind,
        Ecosystem.MAVEN,
        "eu.wohlben.qits:behind" + run,
        "1.0.0",
        APRIL,
        contains(Ecosystem.MAVEN, mavenPackage, "2026.820.1"));

    AdoptionEvaluator.Journey journey = evaluator.of(library, "2026.821.9");

    assertEquals(AdoptionEvaluator.State.ADOPTED, adopter(journey, exact).state());
    assertEquals(AdoptionEvaluator.State.ADOPTED, adopter(journey, past).state());
    assertEquals(AdoptionEvaluator.State.PENDING, adopter(journey, behind).state());
  }

  // --- publish-if-changed: release version is not artifact version --------------------------------

  /**
   * <b>An artifact the release left unchanged is required at its "unchanged since" version.</b>
   * Release V publishes only the image; the library jar stays at U. A consumer carrying the jar at U
   * has everything V shipped of it and is ADOPTED; one below U is PENDING. A later release's jar
   * is not what V carried.
   */
  @Test
  void anUnchangedArtifactIsRequiredAtTheVersionItWasLastPublishedAt() {
    String library = "qits-unchanged-lib" + run;
    String jar = "eu.wohlben.qits:qits-unchanged" + run;
    String image = "qits/unchanged" + run;

    scanned(library, RepositoryArchetype.LIBRARY);
    released(library, Ecosystem.MAVEN, jar, "2026.821.9", MARCH);
    released(library, Ecosystem.DOCKER, image, "2026.821.9", MARCH);
    // V: the jar's content did not change, so only the image was published.
    released(library, Ecosystem.DOCKER, image, "2026.900.1", APRIL);
    // A later release that changed the jar: not what V carried.
    released(library, Ecosystem.MAVEN, jar, "2026.910.1", JUNE);

    String carrying = "qits-unchanged-carrying" + run;
    scanned(carrying, RepositoryArchetype.SERVICE, pin(Ecosystem.MAVEN, jar));
    released(
        carrying,
        Ecosystem.MAVEN,
        "eu.wohlben.qits:carrying" + run,
        "1.0.0",
        MAY,
        contains(Ecosystem.MAVEN, jar, "2026.821.9"));

    String behind = "qits-unchanged-behind" + run;
    scanned(behind, RepositoryArchetype.SERVICE, pin(Ecosystem.MAVEN, jar));
    released(
        behind,
        Ecosystem.MAVEN,
        "eu.wohlben.qits:behind" + run,
        "1.0.0",
        MAY,
        contains(Ecosystem.MAVEN, jar, "2026.800.1"));

    AdoptionEvaluator.Journey journey = evaluator.of(library, "2026.900.1");

    assertTrue(
        journey.packages().contains(new ReleaseCoordinates.Coordinate(Ecosystem.MAVEN, jar)),
        "the unchanged jar is still a package of V: " + journey.packages());
    assertEquals(AdoptionEvaluator.State.ADOPTED, adopter(journey, carrying).state());
    assertEquals("1.0.0", adopter(journey, carrying).adoptedVersion());
    assertEquals(AdoptionEvaluator.State.PENDING, adopter(journey, behind).state());
  }

  /** A version this service knows no release of carries nothing, as before. */
  @Test
  void aVersionNoReleaseNamesCarriesNothing() {
    String library = "qits-unknown-version-lib" + run;
    scanned(library, RepositoryArchetype.LIBRARY);
    released(library, Ecosystem.MAVEN, "eu.wohlben.qits:unknown" + run, "2026.821.9", MARCH);

    assertTrue(evaluator.of(library, "2026.900.1").packages().isEmpty());
  }

  /** And npm's order is semver's, where a release candidate is BELOW the release it precedes. */
  @Test
  void anNpmPrereleaseIsBelowTheReleaseItIsACandidateFor() {
    String library = "qits-semver-lib" + run;
    String pkg = "@qits/semver" + run;

    scanned(library, RepositoryArchetype.LIBRARY);
    released(library, Ecosystem.NPM, pkg, "21.0.0", MARCH);

    String consumer = "qits-semver-consumer" + run;
    scanned(consumer, RepositoryArchetype.FRONTEND, pin(Ecosystem.NPM, pkg));
    released(
        consumer,
        Ecosystem.NPM,
        "@qits/semver-consumer" + run,
        "1.0.0",
        APRIL,
        // Text says this is "greater than" 21.0.0. Semver says it is the candidate that came first.
        contains(Ecosystem.NPM, pkg, "21.0.0-rc.1"));

    assertEquals(
        AdoptionEvaluator.State.PENDING,
        adopter(evaluator.of(library, "21.0.0"), consumer).state());
  }

  /**
   * <b>A component with no ecosystem never matches.</b> {@code pkg:golang/…},
   * {@code pkg:generic/…}, a document with no purl at all: stored, shown on the repository page,
   * and compared with nothing. A name in a world this platform does not inventory means something
   * else.
   */
  @Test
  void aComponentWithNoEcosystemIsNeverEvidenceOfAnything() {
    String library = "qits-unmapped-lib" + run;
    String pkg = "eu.wohlben.qits:qits-unmapped" + run;

    scanned(library, RepositoryArchetype.LIBRARY);
    released(library, Ecosystem.MAVEN, pkg, "1.0.0", MARCH);

    String consumer = "qits-unmapped-consumer" + run;
    // The pin is what puts it in the closure at all; the document is what fails to close it.
    scanned(consumer, RepositoryArchetype.SERVICE, pin(Ecosystem.MAVEN, pkg));
    released(
        consumer,
        Ecosystem.MAVEN,
        "eu.wohlben.qits:unmapped-consumer" + run,
        "2.0.0",
        APRIL,
        new ParsedSbom.Component("c-1", "pkg:golang/x/y@9.9.9", null, pkg, "9.9.9", true));

    AdoptionEvaluator.Journey journey = evaluator.of(library, "1.0.0");

    assertEquals(AdoptionEvaluator.State.PENDING, adopter(journey, consumer).state());
    assertNull(adopter(journey, consumer).adoptedVersion());
  }

  /**
   * <b>A release whose document has not been read is evidence of nothing — including of a
   * NON-adoption.</b> A row that is PENDING, MISSING or FAILED holds no components, so the honest
   * answer stays PENDING rather than becoming "they did not take it".
   */
  @Test
  void aReleaseWithNoIngestedDocumentLeavesTheAnswerPending() {
    String library = "qits-nodoc-lib" + run;
    String pkg = "eu.wohlben.qits:qits-nodoc" + run;

    scanned(library, RepositoryArchetype.LIBRARY);
    released(library, Ecosystem.MAVEN, pkg, "1.0.0", MARCH);

    String consumer = "qits-nodoc-consumer" + run;
    scanned(consumer, RepositoryArchetype.SERVICE, pin(Ecosystem.MAVEN, pkg));
    releasedWithoutADocument(
        consumer, Ecosystem.MAVEN, "eu.wohlben.qits:nodoc-consumer" + run, "2.0.0", APRIL);

    assertEquals(
        AdoptionEvaluator.State.PENDING,
        adopter(evaluator.of(library, "1.0.0"), consumer).state());
  }

  // --- which release is reported ------------------------------------------------------------------

  /**
   * <b>THE EARLIEST MATCHING RELEASE, BY {@code occurred_at}.</b> A library taken in April and
   * shipped in every release since was adopted in April; the newest release carrying it is not when
   * it arrived. And the version reported is the ADOPTER's own — it is half of the address of the
   * release request that adoption opened.
   */
  @Test
  void theEarliestReleaseThatCarriesItIsTheAdoptionAndItIsTheAdoptersOwnVersion() {
    String library = "qits-earliest-lib" + run;
    String pkg = "@qits/earliest" + run;

    scanned(library, RepositoryArchetype.LIBRARY);
    released(library, Ecosystem.NPM, pkg, "2026.905.1", MARCH);

    String consumer = "qits-earliest-consumer" + run;
    String consumerPackage = "@qits/earliest-consumer" + run;
    scanned(consumer, RepositoryArchetype.FRONTEND, pin(Ecosystem.NPM, pkg));
    // Three releases of the consumer, two of them carrying it. The one in JUNE is the newest and is
    // deliberately written first, so an implementation that took "whatever came back first" fails.
    released(
        consumer, Ecosystem.NPM, consumerPackage, "3.0.0", JUNE,
        contains(Ecosystem.NPM, pkg, "2026.905.4"));
    released(
        consumer, Ecosystem.NPM, consumerPackage, "2.0.0", MAY,
        contains(Ecosystem.NPM, pkg, "2026.905.1"));
    released(
        consumer, Ecosystem.NPM, consumerPackage, "1.0.0", APRIL,
        contains(Ecosystem.NPM, pkg, "2026.900.1"));

    AdoptionEvaluator.Adopter adopted =
        adopter(evaluator.of(library, "2026.905.1"), consumer);

    assertEquals(AdoptionEvaluator.State.ADOPTED, adopted.state());
    assertEquals("2.0.0", adopted.adoptedVersion(), "the consumer's OWN release, and the first one");
    assertEquals(MAY, adopted.adoptedAt());
  }

  // --- the chain ----------------------------------------------------------------------------------

  /**
   * <b>Each ADOPTED hop's own release is the requirement for the hop behind it</b>, and a PENDING
   * hop stops the chain there — there is no version of it to require yet. A service cannot be
   * shipping a library through a frontend that has not shipped the library.
   */
  @Test
  void aPendingParentLeavesItsChildrenPendingAndAnAdoptedOneCarriesTheChainOn() {
    String library = "qits-chain-lib" + run;
    String libraryPackage = "@qits/chain-lib" + run;
    String frontend = "qits-chain-frontend" + run;
    String frontendPackage = "@qits/chain-frontend" + run;
    String service = "qits-chain-service" + run;

    scanned(library, RepositoryArchetype.LIBRARY);
    released(library, Ecosystem.NPM, libraryPackage, "2026.905.1", MARCH);

    // The frontend has NOT taken it: its only release carries an older copy.
    scanned(frontend, RepositoryArchetype.FRONTEND, pin(Ecosystem.NPM, libraryPackage));
    released(
        frontend, Ecosystem.NPM, frontendPackage, "1.0.0", APRIL,
        contains(Ecosystem.NPM, libraryPackage, "2026.900.1"));

    // …and the service is carrying the frontend's bundle at a version far beyond anything, which
    // must NOT make it adopted: what it is carrying is a frontend that never took the library.
    scanned(service, RepositoryArchetype.SERVICE, pin(Ecosystem.NPM, frontendPackage));
    released(
        service, Ecosystem.MAVEN, "eu.wohlben.qits:chain-service" + run, "9.0.0", MAY,
        contains(Ecosystem.NPM, frontendPackage, "99.0.0"));

    AdoptionEvaluator.Journey blocked = evaluator.of(library, "2026.905.1");
    assertEquals(AdoptionEvaluator.State.PENDING, adopter(blocked, frontend).state());
    assertEquals(
        AdoptionEvaluator.State.PENDING,
        adopter(blocked, service).state(),
        "a child of a PENDING chain has no requirement to have met");
    assertEquals(2, adopter(blocked, service).depth());

    // Now the frontend releases with it. The SAME service release closes, because the frontend's
    // adopting version (2.0.0) is now the requirement and the service is carrying 99.0.0.
    released(
        frontend, Ecosystem.NPM, frontendPackage, "2.0.0", JUNE,
        contains(Ecosystem.NPM, libraryPackage, "2026.905.1"));

    AdoptionEvaluator.Journey travelled = evaluator.of(library, "2026.905.1");
    assertEquals(AdoptionEvaluator.State.ADOPTED, adopter(travelled, frontend).state());
    assertEquals("2.0.0", adopter(travelled, frontend).adoptedVersion());
    assertEquals(AdoptionEvaluator.State.ADOPTED, adopter(travelled, service).state());
    assertEquals("9.0.0", adopter(travelled, service).adoptedVersion());
  }

  // --- the release-pin evidence -------------------------------------------------------------------

  /**
   * <b>THE BUG THIS EVIDENCE KIND EXISTS FOR.</b> A frontend publishes NOTHING to any registry —
   * its release is a git tag — so it has no {@code mt_artifact} row, no document, and no component
   * anywhere that could ever name the library it took. Measured live on 2026-09-08: fifteen
   * frontends, all reported PENDING, all of them carrying the release.
   *
   * <p>What proves it is the frontend's own released TREE. Not its working tree — a tag is
   * immutable and is tied to the version reported as {@code adoptedVersion}.
   */
  @Test
  void aFrontendThatPublishesNothingIsAdoptedThroughItsOwnReleasedTree() {
    String library = "qits-ui-components-jslib" + run;
    String pkg = "@qits/ui-components" + run;
    String took = "qits-took-frontend" + run;
    String behind = "qits-behind-frontend" + run;

    scanned(library, RepositoryArchetype.LIBRARY);
    released(library, Ecosystem.NPM, pkg, "2026.906.164412", MARCH);

    scanned(took, RepositoryArchetype.FRONTEND, pin(Ecosystem.NPM, pkg));
    declared(
        took, "2026.907.1", commit(took), APRIL, declares(Ecosystem.NPM, pkg, "2026.906.164412"));

    scanned(behind, RepositoryArchetype.FRONTEND, pin(Ecosystem.NPM, pkg));
    declared(
        behind, "2026.907.2", commit(behind), APRIL,
        declares(Ecosystem.NPM, pkg, "2026.905.1"));

    AdoptionEvaluator.Journey journey = evaluator.of(library, "2026.906.164412");

    AdoptionEvaluator.Adopter adopted = adopter(journey, took);
    assertEquals(AdoptionEvaluator.State.ADOPTED, adopted.state());
    assertEquals("2026.907.1", adopted.adoptedVersion(), "the frontend's OWN released version");
    assertEquals(APRIL, adopted.adoptedAt());

    assertEquals(
        AdoptionEvaluator.State.PENDING,
        adopter(journey, behind).state(),
        "a released tree declaring an older version is evidence of a NON-adoption");
  }

  /**
   * <b>THE SECOND HOP, PROVED.</b> A service consumes a frontend as a {@code webui} submodule, so
   * what its released tree declares is a COMMIT and never a version — and its own SBOM lists maven
   * components only, because a compiled Angular dist carries no npm metadata (239 and 0, measured
   * on {@code qits-ci-service}). The sha is resolved through the FRONTEND's ledger to the release
   * it belongs to, and that version is what the comparison sees.
   */
  @Test
  void aServiceIsAdoptedThroughAGitlinkShaResolvedInTheFrontendsLedger() {
    String library = "qits-hop-lib" + run;
    String pkg = "@qits/hop" + run;
    String frontend = "qits-hop-frontend" + run;
    String service = "qits-hop-service" + run;
    String taken = commit(frontend + "-taken");

    scanned(library, RepositoryArchetype.LIBRARY);
    released(library, Ecosystem.NPM, pkg, "2026.905.1", MARCH);

    // The frontend takes the library and releases; that release IS the commit `taken`.
    scanned(frontend, RepositoryArchetype.FRONTEND, pin(Ecosystem.NPM, pkg));
    declared(frontend, "2026.906.1", taken, APRIL, declares(Ecosystem.NPM, pkg, "2026.905.1"));

    // The service submodules the frontend, and its released tree holds that very commit.
    scanned(
        service,
        RepositoryArchetype.SERVICE,
        ParsedPin.of(
            Ecosystem.GITLINK, ".gitmodules", frontend, taken, null,
            "gitlink:service/src/main/webui"));
    declared(service, "2026.907.1", commit(service), MAY, submodules(frontend, taken));

    AdoptionEvaluator.Journey journey = evaluator.of(library, "2026.905.1");

    assertEquals(AdoptionEvaluator.State.ADOPTED, adopter(journey, frontend).state());
    AdoptionEvaluator.Adopter adopted = adopter(journey, service);
    assertEquals(2, adopted.depth(), "reached across the submodule edge");
    assertEquals(AdoptionEvaluator.State.ADOPTED, adopted.state());
    assertEquals("2026.907.1", adopted.adoptedVersion(), "the SERVICE's own released version");
    assertEquals(MAY, adopted.adoptedAt());
  }

  /** git's own rule for an abbreviated object name, which is what a tree may have recorded. */
  @Test
  void anAbbreviatedGitlinkShaStillResolvesToTheReleaseItNames() {
    String library = "qits-abbrev-lib" + run;
    String pkg = "@qits/abbrev" + run;
    String frontend = "qits-abbrev-frontend" + run;
    String service = "qits-abbrev-service" + run;
    String taken = commit(frontend + "-taken");

    scanned(library, RepositoryArchetype.LIBRARY);
    released(library, Ecosystem.NPM, pkg, "1.0.0", MARCH);
    scanned(frontend, RepositoryArchetype.FRONTEND, pin(Ecosystem.NPM, pkg));
    declared(frontend, "2.0.0", taken, APRIL, declares(Ecosystem.NPM, pkg, "1.0.0"));
    scanned(
        service,
        RepositoryArchetype.SERVICE,
        ParsedPin.of(
            Ecosystem.GITLINK, ".gitmodules", frontend, taken, null, "gitlink:webui"));
    declared(
        service, "3.0.0", commit(service), MAY, submodules(frontend, taken.substring(0, 8)));

    assertEquals(
        AdoptionEvaluator.State.ADOPTED,
        adopter(evaluator.of(library, "1.0.0"), service).state());
  }

  /**
   * <b>Two honest kinds of "no".</b> A sha that resolves to an OLDER release of the frontend is
   * evidence the service is carrying the frontend from before it took the library; a sha the ledger
   * cannot place at all is an off-release commit, or a release older than anything recorded — and
   * neither is evidence of an adoption.
   */
  @Test
  void aGitlinkShaResolvingToAnOlderReleaseOrToNothingIsNotAnAdoption() {
    String library = "qits-stale-lib" + run;
    String pkg = "@qits/stale" + run;
    String frontend = "qits-stale-frontend" + run;
    String stale = "qits-stale-service" + run;
    String lost = "qits-lost-service" + run;
    String before = commit(frontend + "-before");
    String after = commit(frontend + "-after");

    scanned(library, RepositoryArchetype.LIBRARY);
    released(library, Ecosystem.NPM, pkg, "2026.905.1", MARCH);

    scanned(frontend, RepositoryArchetype.FRONTEND, pin(Ecosystem.NPM, pkg));
    // Two releases of the frontend: the older one without the library, the newer one with it.
    declared(frontend, "1.0.0", before, APRIL, declares(Ecosystem.NPM, pkg, "2026.900.1"));
    declared(frontend, "2.0.0", after, MAY, declares(Ecosystem.NPM, pkg, "2026.905.1"));

    scanned(
        stale,
        RepositoryArchetype.SERVICE,
        ParsedPin.of(Ecosystem.GITLINK, ".gitmodules", frontend, before, null, "gitlink:webui"));
    declared(stale, "9.0.0", commit(stale), JUNE, submodules(frontend, before));

    scanned(
        lost,
        RepositoryArchetype.SERVICE,
        ParsedPin.of(Ecosystem.GITLINK, ".gitmodules", frontend, after, null, "gitlink:webui"));
    declared(lost, "9.0.0", commit(lost), JUNE, submodules(frontend, commit("nobody-released")));

    AdoptionEvaluator.Journey journey = evaluator.of(library, "2026.905.1");

    assertEquals(
        AdoptionEvaluator.State.ADOPTED,
        adopter(journey, frontend).state(),
        "the frontend's adopting release is 2.0.0, and that is what its children must carry");
    assertEquals(
        AdoptionEvaluator.State.PENDING,
        adopter(journey, stale).state(),
        "carrying the frontend from before it took the library");
    assertEquals(
        AdoptionEvaluator.State.PENDING,
        adopter(journey, lost).state(),
        "a commit the ledger cannot place proves nothing");
  }

  /**
   * <b>The chain is the same chain, whatever proves each link.</b> The service's requirement is the
   * FRONTEND's adopting version and never the library's, so a frontend that has not taken the
   * library leaves the service PENDING — even though the service is faithfully carrying the
   * frontend's newest release.
   */
  @Test
  void aPendingFrontendLeavesTheServicePendingAcrossTheGitlinkToo() {
    String library = "qits-blocked-lib" + run;
    String pkg = "@qits/blocked" + run;
    String frontend = "qits-blocked-frontend" + run;
    String service = "qits-blocked-service" + run;
    String first = commit(frontend + "-first");
    String taken = commit(frontend + "-taken");

    scanned(library, RepositoryArchetype.LIBRARY);
    released(library, Ecosystem.NPM, pkg, "2026.905.1", MARCH);

    // The frontend released once, and that release's tree declares an OLDER copy of the library.
    scanned(frontend, RepositoryArchetype.FRONTEND, pin(Ecosystem.NPM, pkg));
    declared(frontend, "1.0.0", first, APRIL, declares(Ecosystem.NPM, pkg, "2026.900.1"));

    // The service is already at the commit the frontend's NEXT release will be cut from — which is
    // the ordinary shape of a bump that landed before the sibling released.
    scanned(
        service,
        RepositoryArchetype.SERVICE,
        ParsedPin.of(Ecosystem.GITLINK, ".gitmodules", frontend, taken, null, "gitlink:webui"));
    declared(service, "9.0.0", commit(service), MAY, submodules(frontend, taken));

    AdoptionEvaluator.Journey blocked = evaluator.of(library, "2026.905.1");
    assertEquals(AdoptionEvaluator.State.PENDING, adopter(blocked, frontend).state());
    assertEquals(
        AdoptionEvaluator.State.PENDING,
        adopter(blocked, service).state(),
        "there is no version of the frontend to require yet");

    // The frontend takes it and releases that very commit; the service's SAME row closes, because
    // its requirement is now 2.0.0 and the commit it holds resolves to exactly that release.
    declared(frontend, "2.0.0", taken, JUNE, declares(Ecosystem.NPM, pkg, "2026.905.1"));

    AdoptionEvaluator.Journey travelled = evaluator.of(library, "2026.905.1");
    assertEquals("2.0.0", adopter(travelled, frontend).adoptedVersion());
    assertEquals(AdoptionEvaluator.State.ADOPTED, adopter(travelled, service).state());
    assertEquals("9.0.0", adopter(travelled, service).adoptedVersion());
  }

  /**
   * <b>The earliest wins ACROSS the two evidence kinds, not within each of them.</b> "When did this
   * repository start shipping it" has one answer, and it does not depend on which kind of proof
   * happened to be available for which release.
   */
  @Test
  void theEarliestMatchWinsAcrossTheDocumentAndTheReleasedTreeAlike() {
    String library = "qits-both-lib" + run;
    String pkg = "@qits/both" + run;
    String consumer = "qits-both-consumer" + run;

    scanned(library, RepositoryArchetype.LIBRARY);
    released(library, Ecosystem.NPM, pkg, "1.0.0", MARCH);

    scanned(consumer, RepositoryArchetype.SERVICE, pin(Ecosystem.NPM, pkg));
    // The document proves the MAY release…
    released(
        consumer, Ecosystem.NPM, "@qits/both-consumer" + run, "2.0.0", MAY,
        contains(Ecosystem.NPM, pkg, "1.0.0"));
    // …and the released tree proves an earlier one, which nothing published a document for.
    declared(consumer, "1.0.0", commit(consumer), APRIL, declares(Ecosystem.NPM, pkg, "1.0.0"));

    AdoptionEvaluator.Adopter adopted = adopter(evaluator.of(library, "1.0.0"), consumer);

    assertEquals(AdoptionEvaluator.State.ADOPTED, adopted.state());
    assertEquals("1.0.0", adopted.adoptedVersion(), "the April release, proved by its own tree");
    assertEquals(APRIL, adopted.adoptedAt());
  }

  /**
   * <b>The release ledger is a fact about a repository's OWN release and never about somebody
   * else's.</b> Two repositories declaring the same coordinate must not prove each other's
   * adoption, which is what {@code mt_release.repository} being the catalog name is for.
   */
  @Test
  void oneRepositorysReleasedTreeIsNeverEvidenceAboutAnother() {
    String library = "qits-mine-lib" + run;
    String pkg = "@qits/mine" + run;
    String took = "qits-mine-took" + run;
    String never = "qits-mine-never" + run;

    scanned(library, RepositoryArchetype.LIBRARY);
    released(library, Ecosystem.NPM, pkg, "1.0.0", MARCH);

    scanned(took, RepositoryArchetype.SERVICE, pin(Ecosystem.NPM, pkg));
    declared(took, "2.0.0", commit(took), APRIL, declares(Ecosystem.NPM, pkg, "1.0.0"));

    // In the closure by its pin, and with no release of its own recorded anywhere.
    scanned(never, RepositoryArchetype.SERVICE, pin(Ecosystem.NPM, pkg));

    AdoptionEvaluator.Journey journey = evaluator.of(library, "1.0.0");

    assertEquals(AdoptionEvaluator.State.ADOPTED, adopter(journey, took).state());
    assertEquals(AdoptionEvaluator.State.PENDING, adopter(journey, never).state());
    assertNull(adopter(journey, never).adoptedVersion());
  }

  // --- the release nobody has heard of --------------------------------------------------------------

  /**
   * <b>There is no "no such release", and that is the change from the trains.</b> The old
   * {@code /trains/by-release} answered 404 when a release had opened no station — a fact about the
   * log rather than about the release. An unknown release simply published no coordinate anybody
   * could be carrying, so the closure is real and every row of it is PENDING.
   */
  @Test
  void aReleaseThisServiceNeverHeardOfIsAnEmptyPackageListAndAPendingClosure() {
    String library = "qits-unknown-lib" + run;
    String pkg = "@qits/unknown" + run;
    String consumer = "qits-unknown-consumer" + run;

    scanned(library, RepositoryArchetype.LIBRARY);
    released(library, Ecosystem.NPM, pkg, "1.0.0", MARCH);
    scanned(consumer, RepositoryArchetype.SERVICE, pin(Ecosystem.NPM, pkg));
    released(
        consumer, Ecosystem.NPM, "@qits/unknown-consumer" + run, "2.0.0", APRIL,
        contains(Ecosystem.NPM, pkg, "1.0.0"));

    AdoptionEvaluator.Journey journey = evaluator.of(library, "2026.999.1");

    assertTrue(journey.packages().isEmpty(), "that version published nothing this service knows of");
    assertEquals("2026.999.1", journey.version());
    assertEquals(
        AdoptionEvaluator.State.PENDING,
        adopter(journey, consumer).state(),
        "the closure is real; nothing in it can be carrying a version that published nothing");
  }
}
