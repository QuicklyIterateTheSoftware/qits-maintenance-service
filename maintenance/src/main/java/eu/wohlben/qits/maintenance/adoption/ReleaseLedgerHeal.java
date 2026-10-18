package eu.wohlben.qits.maintenance.adoption;

import eu.wohlben.qits.maintenance.work.WorkQueue;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * <b>The ledger rows {@code GET /pins} refused for, recorded from their tags in the background</b>
 * (qits-1172).
 *
 * <p>{@code Inventory.pins} answers 503 when a version qits-deployments serves or would roll back
 * to belongs to a repository this inventory holds and {@code mt_release} has no row for it. On the
 * live estate that is not a corner: the ledger only exists since 2026-10-10, the backfill recorded
 * one release per repository, and the deployer's rollback set reaches further back than either. A
 * refusal nothing can clear — there is no write door onto the ledger — would stop the artifact GC
 * until every such application happened to redeploy. So the refusal heals itself: each missing
 * {@code (repository, version)} is handed here, and the tag {@code refs/tags/<version>} is read
 * and recorded by {@link ReleaseLedger#record}, exactly as an {@code SCMRelease} would have been.
 * The GC run after that gets its 200.
 *
 * <p><b>On the {@link WorkQueue}, never on the request.</b> A tag read is a tree plus a handful of
 * blobs against the git host; the GET answers its 503 at once and the worker does the reading, in
 * line with the scans and bumps that already share that one thread — so the git host still sees a
 * single caller.
 *
 * <p><b>One attempt per pair per window, which is what keeps a 15-minute GC poll from being a
 * stampede.</b> A pair is remembered from the moment it is queued:
 *
 * <ul>
 *   <li>while it is IN FLIGHT, a second refusal queues nothing;
 *   <li>once RECORDED it is forgotten — the row exists, and the next GET never asks again;
 *   <li>a tag the git host does NOT HOLD is a genuine inconsistency (a deployment of a release that
 *       does not exist there), and asking again in fifteen minutes will not change the answer. It
 *       is a WARN, the 503 says so, and it is retried only after {@link #NOT_HELD_RETRY} — long
 *       enough to be quiet, short enough that a tag pushed later is still picked up;
 *   <li>a git host that could not be ASKED is retried after {@link #FAILED_RETRY}, the GC's own
 *       cadence, because that one is transient.
 * </ul>
 *
 * <p>The memory is this process's and that is enough: a restart forgets the windows, which costs
 * one tag read per missing pair, once.
 *
 * <p><b>{@code on_main} follows the rule it always had.</b> {@link ReleaseLedger#record} writes the
 * row not-on-main, the GC keeps its pins under that rule too, and the next main scan's {@code
 * ScanService.keepMain} asks the git host whether main contains it — nothing here decides that.
 *
 * <p>{@code occurredAt} is the moment of recording. The publisher's moment was on a frame this
 * service either never saw or saw before the ledger existed, and nothing reads the column as a
 * release date for a kept release.
 */
@ApplicationScoped
public class ReleaseLedgerHeal {

  private static final Logger LOG = Logger.getLogger(ReleaseLedgerHeal.class);

  /** How long a tag the git host did not hold is left alone before it is asked about again. */
  public static final Duration NOT_HELD_RETRY = Duration.ofHours(6);

  /** How long a pair whose read could not be made waits before the next attempt. */
  public static final Duration FAILED_RETRY = Duration.ofMinutes(15);

  /** What became of the last attempt at one pair, as far as a caller needs to say it. */
  public enum State {
    /** Queued or being read. */
    IN_FLIGHT,
    /** The git host does not hold the tag. */
    NOT_HELD,
    /** The git host could not be asked; retried after {@link #FAILED_RETRY}. */
    FAILED,
    /** The inventory row names no project, so there is no address to read the tag at. */
    UNADDRESSABLE
  }

  private record Attempt(State state, Instant at) {}

  @Inject ReleaseLedger ledger;

  @Inject WorkQueue work;

  private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

  /**
   * Makes sure a record of {@code repository version} is on its way, unless one was attempted too
   * recently to try again.
   *
   * @param project the project the git host addresses the repository under — the inventory row's
   * @param repository the CATALOG NAME, which is what {@code mt_release.repository} holds
   * @return the pair's state after this call, for the refusal's sentence
   */
  public State request(String project, String repository, String version) {
    if (project == null || project.isBlank()) {
      // The next scan fills the column; there is nothing to queue until it has.
      return State.UNADDRESSABLE;
    }
    String key = repository + " " + version;
    Instant now = Instant.now();
    java.util.concurrent.atomic.AtomicBoolean queue = new java.util.concurrent.atomic.AtomicBoolean();
    Attempt current =
        attempts.compute(
            key,
            (pair, last) -> {
              if (last != null && !due(last, now)) {
                return last;
              }
              queue.set(true);
              return new Attempt(State.IN_FLIGHT, now);
            });
    if (queue.get()) {
      work.submit(
          "recording the release " + key + " a deployment names",
          () -> record(key, project, repository, version));
    }
    return current.state();
  }

  /** Whether the last attempt's window is over. An in-flight attempt never is. */
  private static boolean due(Attempt last, Instant now) {
    return switch (last.state()) {
      case IN_FLIGHT -> false;
      case NOT_HELD -> !now.isBefore(last.at().plus(NOT_HELD_RETRY));
      case FAILED -> !now.isBefore(last.at().plus(FAILED_RETRY));
      case UNADDRESSABLE -> true;
    };
  }

  private void record(String key, String project, String repository, String version) {
    try {
      if (ledger.record(project, repository, version, null, Instant.now())) {
        attempts.remove(key);
        LOG.infof(
            "Recorded the release %s %s from its tag: a deployment names it and the ledger did not",
            repository, version);
      } else {
        attempts.put(key, new Attempt(State.NOT_HELD, Instant.now()));
        LOG.warnf(
            "qits-deployments serves or would roll back to %s %s, and the git host holds no tag"
                + " %s%s for it; GET /pins refuses until it does",
            repository, version, ReleaseLedger.TAG_PREFIX, version);
      }
    } catch (RuntimeException e) {
      attempts.put(key, new Attempt(State.FAILED, Instant.now()));
      LOG.warnf(
          "The release %s %s could not be recorded from its tag; retried after %s: %s",
          repository, version, FAILED_RETRY, e.getMessage());
    }
  }

  /** Forgets every attempt. For a test that starts from an empty ledger. */
  public void forget() {
    attempts.clear();
  }
}
