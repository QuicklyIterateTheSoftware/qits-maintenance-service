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
 * unique, and {@link #committablePaths()} are pairwise disjoint. Paths read from the fold are
 * checked again at run time, per subject. Since qits-1133 a SOURCE kind's output IS a DERIVED kind's
 * input (a new library changes the screenshots): {@link #stage()} orders them, and carry-over of a
 * DERIVED kind counts only DERIVED output.
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

  /** When it runs in the pre-run: SOURCE kinds first, DERIVED kinds once every SOURCE is FRESH. */
  default Stage stage() {
    return Stage.DERIVED;
  }

  /**
   * The paths this kind reads, as pathspecs. A DERIVED kind is carried to a new fold when no path
   * that changed since the previous fold matches one of them. Default: every path.
   */
  default List<String> inputPaths() {
    return ALL_PATHS;
  }

  /** Every path, as a pathspec: the default of {@link #inputPaths()}. */
  List<String> ALL_PATHS = List.of(":(glob)**");

  /**
   * Whether this build offers the kind. A kind that is switched off is not listed, not asked and
   * not run. Default: on.
   */
  default boolean enabled() {
    return true;
  }

  /**
   * Payload fields known only at dispatch, sent beside the plan's extras.
   *
   * @param startHead the head of the kind's branch read just before the run, or null when the branch
   *     does not exist
   */
  default Map<String, String> dispatchExtras(AutomationSubject subject, String startHead) {
    return Map.of();
  }

  /**
   * The payload's {@code group} for an {@link Target#OWN_BRANCH} kind that runs on the bump
   * pipeline ({@code MaintenanceBump}) rather than the shared core. Default: the kind id.
   */
  default String bumpGroup() {
    return kind();
  }

  /**
   * The priority of the source this kind's branch adds when it joins the request ({@code LOWEST},
   * {@code LOW}, …), or null to let qits-projects choose.
   */
  default String joinPriority() {
    return null;
  }

  /** {@link Target#OWN_BRANCH} only: the branch is this plus the request id. */
  default String branchPrefix() {
    return AutomationService.BRANCH_PREFIX + kind() + "/";
  }
}
