package eu.wohlben.qits.maintenance.error;

/** {@code GET /sbom-check} before any check has run: 404, not an empty report that reads as clean. */
public class NoSbomCheckRunException extends MaintenanceException {

  public NoSbomCheckRunException() {
    super(404, "no sbom check has run yet");
  }
}
