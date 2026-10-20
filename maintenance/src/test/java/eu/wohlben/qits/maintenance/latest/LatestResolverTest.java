package eu.wohlben.qits.maintenance.latest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.peer.PeerCall;
import eu.wohlben.qits.maintenance.peer.PeerClient;
import eu.wohlben.qits.maintenance.peer.PeerExchange;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * THE FAILURE THAT ENDED THE FIRST LIVE SCAN, and the rule that came out of it.
 *
 * <p>One dependency of 49 repositories carried an unresolved expression, it reached
 * {@code URI.create}, the {@code IllegalArgumentException} came out of the resolver, past the
 * scanner, past the run, and the scan row stayed RUNNING for ever. A lookup is one question about
 * one dependency: it may not be able to end a scan.
 *
 * <p>No Quarkus here — the resolver's own error handling is a property of the class, and a stub
 * client is all it takes to prove it.
 */
class LatestResolverTest {

  /** A client that answers however a test needs it to, at no port. */
  private static class Stub extends PeerClient {

    private final RuntimeException thrown;

    Stub(RuntimeException thrown) {
      this.thrown = thrown;
    }

    @Override
    public String url(PeerTarget target, String path) {
      return "http://stub" + path;
    }

    @Override
    public PeerExchange get(PeerTarget target, String path) {
      if (thrown != null) {
        throw thrown;
      }
      return new PeerExchange(
          new PeerCall("GET", url(target, path), null),
          new eu.wohlben.qits.maintenance.peer.PeerAnswer(
              503, "", null, java.util.Map.of(), null));
    }
  }

  private static LatestResolver resolver(PeerClient peers) {
    return resolver(peers, OPEN);
  }

  private static LatestResolver resolver(PeerClient peers, AdoptionGate gate) {
    LatestResolver resolver = new LatestResolver();
    resolver.peers = peers;
    resolver.gate = gate;
    return resolver;
  }

  /** A gate that admits everything: what a package nothing ever announced gets. */
  private static final AdoptionGate OPEN = gate(java.util.Map.of());

  /**
   * A gate over facts a test states, per package name, applying the real rule ({@link
   * AdoptionGate#admits(Ecosystem, MaintenanceStore.AdoptionFacts, String)}).
   */
  private static AdoptionGate gate(java.util.Map<String, MaintenanceStore.AdoptionFacts> facts) {
    return new AdoptionGate() {
      @Override
      public java.util.function.Predicate<String> admits(
          Ecosystem ecosystem, PinKind kind, String name) {
        MaintenanceStore.AdoptionFacts known = facts.get(name);
        if (kind != PinKind.INTERNAL || known == null) {
          return version -> true;
        }
        return version -> AdoptionGate.admits(ecosystem, known, version);
      }
    };
  }

  /** A registry serving one {@code maven-metadata.xml} per path, at no port. */
  private static final class Metadata extends PeerClient {

    private final java.util.Map<String, List<String>> versions;

    Metadata(java.util.Map<String, List<String>> versions) {
      this.versions = versions;
    }

    @Override
    public String url(PeerTarget target, String path) {
      return "http://stub" + path;
    }

    @Override
    public PeerExchange get(PeerTarget target, String path) {
      List<String> listed = versions.get(path);
      String body =
          listed == null
              ? ""
              : "<metadata><versioning><versions>"
                  + String.join(
                      "", listed.stream().map(v -> "<version>" + v + "</version>").toList())
                  + "</versions></versioning></metadata>";
      return new PeerExchange(
          new PeerCall("GET", url(target, path), null),
          new eu.wohlben.qits.maintenance.peer.PeerAnswer(
              listed == null ? 404 : 200, body, null, java.util.Map.of(), null));
    }
  }

  @Test
  void aNameThatIsStillAnExpressionIsAnErrorRowAndNeverARequest() {
    // ${project.groupId}:qits-artifacts-artifacts — the exact shape that died live. Nothing is
    // asked, so there is no url to malform.
    Stub peers = new Stub(new IllegalStateException("no request may be made for an expression"));
    LatestLookup lookup =
        resolver(peers)
            .resolve(Ecosystem.MAVEN, PinKind.INTERNAL, "${project.groupId}:qits-artifacts-artifacts");
    assertFalse(lookup.ok());
    assertNull(lookup.latest());
    assertTrue(lookup.error().contains("is not a name this service can address"));
  }

  @Test
  void everyCharacterARealNameCarriesIsStillAllowed() {
    // A maven colon, an npm scope's at-sign and slash, an image path's slashes, a registry host's
    // port. Refusing any of these would make the guard the new outage.
    LatestResolver resolver = resolver(new Stub(null));
    for (String name :
        new String[] {
          "eu.wohlben.qits:qits-eventstream",
          "@angular/core",
          "qits/build-images/maven-base",
          "mirror.dev.localhost:8080/quay/quarkus/ubi9-quarkus-mandrel-builder-image"
        }) {
      LatestLookup lookup = resolver.resolve(Ecosystem.MAVEN, PinKind.INTERNAL, name);
      assertFalse(
          lookup.error().contains("is not a name this service can address"),
          name + " must be addressable");
    }
  }

  @Test
  void anExceptionInOneLookupBecomesThatDependencysErrorAndNothingMore() {
    LatestLookup lookup =
        resolver(new Stub(new IllegalArgumentException("Illegal character in path at index 58")))
            .resolve(Ecosystem.MAVEN, PinKind.EXTERNAL, "io.quarkus:quarkus-arc");
    assertFalse(lookup.ok());
    assertTrue(lookup.error().startsWith("the lookup failed: "));
    assertTrue(lookup.error().contains("Illegal character"));
  }

  @Test
  void aNonTwoHundredIsTheDependencysErrorToo() {
    LatestLookup lookup =
        resolver(new Stub(null)).resolve(Ecosystem.MAVEN, PinKind.EXTERNAL, "g:a");
    assertFalse(lookup.ok());
    assertEquals("HTTP 503", lookup.error());
  }

  @Test
  void theRepositorysOwnArtifactsAreNeverLookedUpAtAll() {
    LatestResolver resolver = resolver(new Stub(null));
    assertFalse(resolver.resolvable(Ecosystem.MAVEN, PinKind.REACTOR));
    assertFalse(resolver.resolvable(Ecosystem.MAVEN, PinKind.UNRESOLVED));
    assertTrue(resolver.resolvable(Ecosystem.MAVEN, PinKind.INTERNAL));
    // v1 orders no external base image tags.
    assertFalse(resolver.resolvable(Ecosystem.DOCKER, PinKind.EXTERNAL));
    assertTrue(resolver.resolvable(Ecosystem.DOCKER, PinKind.INTERNAL));
  }

  // --- the finalization gate (qits-1472) -----------------------------------------------------------

  private static final String X = "eu.wohlben.qits:qits-x";

  private static final String X_METADATA = "/eu/wohlben/qits/qits-x/maven-metadata.xml";

  /** qits-x's repository announced it and finalized only 2026.901.1; nothing is in mt_latest. */
  private static final MaintenanceStore.AdoptionFacts X_FINALIZED_AT_901 =
      new MaintenanceStore.AdoptionFacts(Set.of("r-x"), List.of("2026.901.1"), null);

  /**
   * <b>THE POLL'S DOOR.</b> The registry holds 2026.902.1 — published, its request RELEASED and not
   * finalized — and the latest the poll writes is the highest version the repository FINALIZED at
   * or above, never that one.
   */
  @Test
  void aRegistryVersionItsRepositoryHasNotFinalizedIsNeverTheLatest() {
    LatestResolver resolver =
        resolver(
            new Metadata(java.util.Map.of(X_METADATA, List.of("2026.900.1", "2026.901.1", "2026.902.1"))),
            gate(java.util.Map.of(X, X_FINALIZED_AT_901)));

    LatestLookup lookup = resolver.resolve(Ecosystem.MAVEN, PinKind.INTERNAL, X);

    assertTrue(lookup.ok(), lookup.toString());
    assertEquals("2026.901.1", lookup.latest());
  }

  /** A package nothing ever announced has no repository to gate on, and is answered as before. */
  @Test
  void aPackageNothingAnnouncedKeepsTheUngatedAnswer() {
    LatestResolver resolver =
        resolver(
            new Metadata(java.util.Map.of(X_METADATA, List.of("2026.901.1", "2026.902.1"))), OPEN);

    assertEquals("2026.902.1", resolver.resolve(Ecosystem.MAVEN, PinKind.INTERNAL, X).latest());
  }

  /**
   * THE BACKFILL. Nothing finalized is known yet — the table is new — and the column already holds
   * 2026.902.1, adopted before the gate existed: that and everything below it stays admissible, so
   * the deploy freezes nothing.
   */
  @Test
  void everythingAtOrBelowTheCurrentLatestStaysAdmissible() {
    LatestResolver resolver =
        resolver(
            new Metadata(
                java.util.Map.of(X_METADATA, List.of("2026.901.1", "2026.902.1", "2026.903.1"))),
            gate(
                java.util.Map.of(
                    X, new MaintenanceStore.AdoptionFacts(Set.of("r-x"), List.of(), "2026.902.1"))));

    assertEquals("2026.902.1", resolver.resolve(Ecosystem.MAVEN, PinKind.INTERNAL, X).latest());
  }

  /** Every listed version unfinalized: an answer with a sentence, never the unfinalized version. */
  @Test
  void aListingWithNothingFinalizedSaysSo() {
    LatestResolver resolver =
        resolver(
            new Metadata(java.util.Map.of(X_METADATA, List.of("2026.902.1"))),
            gate(java.util.Map.of(X, X_FINALIZED_AT_901)));

    LatestLookup lookup = resolver.resolve(Ecosystem.MAVEN, PinKind.INTERNAL, X);

    assertFalse(lookup.ok());
    assertNull(lookup.latest());
    assertTrue(lookup.error().contains("finalized"), lookup.error());
  }

  /**
   * <b>THE SHARED-PROPERTY DOOR</b> ({@code PendingChanges.shared}, qits-1206). Its target comes from
   * {@link LatestResolver#published}, not from {@code mt_latest}, so it is gated there: both
   * readers are published at 2026.902.1, only 2026.901.1 is finalized, and the property moves to
   * 2026.901.1.
   */
  @Test
  void aSharedPropertyIsNeverTargetedAtAnUnfinalizedVersion() {
    String y = "eu.wohlben.qits:qits-y";
    LatestResolver resolver =
        resolver(
            new Metadata(
                java.util.Map.of(
                    X_METADATA, List.of("2026.900.1", "2026.901.1", "2026.902.1"),
                    "/eu/wohlben/qits/qits-y/maven-metadata.xml",
                        List.of("2026.900.1", "2026.901.1", "2026.902.1"))),
            gate(
                java.util.Map.of(
                    X, X_FINALIZED_AT_901,
                    y, new MaintenanceStore.AdoptionFacts(Set.of("r-x"), List.of("2026.901.1"), null))));
    List<eu.wohlben.qits.maintenance.entity.MtPin> readers =
        List.of(reader(X, "2026.900.1"), reader(y, "2026.900.1"));
    // The latest rows say 2026.902.1 — say, written before this gate existed — so both readers are
    // behind and the shared rule asks the registry.
    java.util.Map<String, eu.wohlben.qits.maintenance.entity.MtLatest> latest =
        eu.wohlben.qits.maintenance.pending.PendingChanges.index(
            List.of(latestRow(X, "2026.902.1"), latestRow(y, "2026.902.1")));

    var resolution =
        eu.wohlben.qits.maintenance.pending.PendingChanges.resolve(
            readers, latest, name -> false, resolver::published);

    assertEquals(1, resolution.planned().size(), resolution.toString());
    assertEquals("2026.901.1", resolution.planned().get(0).change().to());
  }

  private static eu.wohlben.qits.maintenance.entity.MtPin reader(String name, String version) {
    var pin = new eu.wohlben.qits.maintenance.entity.MtPin();
    pin.repository = "qits-consumer";
    pin.manifestPath = "pom.xml";
    pin.ecosystem = Ecosystem.MAVEN.wireName();
    pin.name = name;
    pin.version = version;
    pin.kind = PinKind.INTERNAL.name();
    pin.location = "property:qits.x.version";
    return pin;
  }

  private static eu.wohlben.qits.maintenance.entity.MtLatest latestRow(String name, String version) {
    var row = new eu.wohlben.qits.maintenance.entity.MtLatest();
    row.ecosystem = Ecosystem.MAVEN.wireName();
    row.name = name;
    row.latest = version;
    row.checkedAt = java.time.Instant.now();
    return row;
  }
}
