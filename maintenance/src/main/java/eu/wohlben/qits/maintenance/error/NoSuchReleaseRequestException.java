package eu.wohlben.qits.maintenance.error;

/**
 * Nothing remembered about that release request — a 404.
 *
 * <p>The request may well exist in qits-projects: this service has a row only for a request it
 * opened, withdrew, or saw an upstream release restart.
 */
public class NoSuchReleaseRequestException extends MaintenanceException {

  public NoSuchReleaseRequestException(Object id) {
    super(404, "qits-maintenance remembers nothing about release request " + id);
  }
}
