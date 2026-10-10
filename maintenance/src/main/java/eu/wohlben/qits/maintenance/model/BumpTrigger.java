package eu.wohlben.qits.maintenance.model;

/**
 * What asked for a bump. The wire spelling is upper case here, unlike a run trigger elsewhere on
 * the platform, because the plan pins the column's vocabulary as {@code SCHEDULED|MANUAL} —
 * and {@code FOLD} since the release-request automations (qits-978).
 */
public enum BumpTrigger {
  /**
   * The clock — read only since qits-1133 R5: the retired nightly group bump wrote it, and its rows
   * are history.
   */
  SCHEDULED,

  /** Somebody pressed a button, or a machine posted to a route — an automation's re-run door. */
  MANUAL,

  /**
   * A release request's FOLD: qits-projects posted the fold to {@code POST
   * /release-requests/{id}/automations} and an automation opened this row for it. Neither the clock
   * nor a person's press — a re-run through {@code …/automations/{kind}/runs} is {@link #MANUAL}.
   */
  FOLD,

  /**
   * An UPSTREAM release (qits-1133): {@code mt_latest} moved for a dependency an open release
   * request's repository pins, and the {@code dependency-bump} automation was re-planned on that
   * request's current fold.
   */
  UPSTREAM
}
