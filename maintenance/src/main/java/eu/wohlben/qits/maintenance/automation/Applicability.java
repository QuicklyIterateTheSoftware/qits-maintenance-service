package eu.wohlben.qits.maintenance.automation;

/**
 * Whether a kind applies to a repository at one fold — <b>decided by the platform from the
 * repository, never by an opt-in file</b> (owner decision, qits-978).
 *
 * <p>Three answers, and the third is the one that matters. NOT_APPLICABLE is stored per (request,
 * fold, kind) and answered with its reason to a caller that accepts the word (qits-1133); an older
 * caller gets it left out, as before. UNKNOWN is answered and NOT stored, so qits-projects' sweep asks again and the request holds until
 * somebody can say — a git host that was away must not read as "no screenshots here".
 *
 * @param state which of the three
 * @param reason the sentence, for the two that are not APPLIES
 */
public record Applicability(State state, String reason) {

  public enum State {
    APPLIES,
    NOT_APPLICABLE,
    UNKNOWN
  }

  public static Applicability applies() {
    return new Applicability(State.APPLIES, null);
  }

  public static Applicability notApplicable(String reason) {
    return new Applicability(State.NOT_APPLICABLE, reason);
  }

  public static Applicability unknown(String reason) {
    return new Applicability(State.UNKNOWN, reason);
  }

  public boolean applied() {
    return state == State.APPLIES;
  }
}
