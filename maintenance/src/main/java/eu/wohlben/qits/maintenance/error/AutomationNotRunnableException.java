package eu.wohlben.qits.maintenance.error;

/**
 * An automation cannot be run on the request as it stands — its plan could not be decided, or the
 * request has no fold to run on yet — a 409. The sentence says which.
 */
public class AutomationNotRunnableException extends MaintenanceException {

  public AutomationNotRunnableException(String message) {
    super(409, message);
  }
}
