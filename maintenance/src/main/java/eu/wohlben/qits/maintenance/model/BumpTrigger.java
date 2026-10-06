package eu.wohlben.qits.maintenance.model;

/**
 * What asked for a bump. The wire spelling is upper case here, unlike a run trigger elsewhere on
 * the platform, because the plan pins the column's vocabulary as {@code SCHEDULED|MANUAL} —
 * and {@code FOLD} since the release-request automations (qits-978).
 */
public enum BumpTrigger {
  /** The clock: {@code schedule/BumpSchedule} found a group pending with no active bump. */
  SCHEDULED,

  /** Somebody pressed the button, or a machine posted to the route. */
  MANUAL,

  /**
   * A release request's FOLD: qits-projects posted the fold to {@code POST
   * /release-requests/{id}/automations} and an automation opened this row for it. Neither the clock
   * nor a person's press — a re-run through {@code …/automations/{kind}/runs} is {@link #MANUAL}.
   */
  FOLD
}
