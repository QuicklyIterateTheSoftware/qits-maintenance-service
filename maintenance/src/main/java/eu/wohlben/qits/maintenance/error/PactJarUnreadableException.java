package eu.wohlben.qits.maintenance.error;

/**
 * A deployed consumer's pact jar could not be read off the hosted maven repository, so the
 * interactions answer is not complete (qits-2702). A 502, never a partial answer: a reader told
 * "nobody calls this path" when the truth is "one jar could not be read" would retire what a serving
 * consumer still uses.
 */
public class PactJarUnreadableException extends MaintenanceException {

  public PactJarUnreadableException(String coordinate, String url, String why) {
    super(
        502,
        "the pact jar "
            + coordinate
            + " could not be read, so the interactions are not complete: "
            + why
            + (url == null ? "" : " (" + url + ")"));
  }
}
