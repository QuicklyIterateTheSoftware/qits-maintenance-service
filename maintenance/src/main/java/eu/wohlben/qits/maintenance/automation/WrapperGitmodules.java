package eu.wohlben.qits.maintenance.automation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A wrapper's {@code .gitmodules}, read the way qits-projects' {@code WrapperGitmodules.entries}
 * reads it — <b>a port, deliberately not {@code manifest.GitmodulesParser}</b>.
 *
 * <p>The estate-pin decision moved here from qits-projects (qits-999), and the promise of the move
 * is that it decides exactly what it decided there: same entries, same names, byte-identical change
 * JSON. The scan's parser answers a different question and answers it differently — it names a
 * submodule by its URL's basename and drops a section with no url, unquotes values and strips
 * inline comments, and takes the FIRST value of a repeated key — and any one of those would move an
 * estate pin that the wrapper's own reader would not have moved. So the wrapper's reader is ported
 * verbatim: the section's quoted name is the entry's name, keys are lower-cased, values are trimmed,
 * the LAST value of a repeated key wins (as git config reads it), and only whole-line {@code #} and
 * {@code ;} comments are comments.
 */
final class WrapperGitmodules {

  /** The one path that says a fold declares submodules. */
  static final String PATH = ".gitmodules";

  private WrapperGitmodules() {}

  /**
   * One {@code [submodule "<name>"]} section.
   *
   * @param name the section's quoted name — the sibling repository's name in a wrapper
   * @param path its {@code path}, or null when the section declares none
   * @param url its {@code url}, or null
   */
  record Entry(String name, String path, String url) {}

  /** Every submodule section, in file order. A section with no quoted name is not one. */
  static List<Entry> entries(String content) {
    List<Entry> out = new ArrayList<>();
    if (content == null || content.isEmpty()) {
      return out;
    }
    String name = null;
    Map<String, String> keys = new LinkedHashMap<>();
    for (String raw : content.split("\n", -1)) {
      String line = raw.trim();
      if (line.startsWith("[")) {
        if (name != null) {
          out.add(new Entry(name, keys.get("path"), keys.get("url")));
        }
        name = sectionName(line);
        keys = new LinkedHashMap<>();
        continue;
      }
      if (name == null || line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
        continue;
      }
      int eq = line.indexOf('=');
      if (eq < 0) {
        continue;
      }
      // Last value wins, as git config itself reads a repeated key.
      keys.put(
          line.substring(0, eq).trim().toLowerCase(Locale.ROOT), line.substring(eq + 1).trim());
    }
    if (name != null) {
      out.add(new Entry(name, keys.get("path"), keys.get("url")));
    }
    return out;
  }

  private static String sectionName(String header) {
    String inner =
        header.substring(1, header.endsWith("]") ? header.length() - 1 : header.length());
    if (!inner.trim().toLowerCase(Locale.ROOT).startsWith("submodule")) {
      return null;
    }
    int firstQuote = inner.indexOf('"');
    int lastQuote = inner.lastIndexOf('"');
    if (firstQuote >= 0 && lastQuote > firstQuote) {
      return inner.substring(firstQuote + 1, lastQuote);
    }
    return null;
  }
}
