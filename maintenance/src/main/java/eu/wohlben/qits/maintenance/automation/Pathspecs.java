package eu.wohlben.qits.maintenance.automation;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Whether a path falls under a git pathspec, read the way {@code git add -- <pathspec>} reads it —
 * because the pathspecs a kind declares are exactly what qits-ci's postlude stages, and carry-over
 * asks this side the same question about the paths a fold changed.
 *
 * <p><b>Two spellings, and only two.</b> {@code :(glob)} magic is wildmatch: {@code **}{@code /}
 * matches any number of leading directories, {@code /**} everything inside, {@code *} and {@code ?}
 * stay inside one segment. A pathspec with no magic is git's default: the path itself or anything
 * below it as a directory, with {@code *} and {@code ?} matching across slashes. Any other magic
 * ({@code top}, {@code literal}) is read as the default without wildcards; nothing this service
 * declares uses one.
 */
public final class Pathspecs {

  private static final String GLOB = "glob";

  private Pathspecs() {}

  /** Whether {@code path} is under any of {@code pathspecs}. */
  public static boolean matchesAny(List<String> pathspecs, String path) {
    if (pathspecs == null) {
      return false;
    }
    for (String pathspec : pathspecs) {
      if (matches(pathspec, path)) {
        return true;
      }
    }
    return false;
  }

  /** Whether {@code path} is under {@code pathspec}. */
  public static boolean matches(String pathspec, String path) {
    if (pathspec == null || path == null || pathspec.isBlank()) {
      return false;
    }
    String spec = pathspec;
    boolean glob = false;
    boolean literal = false;
    if (spec.startsWith(":(")) {
      int close = spec.indexOf(')');
      if (close < 0) {
        return false;
      }
      for (String magic : spec.substring(2, close).split(",")) {
        String word = magic.trim().toLowerCase(Locale.ROOT);
        glob |= GLOB.equals(word);
        literal |= "literal".equals(word);
      }
      spec = spec.substring(close + 1);
    }
    String candidate = path.startsWith("./") ? path.substring(2) : path;
    if (glob) {
      return wildmatch(spec).matcher(candidate).matches();
    }
    if (candidate.equals(spec) || candidate.startsWith(spec.endsWith("/") ? spec : spec + "/")) {
      return true;
    }
    return !literal
        && (spec.indexOf('*') >= 0 || spec.indexOf('?') >= 0)
        && fnmatch(spec).matcher(candidate).matches();
  }

  /** {@code :(glob)}'s reading: {@code **} spans directories, {@code *} and {@code ?} do not. */
  static Pattern wildmatch(String glob) {
    StringBuilder regex = new StringBuilder();
    int i = 0;
    while (i < glob.length()) {
      char c = glob.charAt(i);
      if (glob.startsWith("**/", i)) {
        regex.append("(?:.*/)?");
        i += 3;
      } else if (glob.startsWith("/**", i) && i + 3 == glob.length()) {
        regex.append("/.*");
        i += 3;
      } else if (glob.startsWith("**", i)) {
        regex.append(".*");
        i += 2;
      } else if (c == '*') {
        regex.append("[^/]*");
        i++;
      } else if (c == '?') {
        regex.append("[^/]");
        i++;
      } else {
        regex.append(Pattern.quote(String.valueOf(c)));
        i++;
      }
    }
    return Pattern.compile(regex.toString());
  }

  /** The default pathspec's wildcards, which match across slashes. */
  private static Pattern fnmatch(String spec) {
    StringBuilder regex = new StringBuilder();
    for (char c : spec.toCharArray()) {
      switch (c) {
        case '*' -> regex.append(".*");
        case '?' -> regex.append('.');
        default -> regex.append(Pattern.quote(String.valueOf(c)));
      }
    }
    return Pattern.compile(regex.toString());
  }
}
