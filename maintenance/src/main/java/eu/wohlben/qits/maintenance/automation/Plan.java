package eu.wohlben.qits.maintenance.automation;

import eu.wohlben.qits.maintenance.pending.Change;
import java.util.List;
import java.util.Map;

/**
 * A kind's cheap answer before anything is run: FRESH (nothing to regenerate, and no CI run is
 * spent finding that out), RUN (with the runs it wants), or UNKNOWN (it could not tell — answered,
 * never stored, and asked again by the sweep).
 *
 * <p><b>A plan may ask for more than one run.</b> An {@link Target#OWN_BRANCH} kind has one branch
 * per request and so one run; a {@link Target#SOURCE_BRANCHES} kind writes each source branch that
 * needs it, and each of those is a run of its own with its own change list — estate pins asks for one
 * per branch whose gitlinks are behind and never for a branch that is already right.
 *
 * @param kind which of the three
 * @param reason the sentence for FRESH and UNKNOWN
 * @param runs the runs a RUN plan wants, never empty
 */
public record Plan(Kind kind, String reason, List<Run> runs) {

  public enum Kind {
    FRESH,
    RUN,
    UNKNOWN
  }

  /**
   * One run a plan asks for.
   *
   * @param branch the branch it writes, or null for the kind's own ({@code branchPrefix() +
   *     requestId}); a SOURCE_BRANCHES kind always names one
   * @param changes what a {@code MaintenanceBump} run writes; empty for the shared core
   * @param extras the kind's own payload fields, sent beside the shared ones
   */
  public record Run(String branch, List<Change> changes, Map<String, Object> extras) {

    public Run {
      changes = changes == null ? List.of() : List.copyOf(changes);
      extras = extras == null ? Map.of() : Map.copyOf(extras);
    }
  }

  public Plan {
    runs = runs == null ? List.of() : List.copyOf(runs);
  }

  /** The default: run once, on the kind's own branch, with these extra payload fields. */
  public static Plan run(Map<String, Object> extras) {
    return new Plan(Kind.RUN, null, List.of(new Run(null, List.of(), extras)));
  }

  /** Several runs, one per branch that needs writing. */
  public static Plan runs(List<Run> runs) {
    if (runs == null || runs.isEmpty()) {
      throw new IllegalArgumentException("a RUN plan names at least one run; nothing to run is FRESH");
    }
    return new Plan(Kind.RUN, null, runs);
  }

  public static Plan fresh(String reason) {
    return new Plan(Kind.FRESH, reason, List.of());
  }

  public static Plan unknown(String reason) {
    return new Plan(Kind.UNKNOWN, reason, List.of());
  }
}
