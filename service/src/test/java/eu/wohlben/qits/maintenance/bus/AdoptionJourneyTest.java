package eu.wohlben.qits.maintenance.bus;

import static eu.wohlben.qits.maintenance.bus.ForeignEventContractTest.frame;
import static eu.wohlben.qits.maintenance.bus.ForeignEventContractTest.softwareReleasePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.bootstrap.graph.Ecosystem;
import eu.wohlben.qits.bootstrap.graph.manifest.ParsedPin;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.control.Adoption;
import eu.wohlben.qits.maintenance.dto.AdoptionJourneyDto;
import eu.wohlben.qits.maintenance.dto.DownstreamDto;
import eu.wohlben.qits.maintenance.model.GroupSource;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.model.RepositoryArchetype;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * <b>ONE JOURNEY, END TO END, ACROSS THE HOP THE RELEASE TRAINS COULD NOT MAKE: a library releases,
 * a frontend takes it, and the SERVICE THAT CARRIES THAT FRONTEND AS A SUBMODULE takes it too.</b>
 *
 * <p>The retired trains stopped at the frontend, every time, and it was a modelling failure rather
 * than a bug: membership was derived ONCE, at the release, by asking who pins the released
 * coordinate — one hop — and a submodule is not a released coordinate at all. This class is the
 * acceptance gate for the replacement, and the assertion that matters most is an EXACT one: the
 * closure of the library is {@code [frontend@1, service@2 via frontend]} and nothing else.
 *
 * <p><b>Driven through the seams a deployment uses.</b> Real {@code SoftwareRelease} frames into
 * {@link SoftwareReleaseListener}, the real work queue, real SBOM documents fetched through {@link
 * FakePeers}, and a real database. Nothing here calls a store write to fake a release: the whole
 * value of a journey test is that every seam between the frame and the answer is in it.
 *
 * <p><b>What it does NOT drive is a second listener, and that is the subtraction.</b> There used to
 * be two consumers of every {@code SoftwareRelease} — one to move {@code mt_latest} and open the
 * SBOM outbox row, a second to open the station — and the second one is gone with the tables. There
 * is one consumer of a release again, and the closure is a query.
 *
 * <p>The wrapper is in the estate on purpose. It gitlink-pins all three repositories, so an
 * implementation that did not exclude the {@code PROJECT} archetype would put it on every assertion
 * below — and then expand it, which reaches the whole platform in one hop.
 */
@QuarkusTest
class AdoptionJourneyTest {

  /** The library at the bottom of the chain, and the package it publishes. */
  private static final String LIBRARY = "qits-ui-components-jslib";
  private static final String LIBRARY_ID = "r-ui-components";
  private static final String LIBRARY_PACKAGE = "@qits/ui-components";
  private static final String LIBRARY_VERSION = "2026.905.1";

  /** The frontend in the middle: it PINS the library's package and publishes a bundle. */
  private static final String FRONTEND = "qits-ci-frontend";
  private static final String FRONTEND_ID = "r-ci-frontend";
  private static final String FRONTEND_PACKAGE = "@qits/ci-spa";
  private static final String FRONTEND_VERSION = "2026.905.2";

  /** And the service, which carries the frontend as its {@code webui} SUBMODULE. */
  private static final String SERVICE = "qits-ci-service";
  private static final String SERVICE_ID = "r-ci-service";
  private static final String SERVICE_PACKAGE = "eu.wohlben.qits:qits-ci";
  private static final String SERVICE_VERSION = "2026.905.3";

  /** The estate rather than a member of it — it submodules all three. */
  private static final String WRAPPER = "qits-qits";

  @Inject SoftwareReleaseListener releases;

  @Inject MaintenanceStore store;

  @Inject Adoption adoption;

  @Inject FakePeers peers;

  @Inject WorkQueue queue;

  @Inject InventoryReset reset;

  @BeforeEach
  void anEstateOfThreeRepositoriesAndTheWrapperOverThem() {
    reset.clear();
    peers.reset();
    scanned(LIBRARY, LIBRARY_ID, RepositoryArchetype.LIBRARY);
    scanned(FRONTEND, FRONTEND_ID, RepositoryArchetype.FRONTEND, npm(LIBRARY_PACKAGE, "2026.904.9"));
    // THE GITLINK HOP. The service does not pin the frontend's package — it carries the frontend's
    // REPOSITORY at service/src/main/webui, which is what GitmodulesParser records.
    scanned(SERVICE, SERVICE_ID, RepositoryArchetype.SERVICE, gitlink(FRONTEND));
    scanned(
        WRAPPER, "r-qits", RepositoryArchetype.PROJECT,
        gitlink(LIBRARY), gitlink(FRONTEND), gitlink(SERVICE));
  }

  private void scanned(
      String repository, String catalogId, RepositoryArchetype archetype, ParsedPin... pins) {
    store.replaceInventory(
        repository,
        "qits",
        catalogId,
        archetype.name(),
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

  private static ParsedPin npm(String name, String version) {
    return ParsedPin.of(Ecosystem.NPM, "package.json", name, version, null, "dependency:" + name);
  }

  private static ParsedPin gitlink(String repository) {
    return ParsedPin.of(
        Ecosystem.GITLINK,
        ".gitmodules",
        repository,
        "c0ffee11d00d2233445566778899aabbccddeeff",
        null,
        "gitlink:service/src/main/webui");
  }

  /** The document qits-artifacts answers for one release, naming the one thing it contains. */
  private void sbomOf(
      String packageType,
      String name,
      String version,
      String containsPurl,
      String containsName,
      String containsVersion) {
    String document =
        """
        {"bomFormat":"CycloneDX","specVersion":"1.5","version":1,
         "metadata":{"component":{"bom-ref":"self","type":"library",
                                  "name":"%s","version":"%s"}},
         "components":[{"bom-ref":"c-1","type":"library","name":"%s","version":"%s",
                        "purl":"%s"}],
         "dependencies":[{"ref":"self","dependsOn":["c-1"]},{"ref":"c-1","dependsOn":[]}]}
        """
            .formatted(name, version, containsName, containsVersion, containsPurl);
    peers.answer(
        PeerTarget.ARTIFACTS_SBOM,
        "/artifacts/sboms/" + packageType + "/" + name + "/-/" + version,
        FakePeers.Scripted.ok(document));
  }

  /**
   * One release, through the one consumer of {@code SoftwareRelease}.
   *
   * <p>The barrier is not tidiness: the document is fetched on the single worker thread, so "the
   * SBOM has been read" is only a fact once the queue has drained, and a test that did not wait
   * would be asserting on whichever half of the race it happened to catch.
   */
  private void released(String repository, String packageType, String packageName, String version) {
    releases.onFrame(
        frame("SoftwareRelease", softwareReleasePayload(repository, packageType, packageName, version)));
    assertTrue(queue.awaitIdle(Duration.ofSeconds(30)), "the ingest queue drained");
  }

  private void theDocumentsOfThisJourney() {
    // The frontend's bundle names the library's package; the service's release names the bundle.
    // The second is the whole evidence story across a gitlink edge: the EDGE is the submodule, but
    // what proves the adoption is still a registry coordinate the embedder's document names.
    sbomOf(
        "npm",
        FRONTEND_PACKAGE,
        FRONTEND_VERSION,
        "pkg:npm/%40qits%2Fui-components@" + LIBRARY_VERSION,
        LIBRARY_PACKAGE,
        LIBRARY_VERSION);
    sbomOf(
        "maven",
        SERVICE_PACKAGE,
        SERVICE_VERSION,
        "pkg:npm/%40qits%2Fci-spa@" + FRONTEND_VERSION,
        FRONTEND_PACKAGE,
        FRONTEND_VERSION);
  }

  private static AdoptionJourneyDto.AdopterDto adopter(
      AdoptionJourneyDto journey, String repository) {
    return journey.adopters().stream()
        .filter(row -> repository.equals(row.repository()))
        .findFirst()
        .orElseThrow(() -> new AssertionError(repository + " is not in the journey"));
  }

  // --- the closure ------------------------------------------------------------------------------

  /**
   * <b>THE BUG, WRITTEN AS AN EXACT ASSERTION.</b> Two rows and no others: the frontend at one hop
   * because it pins the package, the service at two because it submodules the frontend. The wrapper
   * pins all three by gitlink and is on none of it.
   */
  @Test
  @Timeout(180)
  void theClosureOfTheLibraryReachesTheServiceBehindTheFrontendAndNeverTheWrapper() {
    released(LIBRARY, "npm", LIBRARY_PACKAGE, LIBRARY_VERSION);

    DownstreamDto closure = adoption.downstream(LIBRARY);

    assertEquals(LIBRARY, closure.repository());
    assertEquals(LIBRARY_ID, closure.catalogId());
    assertEquals(
        List.of(FRONTEND, SERVICE),
        closure.downstream().stream().map(DownstreamDto.EntryDto::repository).toList(),
        "exactly these two, upstream first — the trains answered only the first");

    DownstreamDto.EntryDto frontend = closure.downstream().get(0);
    assertEquals(1, frontend.depth());
    assertEquals(List.of(LIBRARY), frontend.via());
    assertEquals(FRONTEND_ID, frontend.catalogId());
    assertEquals("FRONTEND", frontend.archetype());

    DownstreamDto.EntryDto service = closure.downstream().get(1);
    assertEquals(2, service.depth(), "reached through the gitlink pin, which is the whole point");
    assertEquals(List.of(FRONTEND), service.via());
    assertEquals(SERVICE_ID, service.catalogId());
    assertEquals("SERVICE", service.archetype());

    assertFalse(
        closure.downstream().stream()
            .anyMatch(entry -> WRAPPER.equals(entry.repository())),
        "the wrapper gitlink-pins every one of these and is the estate rather than a member of it");
  }

  /**
   * <b>The caller may be holding qits-projects' spelling, and usually is.</b> Its release-request
   * adapter addresses this closure by the repository row id it already has — which IS this
   * inventory's {@code catalog_id} — so an answer that needed the name would be an announce path
   * that silently carried nothing.
   */
  @Test
  @Timeout(180)
  void theClosureResolvesTheCatalogIdSpellingAndAnswersInNames() {
    released(LIBRARY, "npm", LIBRARY_PACKAGE, LIBRARY_VERSION);

    DownstreamDto byId = adoption.downstream(LIBRARY_ID);

    assertEquals(LIBRARY, byId.repository(), "asked by id, answered by name");
    assertEquals(
        List.of(FRONTEND, SERVICE),
        byId.downstream().stream().map(DownstreamDto.EntryDto::repository).toList());
  }

  // --- the journey ------------------------------------------------------------------------------

  /**
   * <b>THE WHOLE JOURNEY.</b> Three releases, three frames, two documents — and at the end of it a
   * question that has no answer anywhere else on this platform is one document: the library's
   * 2026.905.1 reached the estate, through which releases, and when.
   */
  @Test
  @Timeout(180)
  void aLibraryReleaseTravelsThroughTheFrontendIntoTheServiceAndTheJourneySaysSo() {
    theDocumentsOfThisJourney();

    // 1. THE LIBRARY RELEASES. Its own document is a 404, which is the ordinary answer from that
    //    route and costs nothing here: what the closure needs off the artifact row is the
    //    coordinate, and the row is written whether the document reads or not.
    released(LIBRARY, "npm", LIBRARY_PACKAGE, LIBRARY_VERSION);

    AdoptionJourneyDto owed = adoption.byRelease(LIBRARY, LIBRARY_VERSION);
    assertEquals(LIBRARY_ID, owed.catalogId());
    assertEquals(1, owed.packages().size());
    assertEquals("npm", owed.packages().get(0).ecosystem());
    assertEquals(LIBRARY_PACKAGE, owed.packages().get(0).name());
    assertEquals("PENDING", adopter(owed, FRONTEND).state());
    assertEquals("PENDING", adopter(owed, SERVICE).state());

    // 2. THE FRONTEND RELEASES with it. Its own release version is what is reported, never the
    //    library version it took: that value is half of the address of the release request the
    //    adoption opened.
    released(FRONTEND, "npm", FRONTEND_PACKAGE, FRONTEND_VERSION);

    AdoptionJourneyDto halfway = adoption.byRelease(LIBRARY, LIBRARY_VERSION);
    AdoptionJourneyDto.AdopterDto adoptedFrontend = adopter(halfway, FRONTEND);
    assertEquals("ADOPTED", adoptedFrontend.state());
    assertEquals(FRONTEND_VERSION, adoptedFrontend.adoptedVersion());
    assertNotNull(adoptedFrontend.adoptedAt());
    assertEquals(FRONTEND_ID, adoptedFrontend.catalogId());
    assertEquals(RepositoryStatus.OK.name(), adoptedFrontend.repositoryStatus());
    // AND THE SERVICE IS STILL PENDING, honestly: it carries a frontend that has shipped the
    // library, and it has not shipped that frontend.
    assertEquals("PENDING", adopter(halfway, SERVICE).state());
    assertNull(adopter(halfway, SERVICE).adoptedVersion());

    // 3. THE SERVICE RELEASES, and its bill of materials names the frontend's bundle at the version
    //    that carries the library. That is the second hop closing.
    released(SERVICE, "maven", SERVICE_PACKAGE, SERVICE_VERSION);

    AdoptionJourneyDto arrived = adoption.byRelease(LIBRARY, LIBRARY_VERSION);
    AdoptionJourneyDto.AdopterDto adoptedService = adopter(arrived, SERVICE);
    assertEquals("ADOPTED", adoptedService.state());
    assertEquals(SERVICE_VERSION, adoptedService.adoptedVersion());
    assertEquals(2, adoptedService.depth());
    assertEquals(List.of(FRONTEND), adoptedService.via());
    assertNotNull(adoptedService.adoptedAt());

    assertFalse(
        arrived.adopters().stream().anyMatch(row -> WRAPPER.equals(row.repository())),
        "the wrapper is not on a journey either");
  }

  /**
   * <b>A consumer that SKIPPED STRAIGHT PAST the version has adopted it too.</b> The comparison is
   * inclusive on purpose: what a person wants to know is whether the estate is still carrying the
   * old copy, and a frontend on a later release is not.
   */
  @Test
  @Timeout(180)
  void aConsumerThatSkippedPastTheVersionHasStillAdoptedIt() {
    // The frontend's document names a LATER library version than the one being asked about.
    sbomOf(
        "npm",
        FRONTEND_PACKAGE,
        FRONTEND_VERSION,
        "pkg:npm/%40qits%2Fui-components@2026.905.9",
        LIBRARY_PACKAGE,
        "2026.905.9");

    released(LIBRARY, "npm", LIBRARY_PACKAGE, LIBRARY_VERSION);
    released(FRONTEND, "npm", FRONTEND_PACKAGE, FRONTEND_VERSION);

    AdoptionJourneyDto journey = adoption.byRelease(LIBRARY, LIBRARY_VERSION);

    assertEquals("ADOPTED", adopter(journey, FRONTEND).state());
    assertEquals(FRONTEND_VERSION, adopter(journey, FRONTEND).adoptedVersion());
  }

  /**
   * <b>And the same question asked twice answers the same thing.</b> The bus is at-least-once and a
   * catch-up re-offers whatever was in flight, so every frame above can arrive twice — which is
   * cheaper to be sure of than it used to be, because nothing is written down: the second pass
   * writes the same rows and the answer is derived from them either way.
   */
  @Test
  @Timeout(180)
  void aRedeliveryOfEveryFrameChangesNothingAboutTheAnswer() {
    theDocumentsOfThisJourney();

    released(LIBRARY, "npm", LIBRARY_PACKAGE, LIBRARY_VERSION);
    released(FRONTEND, "npm", FRONTEND_PACKAGE, FRONTEND_VERSION);
    released(SERVICE, "maven", SERVICE_PACKAGE, SERVICE_VERSION);

    AdoptionJourneyDto first = adoption.byRelease(LIBRARY, LIBRARY_VERSION);

    released(LIBRARY, "npm", LIBRARY_PACKAGE, LIBRARY_VERSION);
    released(FRONTEND, "npm", FRONTEND_PACKAGE, FRONTEND_VERSION);
    released(SERVICE, "maven", SERVICE_PACKAGE, SERVICE_VERSION);

    assertEquals(first, adoption.byRelease(LIBRARY, LIBRARY_VERSION), "byte for byte the same");
  }
}
