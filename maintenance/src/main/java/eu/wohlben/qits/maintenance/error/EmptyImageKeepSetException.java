package eu.wohlben.qits.maintenance.error;

/**
 * <b>{@code GET /pins/images} found no external image pin at all, and refuses rather than answer
 * an empty keep-set</b> (qits-2253).
 *
 * <p>qits-mirror evicts every mirrored tag the answer does not name, so an empty 200 would condemn
 * every one of them. Nothing on this platform builds without a base image: an empty set is a store
 * that has not been filled or has lost its rows, never a fact about the manifests.
 */
public class EmptyImageKeepSetException extends MaintenanceException {

  public EmptyImageKeepSetException() {
    super(
        503,
        "the inventory holds no external image pin, and an empty keep-set would evict every"
            + " mirrored tag; refusing until a scan has recorded the images the manifests pin");
  }
}
