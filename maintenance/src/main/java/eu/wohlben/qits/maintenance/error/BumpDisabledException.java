package eu.wohlben.qits.maintenance.error;

/**
 * Bumping is switched off on this deployment — a 409.
 *
 * <p><b>It stops a person's re-run as well as the dispatcher</b>, which is the whole point of the
 * switch: a platform that wants to watch what WOULD be changed for a week reads the inventory and
 * pushes nothing, and a key that only stopped the clock would not give it that.
 *
 * <p>409 rather than 403: nothing is wrong with the caller or their identity — the resource is in a
 * state that does not accept the request.
 */
public class BumpDisabledException extends MaintenanceException {

  public BumpDisabledException() {
    super(409, "bumping is disabled (qits.maintenance.bump.enabled=false)");
  }
}
