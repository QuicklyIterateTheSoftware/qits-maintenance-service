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

  /**
   * A path both lists would match, or null — the runtime disjointness check (qits-1133). Read
   * through {@link #witnesses}: for a plain path that is the path itself, for a glob a couple of
   * paths it matches. A null or empty list overlaps nothing.
   */
  public static String overlap(List<String> one, List<String> other) {
    if (one == null || other == null || one.isEmpty() || other.isEmpty()) {
      return null;
    }
    for (String pathspec : one) {
      for (String witness : witnesses(pathspec)) {
        if (matches(pathspec, witness) && matchesAny(other, witness)) {
          return witness;
        }
      }
    }
    for (String pathspec : other) {
      for (String witness : witnesses(pathspec)) {
        if (matches(pathspec, witness) && matchesAny(one, witness)) {
          return witness;
        }
      }
    }
    return null;
  }

  /** Paths a pathspec matches: its wildcards filled in a couple of ways. */
  public static List<String> witnesses(String pathspec) {
    if (pathspec == null || pathspec.isBlank()) {
      return List.of();
    }
    String spec =
        pathspec.startsWith(":(") && pathspec.indexOf(')') > 0
            ? pathspec.substring(pathspec.indexOf(')') + 1)
            : pathspec;
    String shallow =
        spec.replace("**/", "").replace("/**", "/x.png").replace("**", "x").replace("*", "x")
            .replace("?", "x");
    String deep =
        spec.replace("**/", "a/b/").replace("/**", "/c/d.png").replace("**", "y").replace("*", "y")
            .replace("?", "y");
    return shallow.equals(deep) ? List.of(shallow) : List.of(shallow, deep);
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
