package eu.wohlben.qits.maintenance.error;

import java.util.UUID;

/**
 * A bump for that (repository, group) is already going — a 409.
 *
 * <p><b>One bump at a time per branch is a safety property, not a convenience.</b> The bump step
 * pushes ff-only onto one branch; two runs writing the same branch would make the second a non-ff
 * rejection at best, and at worst two commits computed from two different readings of the pins. The
 * message names the bump that holds the lock so the caller can go and read it.
 */
public class BumpAlreadyActiveException extends MaintenanceException {

  private final UUID activeBumpId;

  public BumpAlreadyActiveException(String repository, String group, UUID activeBumpId) {
    super(
        409,
        "a bump of " + repository + "/" + group + " is already active: " + activeBumpId);
    this.activeBumpId = activeBumpId;
  }

  private BumpAlreadyActiveException(String message, UUID activeBumpId) {
    super(409, message);
    this.activeBumpId = activeBumpId;
  }

  /**
   * The same refusal for a TARGETED bump, which is locked on the branch rather than on a group.
   *
   * <p>Its own sentence rather than the one above with a branch substituted for a group: a caller
   * reading "a bump of qits-qits/workspace/ws-7 is already active" would go looking for a group by
   * that name. What is held here is a REF, and two targeted bumps onto two different branches of one
   * repository are refused by nothing — see {@code MaintenanceStore.openAutomation}.
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
