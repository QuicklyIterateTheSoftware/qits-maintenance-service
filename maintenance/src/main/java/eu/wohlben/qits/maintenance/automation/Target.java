package eu.wohlben.qits.maintenance.automation;

/**
 * Where an automation's commit lands — the one fact the two regenerations this service had before
 * qits-978 differed in, and therefore the whole of how its ending is read.
 */
public enum Target {

  /**
   * <b>A branch of the automation's own</b>, {@code maintenance/automations/<kind>/<request>},
   * joined to the request when a green run moved it. Nothing else commits there, so the head is read
   * before the run and again after it: unmoved is FRESH (nothing to do), moved is a join.
   */
  OWN_BRANCH,

  /**
   * <b>The request's own source branches, in place</b>, by a plain ff-only push. Those branches
   * belong to whoever opened the request and move while the run goes, so no head is compared: the
   * verdict is the CI run's, and the commit it left is read once afterwards.
   */
  SOURCE_BRANCHES
}
