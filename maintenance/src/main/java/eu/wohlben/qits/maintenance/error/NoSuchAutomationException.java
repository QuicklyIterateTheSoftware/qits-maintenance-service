package eu.wohlben.qits.maintenance.error;

/** No release-request automation of that kind is registered in this build — a 404. */
public class NoSuchAutomationException extends MaintenanceException {

  public NoSuchAutomationException(String kind) {
    super(404, "no release-request automation of kind '" + kind + "'");
  }
}
