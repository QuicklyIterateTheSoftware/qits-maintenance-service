package eu.wohlben.qits.maintenance.automation;

/**
 * Which half of a release request's PRE-RUN a kind belongs to (qits-1133).
 *
 * <p>The pre-run is every write a request needs before its first build, and it has two halves
 * because some writes are other writes' inputs. A dependency bump moves a pom, and the entity
 * diagram is generated from what that pom compiles; screenshots are rendered from what the bumped
 * lock installs. Running both at once would build the derived output against inputs that are about
 * to change, and the source commit's re-fold would throw it away.
 *
 * <ul>
 *   <li><b>{@link #SOURCE}</b> — writes BUILD INPUTS: {@code estate-pins} and {@code
 *       dependency-bump}. Always planned; nothing to change is FRESH with no run.
 *   <li><b>{@link #DERIVED}</b> — writes output generated FROM the build inputs: {@code
 *       screenshot-baselines} and {@code entity-diagram}. Planned only once every applicable SOURCE
 *       kind is FRESH at the fold; until then it answers {@link AutomationState#WAITING}. A SOURCE
 *       commit is a new fold, and that is what re-runs the DERIVED kinds.
 * </ul>
 *
 * <p><b>The carry-over invariant is restated per stage</b>: no kind's output is another kind's input
 * <i>within a stage</i>. A DERIVED kind may read SOURCE output — that is what the stage is for — and
 * it is the ordering, not carry-over, that keeps the two from chasing each other. One exception is
 * allowed within DERIVED (qits-1957): a reader that declares precise {@link
 * ReleaseRequestAutomation#inputPaths() inputs} may read another DERIVED kind's output when that
 * writer carries over the reader's commits — {@code golden-recordings-npm} reads the {@code
 * routes.json} {@code screenshot-baselines} writes, and is planned again after it, while the pacts
 * it commits leave {@code screenshot-baselines} carried. The chase ends after one step.
 */
public enum Stage {
  SOURCE,
  DERIVED
}
