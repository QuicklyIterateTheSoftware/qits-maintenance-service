package eu.wohlben.qits.maintenance.error;

/**
 * qits-deployments could not say which versions serve or would roll back, so {@code GET /pins}
 * cannot say what they pin. A 503, never a partial answer: the GC treats an answered source as
 * complete, and a keep-set missing the deployed versions deletes what a restart would pull.
 */
public class DeploymentsUnansweredException extends MaintenanceException {

  public DeploymentsUnansweredException(String why) {
    super(503, "the deployed versions could not be read, so the pins are not complete: " + why);
  }
}
