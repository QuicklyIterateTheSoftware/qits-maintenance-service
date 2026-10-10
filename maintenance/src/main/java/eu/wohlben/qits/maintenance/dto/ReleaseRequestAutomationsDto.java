package eu.wohlben.qits.maintenance.dto;

import java.time.Instant;
import java.util.List;

/**
 * <b>Where one release request's automations stand at one fold</b> — the answer of {@code POST
 * /release-requests/{requestId}/automations} and of the read beside it, and what qits-projects' gate
 * holds the request on.
 *
 * <p><b>This is a wire contract.</b> qits-projects caches it per (request, fold) and passes the
 * request only when every entry is FRESH for the sha it is about to release; an entry that is
 * absent, UNKNOWN or anything else holds. A kind that does not apply to the repository is simply not
 * listed — a repository no kind applies to answers an empty list and releases as it always did.
 *
 * <p><b>Two words are sent only to a trigger that asks for them</b> (qits-1133): a trigger body
 * carrying {@code "accepts": ["WAITING", "NOT_APPLICABLE"]} is answered a WAITING entry for a DERIVED
 * kind held behind its SOURCE kinds and a NOT_APPLICABLE entry, with its {@code reason}, for every
 * kind that does not apply. Without the flag the answer is the old one: no NOT_APPLICABLE entry, and
 * a waiting kind reads REQUESTED — "not run yet", which an older reader holds on and asks again
 * about, exactly what it should do — so the two services can be released in either order.
 *
 * @param requestId the release request
 * @param foldSha the fold the entries are for; null on a read of a request nothing was ever asked
 *     about
 * @param automations one entry per kind that applies, ordered by kind
 */
public record ReleaseRequestAutomationsDto(
    String requestId, String foldSha, List<AutomationDto> automations) {

  /**
   * One kind's outcome at the fold.
   *
   * @param kind the kind's wire name, {@code screenshot-baselines}
   * @param label what the page prints, {@code Screenshot baselines}
   * @param state FRESH (nothing to regenerate: the plan said so, a green run left its branch where it
   *     was, or the outcome was carried from the previous fold), REQUESTED and RUNNING (waiting),
   *     COMMITTED (a green run wrote a commit — joined, or pushed onto the request's branch — which
   *     re-folds the request, so this fold never ships), FAILED (a red run, a refused join, or a loop
   *     that did not converge), UNKNOWN (applicability or the plan could not be decided; nothing is
   *     stored and the next ask decides again) or SUPERSEDED (a newer fold arrived while it waited,
   *     or its run went red after the request moved on; a green run keeps its own outcome)
   * @param detail the sentence: the hold reason, the run's ending, why it is fresh
   * @param bumpId the {@code mt_bump} row that decides the state — {@code GET /bumps/{id}} has the
   *     rest; null on UNKNOWN
   * @param runIds the qits-ci runs behind it, across every branch the kind wrote at this fold
   * @param branch the branch the deciding row writes
   * @param resultSha the commit a COMMITTED outcome left on {@code branch}; null in every other state
   * @param updatedAt when the deciding row last changed
   * @param failure why the deciding row's run went red (qits-1116); null unless it is FAILED with a
   *     failing step recorded
   * @param reason why a NOT_APPLICABLE kind does not apply, or why a WAITING one waits (qits-1133);
   *     the same sentence as {@code detail}, and null in every other state
   */
  public record AutomationDto(
      String kind,
      String label,
      String state,
      String detail,
      String bumpId,
      List<String> runIds,
      String branch,
      String resultSha,
      Instant updatedAt,
      FailureDto failure,
      String reason) {}
}
