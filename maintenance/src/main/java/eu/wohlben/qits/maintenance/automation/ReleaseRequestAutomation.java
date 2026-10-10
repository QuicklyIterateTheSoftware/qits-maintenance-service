package eu.wohlben.qits.maintenance.automation;

import java.util.List;
import java.util.Map;

/**
 * <b>A release-request automation</b> (epic qits-978): a regeneration that has to land INSIDE a
 * release request, run on every fold of that request, joined to it, and held against it until it is
 * fresh for the sha the request is about to release. Estate pins and screenshot baselines are the two
 * this service ships; qits-760's entity diagram is the third, and it is one more implementation of
 * this interface plus one kind file in qits-ci — no page, no gate and no door changes.
 *
 * <p><b>Everything that is not on this interface is shared</b>, in {@link AutomationService}: the
 * carry-over that ends the loop an automation's own commit starts, the row, the dispatch, the caps,
 * the poll, the ending, the join and NOTHING_TO_DO. A kind says what it regenerates and where it may
 * write; it never says how a run is followed or what a green one means.
 *
 * <p>Implementations are CDI beans, discovered through {@code Instance<ReleaseRequestAutomation>}.
 * Two invariants hold across every one of them, and a registry test enforces both: kind ids are
 * unique, and {@link #committablePaths()} are pairwise disjoint across ALL kinds. The second is half
 * of the invariant carry-over rests on — <b>no automation's output is another automation's input
 * within its {@link Stage}</b> (qits-1133); the other half is each kind's own to document, and a kind
 * that declares {@link #inputPaths()} lets the registry test hold that half too. A DERIVED kind may
 * read SOURCE output: the stage ordering, not carry-over, is what keeps those two apart.
 */
public interface ReleaseRequestAutomation {

  /**
   * Stable id, {@code [a-z0-9-]+}: the wire name, the row's {@code automation_kind} and the branch
   * segment — {@code estate-pins}, {@code screenshot-baselines}, qits-760's {@code entity-diagram}.
   */
  String kind();

  /** What the release request page prints: {@code Screenshot baselines}. */
  String label();

  /**
   * Decided from the repository AT THE FOLD — the inventory row and git-host reads — and never from
   * an opt-in file. Cheap: at most a couple of blob reads, because it is asked of every repository
   * on every fold.
   */
  Applicability applicability(AutomationSubject subject);

  /** The optional cheap pre-run answer: FRESH ends without a CI run. Default: run. */
  default Plan plan(AutomationSubject subject) {
    return Plan.run(Map.of());
  }

  /** The qits-ci event that runs it: {@code ReleaseRequestAutomation} (the shared core) or {@code MaintenanceBump}. */
  String pipeline();

  /**
   * The git pathspecs a run may commit; nothing else is ever staged. Disjoint across kinds. A kind
   * whose paths depend on the repository answers the static part here and the rest in {@link
   * #committablePaths(AutomationSubject)}.
   */
  List<String> committablePaths();

  /**
   * The paths a run may commit for ONE subject — what carry-over and the payload use. Defaults to the
   * static {@link #committablePaths()}; a kind whose paths are read from the fold overrides it.
   */
  default List<String> committablePaths(AutomationSubject subject) {
    return committablePaths();
  }

  /** Where the commit lands. */
  Target target();

  /**
   * Which half of the pre-run this kind belongs to (qits-1133): SOURCE kinds write build inputs and
   * are always planned; DERIVED kinds are planned only once every applicable SOURCE kind is FRESH at
   * the fold, and answer WAITING until then. See {@link Stage}.
   */
  Stage stage();

  /**
   * The pathspecs this kind's plan and run READ, or null for "everything" — the default, which
   * leaves the kind to the union rule of carry-over. A kind that declares them gets the precise
   * rule instead: a fold whose changed paths touch none of them, after a FRESH or COMMITTED fold,
   * carries over; one that touches any of them is planned again, whoever wrote it.
   */
  default List<String> inputPaths() {
    return null;
  }

  /** {@link #inputPaths()} for ONE subject; a kind whose inputs are read from the fold overrides it. */
  default List<String> inputPaths(AutomationSubject subject) {
    return inputPaths();
  }

  /** {@link Target#OWN_BRANCH} only: the branch is this plus the request id. */
  default String branchPrefix() {
    return AutomationService.BRANCH_PREFIX + kind() + "/";
  }
}
