package eu.wohlben.qits.maintenance.model;

/**
 * What became of one group's maintenance branch.
 *
 * <p>The branch is the whole "merge request": there is no workspace and no review object, so this
 * column is the only place its life is recorded.
 */
public enum BranchState {
  /** Never pushed, or deleted — by hand, or by the release that consumed it, which drops its named
   * source branches when it lands. The next bump starts fresh from {@code main}. */
  NONE,

  /** It exists on the git host and this service put it there. A further bump commits on top of it,
   * ff-only. */
  PUSHED,

  /** SOMEBODY REWROTE IT BY HAND. The bump's push is ff-only and never forced, so a non-ff
   * rejection is a person's edit — they own the branch now, and this service stops writing to it
   * until it is gone. */
  STALE,

  /**
   * <b>A word rows still hold and nothing writes any more.</b> It meant "this branch went through
   * qits-workspaces' release door", which the door's {@code SCMRelease} was the only thing that
   * could say. A release is a tag on {@code release/<id>} now — the request's fold, not this branch
   * — so no event names a {@code maintenance/} branch as released, and the ending a branch really
   * has is the {@code SCMDeleteBranch} that follows the release: NONE.
   *
   * <p>Kept because the column is a stored word and old rows carry it; never assigned.
   */
  RELEASED,

  /** The last bump run went red. */
  FAILED,

  /**
   * <b>Retired by the cutover (qits-1133 R2), and terminal.</b> {@code LegacyGroupBranchSweep}
   * withdrew the bump-only request standing on the branch, deleted it, and wrote this — or found the
   * branch already gone. {@code MaintenanceStore.recordBranch} never rewrites a row in this state,
   * so no late run ending or {@code SCMDeleteBranch} brings it back to a state anything acts on. A
   * stored word under no check constraint, like the rest: no migration.
   */
  RETIRED
}
