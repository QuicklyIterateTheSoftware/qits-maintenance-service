package eu.wohlben.qits.maintenance.adoption;

import eu.wohlben.qits.maintenance.config.MaintenanceConfig;
import eu.wohlben.qits.maintenance.githost.FileLookup;
import eu.wohlben.qits.maintenance.manifest.ManifestScanner;
import eu.wohlben.qits.maintenance.manifest.ParsedPin;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * <b>WHAT A RELEASE DECLARED, recorded once at the moment it is announced.</b>
 *
 * <p>An adoption needs evidence, and until this class there was exactly one kind of it: a component
 * row in the consumer's own released bill of materials. That rule is right and it is not enough.
 * Measured live on 2026-09-08 against {@code qits-ui-components-jslib 2026.906.164412} — fifteen
 * frontends at depth 1, fifteen services at depth 2, every one of them reported PENDING and every
 * one of them in fact carrying the release for weeks. A frontend publishes no registry artifact at
 * all (its release is a git tag, consumed by the embedding service's gitlink bump), and a service's
 * docker-image SBOM holds maven components only — 239 of them and no npm ones — because the
 * compiled Angular dist carries no npm metadata. Neither hop had a document that could ever name
 * the coordinate.
 *
 * <h2>Why a pin is allowed to be evidence here</h2>
 *
 * <p>{@link AdoptionEvaluator} refuses a pin, and it is right to: a pin read at {@code main} is a
 * fact about somebody's WORKING TREE. Nothing polls it, it moves under you, and it is revertible,
 * so a verdict computed from one would flicker. <b>A pin read at {@code refs/tags/<version>} is a
 * different fact.</b> A tag is immutable and it is tied to the consumer's own released version —
 * which is the very thing an adoption is reported as. So the philosophy already written into that
 * class, that the evidence is about a RELEASE rather than a working tree, is satisfied by the tag,
 * and it is obtainable for a repository that publishes nothing to any registry.
 *
 * <h2>What is kept, and what is dropped</h2>
 *
 * <p><b>INTERNAL only</b>, by {@link MaintenanceConfig#kindOf(ParsedPin)}. EXTERNAL is somebody
 * else's package and nothing of ours releasing it makes anybody downstream; REACTOR is the
 * repository's own artifact, whose version no line anywhere holds; UNRESOLVED never became a
 * version at all. <b>GITLINK survives that filter without a special case</b>, because {@code
 * kindOf} hardcodes it INTERNAL — a submodule is a repository on this platform's own git host and
 * nothing else can be one — and it is the half that matters most: it is the whole frontend→service
 * hop, and its {@code version} is the embedded commit sha rather than a version.
 *
 * <h2>Failure, and why the split is the listener's</h2>
 *
 * <p>The one caller on the bus path is {@code bus/ScmEventListener}, inside a durable claim, so
 * this holds the same three-way split that method does:
 *
 * <ul>
 *   <li>a git host that cannot be ASKED is retryable and is <b>thrown</b> — the frame stays owed and
 *       the next sweep offers it again, which is the only thing that recovers a ledger row nothing
 *       else ever writes;
 *   <li>a tag the host does not HOLD is poison — the same question has the same answer for ever —
 *       so it is a WARN and a return recording nothing;
 *   <li>a manifest that would not parse records <b>whatever parsed</b>. The parsers already drop
 *       what they cannot read (see {@code ManifestScanner}), and a release whose pom listing was
 *       half-readable declared the other half all the same.
 * </ul>
 *
 * <p><b>Not parsing is not the same as not being served.</b> A manifest the tree lists and the git
 * host would not serve comes back from {@code pinsAt} as UNREACHABLE (qits-1172, {@code
 * ManifestScanner.UnservedManifest}), so it lands in the first bullet and is retried: recording
 * the rest would be a ledger row that says, permanently, that the release declared less than it
 * did — and {@code GET /pins} keeps a deployed release by exactly that row.
 *
 * <p><b>The write is idempotent and that is not incidental</b>: a durable redelivery and a backfill
 * re-run both land on {@code (repository, version)}, and {@link MaintenanceStore#recordRelease}
 * rewrites the row and its pins rather than adding to them.
 */
@ApplicationScoped
public class ReleaseLedger {

  private static final Logger LOG = Logger.getLogger(ReleaseLedger.class);

  /**
   * A release's tag, spelled in full.
   *
   * <p>Fully qualified rather than bare: git's own ref search would try {@code refs/<version>} and
   * a branch of that name before it reached the tag, and a release version is exactly the kind of
   * string somebody once made a branch out of.
   *
   * <p><b>One spelling, two readers.</b> {@code bus/ScmEventListener} resolves a release's commit
   * with it and this reads the released tree at it; a second copy of the string would be the one
   * that quietly asked about a ref nobody has.
   */
  public static final String TAG_PREFIX = "refs/tags/";

  @Inject ManifestScanner manifests;

  @Inject MaintenanceStore store;

  @Inject MaintenanceConfig config;

  /**
   * Records one release: the row, and every INTERNAL pin the tree at its tag declared.
   *
   * @param project the project the git host addresses the repository under
   * @param repository the CATALOG NAME — {@code mt_release.repository} is compared with a closure
   *     entry directly, so nothing else may be written into it
   * @param version the released version, which is also the tag's name
   * @param sha the commit that tag resolves to, where the caller has already resolved it; null
   *     falls back to the commit this read resolved, which is the same commit
   * @param occurredAt the publisher's moment, never a clock reading where there is a frame
   * @return whether a row was written
   * @throws IllegalStateException when the git host could not be asked — retryable, on purpose
   */
  public boolean record(
      String project, String repository, String version, String sha, Instant occurredAt) {
    if (project == null || repository == null || version == null || version.isBlank()) {
      LOG.debugf(
          "A release of %s at %s under %s names no (project, repository, version) to read a tree at",
          repository, version, project);
      return false;
    }
    String revision = TAG_PREFIX + version;
    ManifestScanner.Pins read = manifests.pinsAt(project, repository, revision);
    if (read.status() == FileLookup.Status.UNREACHABLE
        || read.status() == FileLookup.Status.INVALID) {
      // Retryable, and thrown on purpose: on the bus path the claim rolls back and the release is
      // offered again, which is the only thing that can fill a ledger row afterwards.
      throw new IllegalStateException(
          "the git host could not be asked for " + repository + " " + revision + ": "
              + read.message());
    }
    if (read.status() != FileLookup.Status.FOUND) {
      LOG.warnf(
          "%s %s is not a tag the git host holds; no release pins are recorded for it",
          repository, version);
      return false;
    }

    List<MaintenanceStore.ReleasePin> declared = new ArrayList<>();
    for (ParsedPin pin : read.pins()) {
      if (config.kindOf(pin) != PinKind.INTERNAL) {
        // EXTERNAL, REACTOR and UNRESOLVED, each for its own reason — see the class comment. A
        // GITLINK is INTERNAL by construction and is kept, its version being the embedded commit.
        continue;
      }
      declared.add(
          new MaintenanceStore.ReleasePin(pin.ecosystem(), pin.name(), pin.version()));
    }

    UUID id =
        store.recordRelease(
            repository, version, sha == null || sha.isBlank() ? read.sha() : sha, occurredAt,
            declared);
    LOG.debugf(
        "Recorded the release %s %s (%s) with %d internal pin(s) as %s",
        repository, version, read.sha(), declared.size(), id);
    return true;
  }
}
