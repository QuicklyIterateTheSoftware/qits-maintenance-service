package eu.wohlben.qits.maintenance.model;

/**
 * WHOSE BRANCH A BUMP IS WRITING — the one fact that decides how its ending is read.
 *
 * <p><b>Two modes rather than one reinterpreted.</b> Everything this service does to a {@code
 * maintenance/<group>} branch rests on OWNING that branch: nobody else commits to it, so the head
 * moving is the run's work and the head not moving is the run finding nothing to write. A bump onto
 * a branch somebody else owns has none of that. The workspace commits to it constantly, so the same
 * measurement — did the head move — would call an ordinary afternoon's work a success and an
 * ff-rejection somebody else's rewrite. The two are not one mode with a flag on it; they are two
 * readings of the same three facts, and this column is which reading applies.
 *
 * <p><b>A word rather than a boolean, and the difference is what a third mode costs.</b> The modes
 * differ in four places — whether a branch row is written, whether the ending compares heads,
 * whether a release request is opened, and what the active-bump lock is keyed on — so a boolean
 * called {@code targeted} would spell every one of them as {@code !targeted}, which names the older
 * of the two behaviours after the newer one. It also makes the group path's own meaning a negation
 * in every query and in every {@code psql} session that goes looking for one. A word says what a row
 * IS, reads as itself in a listing, and a fourth reading of the same facts is a constant here rather
 * than a second boolean nobody can combine with the first.
 *
 * <p><b>Since V18 there are two modes again, GROUP and AUTOMATION</b>: TARGETED and BASELINES became
 * two kinds of release-request automation, which is a column ({@code automation_kind}) rather than
 * a mode. Their words stay readable through {@link #of} for rows a migration has not reached, and
 * nothing new writes them.
 *
 * <p><b>Kept rather than dropped by a V19 (qits-1006), because something still reads the word, not a
 * row.</b> The two doors that wrote them are retired, but {@code Inventory.bump} composes {@code
 * BumpDto.mode} as {@code BumpMode.of(row.mode).name()}, never the raw column — so the wire answer
 * for a legacy row depends on the enum holding the word, not merely on {@link #of} not throwing.
 * {@code golden-masters/pending-bumps/listPendingBumps.json} pins exactly that: a {@code mode:
 * "TARGETED"} row on the wire, recorded off a live request. Dropping the constant would not make that
 * row disappear — it would make it answer {@code "GROUP"} instead, which is the fallback for a word
 * this build does not know, and is a silent reclassification of a real historical row rather than the
 * no-op removing dead code should be.
 *
 * <p><b>No check constraint backs it</b>, for the reason {@code ScanTrigger} and the five status
 * enums have none: the invariant lives at the single writer — {@code MaintenanceStore} takes this
 * enum and nothing else writes the column — and a constraint would make a new constant a migration.
 */
public enum BumpMode {

  /**
   * <b>Read only since qits-1133 R5</b>: the retired group bump. The branch was this service's,
   * {@code maintenance/<group>}, tracked by an {@code mt_branch} row and released by the ask this
   * service made when the run came back green. Nothing writes the word any more; V22 closed every
   * row that was still going, and the rest are history the bump listing still shows.
   */
  GROUP,

  /**
   * <b>Read only, for rows written before V18.</b> V18 maps every one of them to {@link #AUTOMATION}
   * with {@code automation_kind = estate-pins}, and nothing writes this word any more; it stays so
   * that {@link #of} reads a row a migration has not reached as what it was.
   *
   * <p>The branch is the CALLER'S, and it was named in the request rather than derived from anything
   * here.
   *
   * <p>A wrapper release request wants its gitlink pins IN the fold it is going to gate, which means
   * they have to be written onto the workspace branch that request is built from — a branch this
   * service did not create, does not name, will not delete and has no lifecycle for. It writes no
   * {@code mt_branch} row (there is no branch of ours to record), it opens no release request (the
   * caller already has one — a second ask would be a second request for the same work), and its
   * verdict is its CI run's, not its branch head's.
   */
  TARGETED,

  /**
   * <b>Read only, for rows written before V18</b>, which maps them to {@link #AUTOMATION} with
   * {@code automation_kind = screenshot-baselines}. Nothing writes this word any more.
   *
   * <p>The branch is this service's, {@code maintenance/baselines/<request>}, and it carries no
   * dependency at all: one run renders a release request's screenshot tests in the CI image and
   * commits the reference images it wrote. A green run that moved the branch JOINS it to that
   * release request rather than opening a new one, because the images belong to the work already
   * under review. It writes no {@code mt_branch} row: the release request, not this service, decides
   * when the branch is done.
   */
  BASELINES,

  /**
   * <b>A release-request automation</b> (epic qits-978): a regeneration that has to land INSIDE a
   * release request — its estate pins, its screenshot baselines — run on every fold of that request
   * and held against it until it is fresh.
   *
   * <p><b>Which regeneration is a COLUMN, {@code mt_bump.automation_kind}, and no longer a mode.</b>
   * TARGETED and BASELINES were two modes because each was written by hand, with its own dispatch
   * and its own ending; the two differed only in whose branch the commit lands on, and that is now
   * the kind's own {@code target()}. A third regeneration is one more implementation of {@code
   * automation.ReleaseRequestAutomation}, never one more constant here. The engine that reads these
   * rows is {@code automation.AutomationService}; a row in this mode writes no {@code mt_branch} row
   * and asks for no release, because the request it belongs to is already open.
   */
  AUTOMATION;

  /** The mode a column holds, defaulting to {@link #GROUP} for a row written before it existed. */
  public static BumpMode of(String stored) {
    if (stored == null || stored.isBlank()) {
      return GROUP;
    }
    try {
      return valueOf(stored);
    } catch (IllegalArgumentException e) {
      // A word this build does not know is not a reason to lose a row. The older reading is the
      // conservative one: it reads heads and refuses to release anything it did not push.
      return GROUP;
    }
  }
}
