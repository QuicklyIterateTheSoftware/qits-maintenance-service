package eu.wohlben.qits.maintenance.error;

/**
 * The SBOM check could not finish, and says why. A 502: what failed is a peer — qits-artifacts not
 * answering a presence probe with anything but a listing or a 404.
 *
 * <p><b>Thrown rather than skipped, on purpose.</b> A version the probe could not ask about is
 * neither "still in the store" nor "collected", and either guess is wrong in a way nobody would
 * see: one files a ticket about a version that may be gone, the other silently drops one that is
 * there. So the run fails loudly and stores no report; the previous one stays the answer.
 */
public class SbomCheckFailedException extends MaintenanceException {

  public SbomCheckFailedException(String message) {
    super(502, message);
  }
}
