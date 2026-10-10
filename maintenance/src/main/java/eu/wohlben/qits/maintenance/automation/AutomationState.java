package eu.wohlben.qits.maintenance.automation;

import eu.wohlben.qits.maintenance.model.BumpStatus;

/**
 * One kind's outcome at one fold, as qits-projects' gate reads it — a reading of the {@code mt_bump}
 * row(s) behind it rather than a column of its own.
 *
 * <p><b>The rows keep the bump vocabulary</b> because they ARE bump rows, followed by the same poll
 * and read by {@code GET /bumps/{id}}: a green run that wrote nothing is NOTHING_TO_DO there and FRESH
 * here, a green run that wrote a commit is SUCCEEDED there and COMMITTED here. UNKNOWN has no row at
 * all — it is answered and never stored, so the next ask decides again.
 */
public enum AutomationState {
  /** Nothing to regenerate at this fold: a FRESH plan, a run that found nothing, or a carry-over. */
  FRESH,
  /** Waiting for a run — behind a running one, behind the cap, or for qits-ci to take it. */
  REQUESTED,
  /** qits-ci is running it. */
  RUNNING,
  /** A green run wrote a commit, which re-folds the request; this fold never ships. */
  COMMITTED,
  /** A red run, a refused join, or a loop that did not converge. The request holds. */
  FAILED,
  /** Applicability or the plan could not be decided. Answered, never stored. */
  UNKNOWN,
  /** The request moved to another fold before this ended. */
  SUPERSEDED,
  /**
   * A DERIVED kind whose SOURCE kinds are not all FRESH at this fold yet (qits-1133): nothing is
   * planned and nothing is stored, and the next ask after the sources settle decides it. On the wire
   * only to a trigger that says it {@code accepts} the word; anyone else reads it as REQUESTED.
   */
  WAITING,
  /**
   * The kind does not apply to the repository at this fold. Answered, never stored, and on the wire
   * only to a trigger that {@code accepts} it — anyone else is not told about the kind at all.
   */
  NOT_APPLICABLE;

  /** The state one row reads as. */
  public static AutomationState of(String status) {
    BumpStatus bump;
    try {
      bump = BumpStatus.valueOf(status);
    } catch (RuntimeException e) {
      return UNKNOWN;
    }
    return switch (bump) {
      case REQUESTED -> REQUESTED;
      case RUNNING -> RUNNING;
      case SUCCEEDED -> COMMITTED;
      case NOTHING_TO_DO -> FRESH;
      case FAILED -> FAILED;
      case SUPERSEDED -> SUPERSEDED;
    };
  }

  /** Whether a carried-over outcome may rest on this one: the previous fold was settled clean. */
  public boolean carries() {
    return this == FRESH || this == COMMITTED;
  }
}
