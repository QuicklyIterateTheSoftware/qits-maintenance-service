package eu.wohlben.qits.maintenance.bump;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * <b>The few lines of a failed step's log that say why</b> (qits-1116) — what a red automation's
 * sentence quotes instead of "its step log says why".
 *
 * <p>The rule, in order: strip ANSI escapes; keep the lines carrying an error marker ({@code
 * [ERROR]}, {@code ERROR:}, {@code Error:}, {@code error:}); drop Maven's boilerplate — a marker
 * with nothing after it, the {@code [Help 1]} pointers, the "re-run with -e/-X" advice and the
 * {@code mvn <args> -rf} resume hint; keep the first three that remain, each trimmed and cut to
 * {@value #LINE_CAP} characters. A log with no such line answers its last three non-blank lines
 * instead, because whatever a step printed last is the next best guess at why it stopped. The
 * whole is capped at {@value #TOTAL_CAP} characters.
 */
public final class FailureExcerpt {

  static final int LINES = 3;
  static final int LINE_CAP = 300;
  static final int TOTAL_CAP = 1000;

  /** CSI sequences ({@code ESC [ ... final}) and the two-character escapes. */
  private static final Pattern ANSI =
      Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)|\u001B[@-Z\\\\-_]");

  private static final List<String> MARKERS = List.of("[ERROR]", "ERROR:", "Error:", "error:");

  private static final List<String> BOILERPLATE =
      List.of(
          "-> [Help",
          "To see the full stack trace",
          "Re-run Maven using",
          "For more information about the errors",
          "[Help 1]",
          "After correcting the problems",
          "mvn <args> -rf");

  private FailureExcerpt() {}

  /** The excerpt of one step's output, or null when the output is null or blank. */
  public static String of(String output) {
    if (output == null || output.isBlank()) {
      return null;
    }
    List<String> lines = ANSI.matcher(output).replaceAll("").lines().toList();
    List<String> picked = new ArrayList<>();
    for (String line : lines) {
      if (picked.size() == LINES) {
        break;
      }
      if (errorLine(line)) {
        picked.add(cut(line.trim()));
      }
    }
    if (picked.isEmpty()) {
      List<String> nonBlank = lines.stream().filter(line -> !line.isBlank()).toList();
      for (String line : nonBlank.subList(Math.max(0, nonBlank.size() - LINES), nonBlank.size())) {
        picked.add(cut(line.trim()));
      }
    }
    if (picked.isEmpty()) {
      return null;
    }
    String joined = String.join("\n", picked);
    return joined.length() > TOTAL_CAP ? joined.substring(0, TOTAL_CAP) : joined;
  }

  private static boolean errorLine(String line) {
    String marker = null;
    int at = -1;
    for (String candidate : MARKERS) {
      int found = line.indexOf(candidate);
      if (found >= 0 && (at < 0 || found < at)) {
        marker = candidate;
        at = found;
      }
    }
    if (marker == null) {
      return false;
    }
    if (line.substring(at + marker.length()).isBlank()) {
      return false;
    }
    for (String noise : BOILERPLATE) {
      if (line.contains(noise)) {
        return false;
      }
    }
    return true;
  }

  private static String cut(String line) {
    return line.length() > LINE_CAP ? line.substring(0, LINE_CAP) : line;
  }
}
