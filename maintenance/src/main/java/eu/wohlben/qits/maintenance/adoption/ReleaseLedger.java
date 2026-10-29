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
 * <h2>Failure, and why the split now follows who resolved the commit (qits-1733)</h2>
 *
 * <p>The one caller on the bus path is {@code bus/ScmEventListener}, inside a durable claim, so
 * this used to hold the same three-way split that method does, reading the tree at {@code
 * refs/tags/<version>} regardless of what the caller already knew. That second resolution was the
 * bug: the listener resolves the tag itself to get {@code sha}, hands it here, and this went on to
 * ask the git host about the SAME tag a second time to read the tree — and the git host sometimes
 * answers 404 to that second ask even though the tag plainly exists, the first ask having just
 * proved it. Seen live: {@code 2026.1029.83835 is not a tag the git host holds} logged in the same
 * second as the listener's own successful resolve. The result was poison that was not actually
 * poison — a WARN, a {@code false}, no {@code mt_release} row, the frame acked and nothing ever
 * retrying it — and {@code GET /pins} refusing the release's deployment for ever afterward.
 *
 * <p><b>The fix is to stop resolving twice.</b> A full commit sha needs no ref resolution at all,
 * so when the caller hands one, the tree is read AT THE SHA — {@code manifests.pinsAt(project,
 * repository, sha)} — never at the tag a second time. And because the caller just proved that
 * commit exists (it is how {@code sha} was obtained), any answer other than FOUND for that read is
 * not a tag the host might legitimately lack: it is the git host contradicting itself within the
 * same request, which is exactly the race above, so it is now <b>thrown</b> rather than swallowed —
 * NOT_FOUND included, alongside UNREACHABLE and INVALID as before. Throwing keeps the bus frame
 * owed, so it is redelivered, and a redelivery racing the same inconsistency a second time is far
 * cheaper than a release whose ledger row is lost for good.
 *
 * <p>The three-way split survives for the two callers that have no sha of their own — {@code
 * ReleaseLedgerHeal} and {@code work/ReleaseLedgerBackfill}'s rows written before the sha column
 * existed — which still read the tree at the tag and keep today's behaviour:
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
   * <p><b>One spelling, and this class resolves it at most once per release</b> (qits-1733). {@code
   * bus/ScmEventListener} resolves a release's commit with it; this class reads the released tree
   * at that COMMIT when the caller hands one, and falls back to resolving this same tag itself only
   * when no commit is given. A second copy of the string would be the one that quietly asked about
   * a ref nobody has.
   */
  public static final String TAG_PREFIX = "refs/tags/";

  @Inject ManifestScanner manifests;

  @Inject MaintenanceStore store;

  @Inject MaintenanceConfig config;

  /**
   * Records one release: the row, and every INTERNAL pin the tree at its released commit declared.
   *
   * <p><b>Reads at {@code sha} when the caller gave one, and never re-resolves the tag to get
   * there</b> (qits-1733). {@code bus/ScmEventListener} and {@code work/ReleaseLedgerBackfill} both
   * already know the commit — the first from resolving the tag itself moments earlier, the second
   * from the gitlink's own recorded sha — so handing it here means the tree is read with a full
   * commit sha, which needs no ref resolution and so cannot race the tag the way a second {@code
   * refs/tags/<version>} lookup can. In that branch any status other than FOUND is thrown, NOT_FOUND
   * included: the caller just proved the commit exists, so the git host answering otherwise is not a
   * tag it legitimately lacks, it is the same inconsistency that motivated this change, and throwing
   * keeps the frame owed for a retry rather than recording nothing for good.
   *
   * <p>{@code sha} is null for {@code ReleaseLedgerHeal}, which has only a version and a deployment
   * asking about it — there the tree is read at the tag, as it always was, and the three-way split
   * in the class comment applies.
   *
   * @param project the project the git host addresses the repository under
   * @param repository the CATALOG NAME — {@code mt_release.repository} is compared with a closure
   *     entry directly, so nothing else may be written into it
   * @param version the released version, which is also the tag's name
   * @param sha the commit the caller has already resolved, read directly and never re-resolved from
   *     the tag; null or blank falls back to reading the tree at the tag itself
   * @param occurredAt the publisher's moment, never a clock reading where there is a frame
   * @return whether a row was written
   * @throws IllegalStateException when the git host could not be asked (always retryable), or when
   *     {@code sha} was given and the tree at it answered anything but FOUND — the caller already
   *     proved that commit exists, so this is thrown rather than swallowed
   */
  public boolean record(
      String project, String repository, String version, String sha, Instant occurredAt) {
    if (project == null || repository == null || version == null || version.isBlank()) {
      LOG.debugf(
          "A release of %s at %s under %s names no (project, repository, version) to read a tree at",
          repository, version, project);
      return false;
    }
    boolean callerResolved = sha != null && !sha.isBlank();
    String revision = callerResolved ? sha : TAG_PREFIX + version;
    ManifestScanner.Pins read = manifests.pinsAt(project, repository, revision);
    if (read.status() == FileLookup.Status.UNREACHABLE
        || read.status() == FileLookup.Status.INVALID) {
      // Retryable, and thrown on purpose: on the bus path the claim rolls back and the release is
      // offered again, which is the only thing that can fill a ledger row afterwards.
      throw new IllegalStateException(
          "the git host could not be asked for " + repository + " " + revision + ": "
              + read.message());
    }
    if (callerResolved && read.status() != FileLookup.Status.FOUND) {
      // The caller already resolved this exact commit, so anything but FOUND here is the git host
      // contradicting itself within the same release — the qits-1733 race — not a tag it genuinely
      // lacks. Thrown so the frame stays owed and is redelivered, rather than losing the row.
      throw new IllegalStateException(
          "the git host answered " + read.status() + " for " + repository + " " + version + " at "
              + sha + ", although that commit was just resolved");
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
