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
 * <h2>Two methods, because a {@code sha} does not always mean the same thing (qits-1733)</h2>
 *
 * <p>There used to be one {@code record} that, whenever a caller handed a {@code sha}, read the
 * tree AT THAT SHA and treated any non-FOUND answer as the git host contradicting itself — thrown
 * rather than swallowed. That was right for {@code bus/ScmEventListener#onReleaseLedger}, the one
 * caller that resolves the tag ITSELF a moment earlier: its {@code sha} is the commit that very
 * resolution just proved exists, so asking about it a second time and getting anything but FOUND
 * is not a tag the host might legitimately lack, it is the race described below, and losing the
 * row to a swallowed WARN would be worse than redelivering the frame. But {@code
 * work/ReleaseLedgerBackfill} ALSO hands a {@code sha} — the gitlink latest row's recorded one —
 * and that sha was never resolved from this release's tag by anybody; it is a value a previous
 * scan wrote down, possibly long before this release existed or after it was deleted. Folding
 * backfill into the same non-FOUND-throws rule made it start throwing for the exact case it exists
 * to handle: a release whose tag the git host has since lost. The split below gives each caller the
 * method that matches what its {@code sha} actually attests to.
 *
 * <ul>
 *   <li>{@link #recordResolved} is {@code bus/ScmEventListener}'s only. Its {@code sha} is a commit
 *       the caller just resolved from the tag, so the tree is read AT THAT COMMIT — no second
 *       resolution of the tag — and any status but FOUND is thrown, NOT_FOUND included: the same
 *       git host contradicting itself within the same request that motivated the fix.
 *   <li>{@link #record} is {@code work/ReleaseLedgerBackfill} and {@code ReleaseLedgerHeal}'s. It
 *       always reads the tree at {@code refs/tags/<version>}, never at a sha the caller hands it —
 *       because for both of those callers the sha, when there is one, was not resolved from this
 *       tag moments ago, so the tag's existence is still an open question rather than something
 *       just proved. A tag the host does not hold is poison there exactly as it always was: a WARN
 *       and a return recording nothing, never a throw.
 * </ul>
 *
 * <h2>The race that made the split necessary in the first place</h2>
 *
 * <p>Before {@code recordResolved} existed, the single {@code record} read the tree at {@code
 * refs/tags/<version>} unconditionally, including on the bus path where the listener had just
 * resolved that very tag to get {@code sha}. That second resolution was its own bug: the git host
 * sometimes answered 404 to the second ask even though the tag plainly existed, the first ask
 * having just proved it. Seen live: {@code 2026.1029.83835 is not a tag the git host holds} logged
 * in the same second as the listener's own successful resolve. The result was poison that was not
 * actually poison — a WARN, a {@code false}, no {@code mt_release} row, the frame acked and
 * nothing ever retrying it — and {@code GET /pins} refusing the release's deployment for ever
 * afterward. {@link #recordResolved} fixes that by reading at the already-resolved commit instead
 * of the tag a second time, which is a full sha and needs no ref resolution at all to race.
 *
 * <p><b>Not parsing is not the same as not being served.</b> A manifest the tree lists and the git
 * host would not serve comes back from {@code pinsAt} as UNREACHABLE (qits-1172, {@code
 * ManifestScanner.UnservedManifest}), so both methods throw for it and the next sweep or redelivery
 * tries again: recording the rest would be a ledger row that says, permanently, that the release
 * declared less than it did — and {@code GET /pins} keeps a deployed release by exactly that row.
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
   * <p><b>One spelling, and two different readers for two different reasons (qits-1733).</b> {@code
   * bus/ScmEventListener} resolves this tag itself and hands the resolved commit to {@link
   * #recordResolved}, which reads the tree AT THAT COMMIT and never at this string again — a second
   * copy of the spelling would be the one that quietly re-asked about a ref it had just resolved.
   * {@code work/ReleaseLedgerBackfill} and {@code ReleaseLedgerHeal} go through {@link #record}
   * instead, which always reads the tree AT THIS TAG: backfill's sha comes from the gitlink latest
   * row, not from resolving this tag a moment ago, so whether the tag still exists is exactly the
   * question that still needs asking there.
   */
  public static final String TAG_PREFIX = "refs/tags/";

  @Inject ManifestScanner manifests;

  @Inject MaintenanceStore store;

  @Inject MaintenanceConfig config;

  /**
   * Records one release from a commit the caller has already resolved from this release's own tag
   * — {@code bus/ScmEventListener#onReleaseLedger}'s only caller, inside a durable claim.
   *
   * <p><b>Reads at {@code sha}, never at the tag again</b> (qits-1733). The caller just resolved
   * {@code refs/tags/<version>} to get {@code sha}, so handing it here means the tree is read with
   * a full commit sha, which needs no ref resolution and so cannot race the tag the way a second
   * {@code refs/tags/<version>} lookup can. Any status other than FOUND is thrown, NOT_FOUND
   * included: the caller already proved the commit exists, so the git host answering otherwise is
   * not a tag it legitimately lacks, it is the same inconsistency described in the class comment,
   * and throwing keeps the frame owed for a retry rather than recording nothing for good.
   *
   * @param project the project the git host addresses the repository under
   * @param repository the CATALOG NAME — {@code mt_release.repository} is compared with a closure
   *     entry directly, so nothing else may be written into it
   * @param version the released version, which is also the tag's name
   * @param sha the commit the caller has already resolved from this tag; never null or blank —
   *     this method exists only for a caller that has one
   * @param occurredAt the publisher's moment, never a clock reading where there is a frame
   * @return whether a row was written
   * @throws IllegalArgumentException when {@code sha} is null or blank — call {@link #record}
   *     instead when there is no resolved commit
   * @throws IllegalStateException when the git host could not be asked (always retryable), or when
   *     the tree at {@code sha} answered anything but FOUND — the caller already proved that commit
   *     exists, so this is thrown rather than swallowed
   */
  public boolean recordResolved(
      String project, String repository, String version, String sha, Instant occurredAt) {
    if (sha == null || sha.isBlank()) {
      throw new IllegalArgumentException(
          "recordResolved needs a commit the caller already resolved; call record(...) when there"
              + " is none");
    }
    if (project == null || repository == null || version == null || version.isBlank()) {
      LOG.debugf(
          "A release of %s at %s under %s names no (project, repository, version) to read a tree at",
          repository, version, project);
      return false;
    }
    ManifestScanner.Pins read = manifests.pinsAt(project, repository, sha);
    if (read.status() == FileLookup.Status.UNREACHABLE
        || read.status() == FileLookup.Status.INVALID) {
      // Retryable, and thrown on purpose: on the bus path the claim rolls back and the release is
      // offered again, which is the only thing that can fill a ledger row afterwards.
      throw new IllegalStateException(
          "the git host could not be asked for " + repository + " " + sha + ": " + read.message());
    }
    if (read.status() != FileLookup.Status.FOUND) {
      // The caller already resolved this exact commit, so anything but FOUND here is the git host
      // contradicting itself within the same release — the qits-1733 race — not a tag it genuinely
      // lacks. Thrown so the frame stays owed and is redelivered, rather than losing the row.
      throw new IllegalStateException(
          "the git host answered " + read.status() + " for " + repository + " " + version + " at "
              + sha + ", although that commit was just resolved");
    }
    return write(repository, version, sha, occurredAt, read);
  }

  /**
   * Records one release: the row, and every INTERNAL pin the tree at its tag declared — {@code
   * work/ReleaseLedgerBackfill} and {@code ReleaseLedgerHeal}'s method.
   *
   * <p><b>Always reads at the tag, never at {@code sha}</b> (qits-1733). Backfill's {@code sha} is
   * the gitlink latest row's recorded commit — a value a previous scan wrote down, not one this
   * call resolved from {@code refs/tags/<version>} moments ago — and heal has no sha at all. So for
   * both, whether the tag still exists is exactly the open question, and reading at {@code sha}
   * instead would skip asking it. A tag the git host does not hold is therefore poison here, not a
   * contradiction: the same question has the same answer for ever, so it is a WARN and a return
   * recording nothing, never a throw.
   *
   * @param project the project the git host addresses the repository under
   * @param repository the CATALOG NAME — {@code mt_release.repository} is compared with a closure
   *     entry directly, so nothing else may be written into it
   * @param version the released version, which is also the tag's name
   * @param sha the commit to record against the row when the tag read is FOUND, such as a gitlink
   *     latest row's recorded commit; null or blank falls back to {@code read.sha()}, the commit
   *     this read resolved the tag to
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
    return write(repository, version, sha, occurredAt, read);
  }

  /**
   * The write both methods share once their own read answered FOUND: filter the declared pins down
   * to INTERNAL and hand the row to the store, which is idempotent on {@code (repository,
   * version)}.
   */
  private boolean write(
      String repository, String version, String sha, Instant occurredAt, ManifestScanner.Pins read) {
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
