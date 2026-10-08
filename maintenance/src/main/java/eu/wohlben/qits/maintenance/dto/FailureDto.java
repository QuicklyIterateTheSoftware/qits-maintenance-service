package eu.wohlben.qits.maintenance.dto;

/**
 * <b>Why a run went red</b> (qits-1116) — the step that failed it, read off qits-ci's run when it
 * ended. Carried by a FAILED bump and by a FAILED automation entry; null everywhere else.
 *
 * @param stepIndex the failing step's index in the run
 * @param image the image that step ran, or null when the run did not say
 * @param exitCode its exit code, or null when it had none
 * @param excerpt up to three lines of its log that say why, newline-separated, or null when it
 *     wrote nothing
 */
public record FailureDto(int stepIndex, String image, Integer exitCode, String excerpt) {

  /** A row's failure columns as the wire answers them, or null when the row records none. */
  public static FailureDto of(
      Integer stepIndex, String image, Integer exitCode, String excerpt) {
    return stepIndex == null ? null : new FailureDto(stepIndex, image, exitCode, excerpt);
  }
}
