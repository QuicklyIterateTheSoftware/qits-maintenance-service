package eu.wohlben.qits.maintenance.model;

/**
 * One bump's life.
 *
 * <p><b>{@link #REQUESTED} is not "queued" alone.</b> It is also where a bump waits after qits-ci
 * answered 503: CI refusing work it cannot take right now is a retry, not a failure, so the row
 * keeps its changes and the poller dispatches it again.
 */
public enum BumpStatus {
  /** Written, not yet accepted by qits-ci — queued, or deferred by a 503. */
  REQUESTED,

  /** qits-ci accepted it and named at least one run. */
  RUNNING,

  /** The run passed and the branch head moved. */
  SUCCEEDED,

  /** The run went red, or qits-ci answered the trigger with no run at all. */
  FAILED,

  /** THE RUN PASSED AND THE BRANCH HEAD DID NOT MOVE. The step found nothing to write — the
   * versions were already there — which is a real outcome and not a failure. */
  NOTHING_TO_DO,

  /**
   * <b>A release-request automation that will not end on its own fold</b>: a newer fold arrived
   * while it waited, or its run went red after the request had moved on — the shared core refuses a
   * fold that is no longer the one it was sent for, and that refusal is red. Its outcome answers a
   * question nobody is asking any more, so it is recorded and discarded. A GREEN run is never
   * superseded: what it found is a fact about the fold it ran on. Only {@code BumpMode.AUTOMATION}
   * rows reach it.
   */
  SUPERSEDED;

  /** Whether nothing further will happen to a bump in this state. */
  public boolean terminal() {
    return this == SUCCEEDED || this == FAILED || this == NOTHING_TO_DO || this == SUPERSEDED;
  }

  /** Whether a bump in this state holds the (repository, group) lock a second request is refused
   * on. */
  public boolean active() {
    return this == REQUESTED || this == RUNNING;
  }
}
