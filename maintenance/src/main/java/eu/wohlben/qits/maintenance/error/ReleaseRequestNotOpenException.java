package eu.wohlben.qits.maintenance.error;

/**
 * The release request a baselines update names cannot take a branch: it is settled, unknown, or
 * qits-projects could not say — a 409. The caller opens a release request first, or names an open
 * one.
 */
public class ReleaseRequestNotOpenException extends MaintenanceException {

  public ReleaseRequestNotOpenException(String message) {
    super(409, message);
  }
}
