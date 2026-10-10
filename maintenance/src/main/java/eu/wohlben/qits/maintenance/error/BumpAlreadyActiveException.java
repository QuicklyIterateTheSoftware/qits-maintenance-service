package eu.wohlben.qits.maintenance.error;

import java.util.UUID;

/**
 * An automation run onto that branch is already going — a 409.
 *
 * <p><b>One bump at a time per branch is a safety property, not a convenience.</b> The bump step
 * rebuilds one branch under a lease; two runs writing the same branch would make the second a lease
 * rejection — read as STALE — at best, and at worst two commits from two readings of the pins. The
 * message names the bump that holds the lock so the caller can go and read it.
 */
public class BumpAlreadyActiveException extends MaintenanceException {

  private final UUID activeBumpId;

  private BumpAlreadyActiveException(String message, UUID activeBumpId) {
    super(409, message);
    this.activeBumpId = activeBumpId;
  }

  /**
   * The refusal for a run locked on a BRANCH. What is held here is a REF, and two runs onto two
   * different branches of one repository are refused by nothing — see {@code
   * MaintenanceStore.openAutomation}. (The group bump's lock on a {@code (repository, group)} pair
   * is retired with group bumps, qits-1133.)
   */
  public static BumpAlreadyActiveException onBranch(
      String repository, String branch, UUID activeBumpId) {
    return new BumpAlreadyActiveException(
        "a bump of " + repository + " onto " + branch + " is already active: " + activeBumpId,
        activeBumpId);
  }

  public UUID activeBumpId() {
    return activeBumpId;
  }
}
