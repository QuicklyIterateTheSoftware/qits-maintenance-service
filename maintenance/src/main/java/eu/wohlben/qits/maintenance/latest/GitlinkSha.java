package eu.wohlben.qits.maintenance.latest;

import java.util.Locale;
import java.util.Optional;

/**
 * How a gitlink's commit sha rides in {@code mt_latest.source_url}.
 *
 * <p><b>Why that column and not a new one.</b> A released version and the commit it was cut from are
 * one fact about one release, and {@code source_url} is already "where this claim came from" — a
 * registry url for a poll, {@code event:<frame id>} for the bus. A gitlink's claim comes from a tag
 * in the git host, so the sha IS the provenance, and a column added to carry it would be a
 * migration and a second nullable field that only one of four ecosystems ever fills.
 *
 * <p><b>Several readers and one writer, so the spelling lives here.</b> {@code
 * latest/MainHeadLatest} writes it (qits-1257: the commit a repository's {@code main} head is, and
 * the release it carries); the pending rule, the estate pins and the repository page read it. A second spelling of {@code "sha:"}
 * would be the one that failed to match, silently, as "nothing is pending".
 */
public final class GitlinkSha {

  /** What a gitlink's {@code source_url} starts with. */
  public static final String PREFIX = "sha:";

  private static final java.util.regex.Pattern HEX = java.util.regex.Pattern.compile("[0-9a-f]{7,64}");

  private GitlinkSha() {}

  /** The stored form of one commit sha, lower-cased so two spellings of it cannot differ. */
  public static String of(String sha) {
    return PREFIX + sha.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * The sha a {@code source_url} carries, or empty.
   *
   * <p>Empty for a null column, for a column written by any other writer (a registry url), and for
   * anything after the prefix that is not a hex object name. A pin is only pending against a sha
   * that could be one — comparing a pin's sha with a sentence would report every gitlink as behind
   * for ever.
   */
  public static Optional<String> read(String sourceUrl) {
    if (sourceUrl == null) {
      return Optional.empty();
    }
    String value = sourceUrl.trim();
    if (!value.startsWith(PREFIX)) {
      return Optional.empty();
    }
    String sha = value.substring(PREFIX.length()).trim().toLowerCase(Locale.ROOT);
    return HEX.matcher(sha).matches() ? Optional.of(sha) : Optional.empty();
  }

  /**
   * Whether two object names denote the same commit.
   *
   * <p><b>Abbreviation-tolerant, on purpose.</b> Everything this service writes is a full 40-hex
   * name, but a {@code .gitmodules} pin read from a tree is whatever git recorded and a person may
   * have written an abbreviation into an assertion; the shorter of the two is compared as a prefix,
   * which is git's own rule for an abbreviated object name.
   */
  public static boolean same(String left, String right) {
    if (left == null || right == null) {
      return false;
    }
    String one = left.trim().toLowerCase(Locale.ROOT);
    String other = right.trim().toLowerCase(Locale.ROOT);
    if (one.isEmpty() || other.isEmpty()) {
      return false;
    }
    return one.length() <= other.length() ? other.startsWith(one) : one.startsWith(other);
  }
}
