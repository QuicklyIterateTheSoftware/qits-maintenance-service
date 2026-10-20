package eu.wohlben.qits.maintenance.latest;

import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore.AdoptionFacts;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.function.Predicate;

/**
 * <b>Which versions of an internal package a REGISTRY READ may adopt: only what its repository has
 * finalized</b> (qits-1472).
 *
 * <p>The bus was one door and this is the other. {@code bus/SoftwareReleaseListener} no longer moves
 * {@code mt_latest} on a publish — an artifact is adopted only once a release request of its
 * repository FINALIZED at or above its version — but the registry poll ({@code
 * ScanService.refreshLatest} → {@link LatestResolver#resolve}) asked qits-artifacts for the highest
 * PUBLISHED version and wrote it, at the end of every INTERNAL scan: the 00:30 cron, a manual scan,
 * the main-push scan and the tag scan every {@code SCMRelease} queues. A publish is in the registry
 * long before its request finalizes, and a request that goes OBSOLETE never does, so gating the
 * listener alone left the door open minutes later. The same holds for the shared-property target
 * ({@code PendingChanges.shared}), which reads {@link LatestResolver#published} rather than {@code
 * mt_latest}; both go through this predicate.
 *
 * <h2>The rule</h2>
 *
 * <p>For an INTERNAL maven, npm or docker package, a version {@code v} is admitted iff
 *
 * <ul>
 *   <li>NO repository has ever announced the package — {@code mt_published_artifact} holds no row
 *       of it. Then there is no repository to gate it on, and today's behaviour stands: the package
 *       may be something nothing on this platform announces at all;
 *   <li>or a repository that announced it has a finalized row {@code (R, V)} with {@code V >= v},
 *       the bus's own adoption rule;
 *   <li>or {@code v} is at or below the package's CURRENT {@code mt_latest} — the backfill, below.
 * </ul>
 *
 * <h2>The backfill: everything at or below the current latest counts as adopted</h2>
 *
 * <p>{@code mt_finalized_release} is empty at deploy, and a gate that only admitted finalized
 * versions would freeze, or — worse, a gated lookup that admits nothing is written as a failed one
 * — clear the latest of every internal package the first time it was announced after the deploy.
 * The two ways out were seeding finalized rows at boot from qits-projects (the {@code
 * ReleaseLedgerBackfill} shape, over {@code ReleaseRequestClient}) or treating what {@code
 * mt_latest} already holds as adopted. <b>This class takes the second</b>, because it is sound
 * without a network call and needs no boot ordering: every value {@code mt_latest} held at deploy
 * is one the platform was already using, and after the deploy the column only moves through
 * adoption or through this gate, so "at or below the current latest" can never admit a version that
 * was not adopted already. It also makes a later deploy, an emptied table or a repository whose
 * finalizations predate the table converge on the same answer.
 *
 * <p>The gate is read only for a lookup that SUCCEEDED, so a registry outage costs no query here.
 */
@ApplicationScoped
public class AdoptionGate {

  @Inject MaintenanceStore store;

  /**
   * The versions of one package this lookup may adopt. One store read for an INTERNAL registry
   * package; nothing at all otherwise — an external dependency and a gitlink are not this rule's.
   */
  public Predicate<String> admits(Ecosystem ecosystem, PinKind kind, String name) {
    if (kind != PinKind.INTERNAL || ecosystem == Ecosystem.GITLINK) {
      return version -> true;
    }
    AdoptionFacts facts = store.adoptionFacts(ecosystem, name);
    return version -> admits(ecosystem, facts, version);
  }

  /** The rule itself, over facts already read. See the class javadoc. */
  public static boolean admits(Ecosystem ecosystem, AdoptionFacts facts, String version) {
    if (facts.repositories().isEmpty()) {
      return true;
    }
    if (version == null) {
      return false;
    }
    String candidate = version.trim();
    if (facts.latest() != null && !VersionOrder.newer(ecosystem, facts.latest(), candidate)) {
      return true;
    }
    return MaintenanceStore.atOrBelowAny(ecosystem, candidate, facts.finalized());
  }
}
