package eu.wohlben.qits.maintenance.scan;

/**
 * What asked for a scan.
 *
 * <p><b>NO scan bumps anything, whoever asked for it.</b> A SCHEDULED one used to, and that coupling
 * is gone — {@code bump.BumpDispatcher} decides what is owed, from the inventory a scan wrote. What
 * survives here is the RECORD of who asked, which is what a scan row shows and what the
 * bus's rescan is distinguishable by.
 */
public enum ScanTrigger {
  /** The button, or a machine posting to the route. */
  MANUAL,

  /** One of the two crons. */
  SCHEDULED,

  /**
   * A push to a repository's own main branch, off the bus.
   *
   * <p>It scans exactly that one repository, and — like MANUAL and unlike SCHEDULED — it never
   * bumps. What a push changes is a MANIFEST, so the honest answer to one is to re-read it; whether
   * the resulting pending set should become a branch is still the clock's standing instruction or a
   * person's press, and a bump fired from a push would put a branch on every repository somebody
   * touched during the day.
   *
   * <p>It is a new value and no migration: {@code mt_scan.trigger} is a {@code varchar} under no
   * check constraint, and the invariant lives where the writes are — {@code ScanService.request} is
   * the only writer and it takes this enum.
   */
  EVENT
}
