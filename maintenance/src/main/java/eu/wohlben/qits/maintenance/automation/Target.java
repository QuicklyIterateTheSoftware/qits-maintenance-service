package eu.wohlben.qits.maintenance.automation;

/**
 * Where an automation's commit lands — the one fact the two regenerations this service had before
 * qits-978 differed in, and therefore the whole of how its ending is read.
 */
public enum Target {

  /**
   * <b>A branch of the automation's own</b>, {@code maintenance/automations/<kind>/<request>},
   * joined to the request when a green run moved it. Nothing else commits there, so the head is read
   * before the run and again after it: unmoved is FRESH (nothing to do), moved is a join. Every kind
   * this service registers lands here since qits-1258, {@code estate-pins} included.
   */
  OWN_BRANCH,

  /**
   * <b>The request's own source branches, in place</b>, by a plain ff-only push. Those branches
   * belong to whoever opened the request and move while the run goes, so no head is compared: the
   * verdict is the CI run's, and the commit it left is read once afterwards.
   *
   * <p><b>No registered kind answers this any more</b> (qits-1258): {@code estate-pins} was the one,
   * and writing every source branch of a request is what made a nine-branch wrapper request settle
   * for longer than its caller would wait. It stays for the rows opened before that change, which a
   * release may still find REQUESTED or RUNNING on a source branch: the engine reads such a row's
   * target from its branch ({@code AutomationService.target}), and dispatches and ends it this way.
   */
  SOURCE_BRANCHES
}
