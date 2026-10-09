package eu.wohlben.qits.maintenance.automation;

/**
 * When a kind runs inside the pre-run (ticket qits-1133, decision D1).
 *
 * <p>SOURCE kinds write the build's inputs (a pom, a lockfile, a gitlink). DERIVED kinds write what
 * is generated from those inputs (screenshots, the entity diagram). A DERIVED kind is planned only
 * once every SOURCE kind is FRESH at the fold; until then it is answered WAITING.
 */
public enum Stage {
  /** Writes inputs of the build. Always planned; no carry-over. */
  SOURCE,
  /** Writes output generated from the sources. Waits for every SOURCE kind to be FRESH. */
  DERIVED
}
