package eu.wohlben.qits.maintenance.error;

/**
 * <b>A group bump was asked for after the cutover (qits-1133 R2): 410 Gone.</b>
 *
 * <p>With {@code qits.maintenance.pre-run.upstream.enabled} on, nothing writes a {@code
 * maintenance/<group>} branch any more: a repository's pending pins are written by the {@code
 * dependency-bump} release-request automation, at the fold of the request they belong to — a
 * person's, or the main-only {@code LOWEST} one the dispatcher opens. Gone rather than 409, because
 * no retry will ever succeed; the door itself is removed in R5.
 */
public class GroupBumpsRetiredException extends MaintenanceException {

  public static final String MESSAGE =
      "group bumps (maintenance/<group> branches) are retired (qits-1133): pending pins are written"
          + " by the dependency-bump release-request automation — at the fold of an open release"
          + " request, or of the main-only LOWEST request the dispatcher opens when there is none."
          + " Re-run it with POST /release-requests/{id}/automations/dependency-bump/runs";

  public GroupBumpsRetiredException() {
    super(410, MESSAGE);
  }
}
