package eu.wohlben.qits.maintenance.error;

/**
 * The inventory holds no repository of that name — a 404.
 *
 * <p>"Never scanned" and "not in the catalog" are the same answer here on purpose: a repository
 * with no row has nothing to show either way, and inventing a difference would mean answering for
 * qits-projects.
 */
public class NoSuchRepositoryException extends MaintenanceException {

  public NoSuchRepositoryException(String name) {
    super(404, "no repository '" + name + "' in the inventory");
  }

  private NoSuchRepositoryException(String message, boolean sentence) {
    super(404, message);
  }

  /**
   * The re-run door was named a release request and no repository: this service has never been
   * asked about that request, so it cannot tell which repository it belongs to.
   */
  public static NoSuchRepositoryException forRequest(String requestId) {
    return new NoSuchRepositoryException(
        "no repository is known for release request " + requestId
            + ": no automation has been asked about it yet, so name the repository",
        true);
  }

  /**
   * The settle door was asked about a repository no scan has read yet — a newly created one, whose
   * first release request would otherwise wait for the nightly scan (qits-1118). Still a 404, but
   * one that says a scan of it is queued, so the next ask is the one that finds it.
   */
  public static NoSuchRepositoryException scanQueued(String name) {
    return new NoSuchRepositoryException(
        "no repository '" + name + "' in the inventory yet — a scan of it is queued; ask again"
            + " shortly",
        true);
  }
}
