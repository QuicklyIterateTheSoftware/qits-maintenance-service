package eu.wohlben.qits.maintenance.latest;

import eu.wohlben.qits.bootstrap.graph.Ecosystem;
import java.util.Collection;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.apache.maven.artifact.versioning.ComparableVersion;

/**
 * "Which of these two is newer", per ecosystem, and "is this one a prerelease".
 *
 * <p><b>Three orders, because three ecosystems disagree.</b> Maven's is
 * {@code ComparableVersion} — the class every maven resolver ranks with, so this service and a
 * build agree about what {@code 2026.821.1} means beside {@code 2026.813.161828}. npm's is semver,
 * which ranks prereleases by rules maven does not share. An OCI tag is neither by rule and both in
 * practice: this platform tags calver and upstream tags semver, so a tag is read as a version when
 * it looks like one and ignored when it does not — {@code latest} is a moving reference, not a
 * release.
 *
 * <p><b>A prerelease is recognised, not compared away.</b> The order still places
 * {@code 1.0.0-rc.1} where it belongs; what the prerelease flag decides is whether such a version
 * is OFFERED, and that is the pending computation's rule rather than this one's.
 *
 * <p><b>GITLINK rides maven's order, and it is the LATEST column that does so — never a pin.</b> A
 * gitlink's {@code mt_latest.latest} is a calver release version announced on the bus, which
 * maven's order ranks exactly as it ranks every other release of this platform's own; that is what
 * keeps {@code recordLatestIfNewer} forward-only for one. A gitlink PIN is a commit sha, which no
 * order here or anywhere ranks, so nothing compares one — see {@code pending/PendingChanges}.
 */
public final class VersionOrder {

  /**
   * The maven qualifiers that mean "not released yet".
   *
   * <p>{@code ComparableVersion} already ranks these below a release, so this list is not what
   * makes the order right — it is what lets the pending rule refuse to offer one.
   */
  private static final Pattern MAVEN_PRERELEASE =
      Pattern.compile(
          ".*(?:snapshot|alpha|beta|-rc|\\.rc|-cr|\\.cr|milestone|-m\\d|preview|-pre|-ea|-dev).*",
          Pattern.CASE_INSENSITIVE);

  /** A tag this service will read as a version: digits first, dots and one qualifier after. */
  private static final Pattern OCI_VERSION_TAG =
      Pattern.compile("v?\\d+(?:\\.\\d+)*(?:[.\\-+][0-9A-Za-z.\\-+]*)?");

  private VersionOrder() {}

  /** The order for one ecosystem. */
  public static Comparator<String> comparator(Ecosystem ecosystem) {
    return switch (ecosystem) {
      case NPM -> VersionOrder::compareNpm;
      case MAVEN, DOCKER, GITLINK -> VersionOrder::compareMaven;
    };
  }

  /** Whether {@code candidate} is strictly newer than {@code current} in that ecosystem's order. */
  public static boolean newer(Ecosystem ecosystem, String current, String candidate) {
    if (current == null || candidate == null) {
      return false;
    }
    return comparator(ecosystem).compare(candidate, current) > 0;
  }

  /** Whether the version is a prerelease — a SNAPSHOT, a release candidate, a semver prerelease. */
  public static boolean prerelease(Ecosystem ecosystem, String version) {
    if (version == null || version.isBlank()) {
      return false;
    }
    return switch (ecosystem) {
      case NPM -> {
        SemVer parsed = SemVer.parse(version);
        yield parsed == null ? version.contains("-") : parsed.prereleaseVersion();
      }
      case MAVEN, DOCKER, GITLINK -> MAVEN_PRERELEASE.matcher(version.trim()).matches();
    };
  }

  /**
   * Whether a string a registry offered is a version this service will rank at all.
   *
   * <p>An OCI registry's tag list holds {@code latest} and whatever else a person typed; the others
   * publish versions. A tag that is not a version is dropped rather than compared, because there is
   * no order that puts {@code latest} anywhere.
   */
  public static boolean readable(Ecosystem ecosystem, String version) {
    if (version == null || version.isBlank()) {
      return false;
    }
    String value = version.trim();
    return switch (ecosystem) {
      case NPM -> SemVer.parse(value) != null;
      case MAVEN, GITLINK -> true;
      case DOCKER -> OCI_VERSION_TAG.matcher(value).matches();
    };
  }

  /**
   * The version this service reports as "latest" out of everything a registry published.
   *
   * <p><b>The highest RELEASE, and a prerelease only when there is no release at all.</b> The stake
   * is that one column serves every pin of that dependency: a prerelease sitting in it would, by
   * the pending rule, be offered to nobody with a released pin — so a repository three stable
   * releases behind would show nothing pending for as long as an upstream carried a release
   * candidate. The fallback keeps SNAPSHOT-only and prerelease-only artifacts visible, which is
   * exactly the case the pending rule's "unless the pin is one too" is written for.
   */
  public static Optional<String> highest(Ecosystem ecosystem, Collection<String> published) {
    Comparator<String> order = comparator(ecosystem);
    String bestRelease = null;
    String bestAny = null;
    for (String raw : published) {
      if (!readable(ecosystem, raw)) {
        continue;
      }
      String candidate = raw.trim();
      if (bestAny == null || order.compare(candidate, bestAny) > 0) {
        bestAny = candidate;
      }
      if (!prerelease(ecosystem, candidate)
          && (bestRelease == null || order.compare(candidate, bestRelease) > 0)) {
        bestRelease = candidate;
      }
    }
    return Optional.ofNullable(bestRelease != null ? bestRelease : bestAny);
  }

  private static int compareMaven(String left, String right) {
    return new ComparableVersion(normalise(left)).compareTo(new ComparableVersion(normalise(right)));
  }

  /**
   * A leading {@code v} is a tag convention, not part of the version. {@code v1.2.3} and
   * {@code 1.2.3} are one release published twice, and ComparableVersion would rank the letter
   * above the digits.
   */
  private static String normalise(String version) {
    String value = version.trim();
    if (value.length() > 1 && (value.charAt(0) == 'v' || value.charAt(0) == 'V')
        && Character.isDigit(value.charAt(1))) {
      return value.substring(1);
    }
    return value;
  }

  private static int compareNpm(String left, String right) {
    SemVer ours = SemVer.parse(left);
    SemVer theirs = SemVer.parse(right);
    if (ours != null && theirs != null) {
      return ours.compareTo(theirs);
    }
    // One of them is not semver. There is no order between a version and a non-version, so fall
    // back to the string — it never reports "newer" for two different unparseable strings by
    // accident, and `readable` keeps them out of a latest lookup anyway.
    if (ours == null && theirs == null) {
      return left.trim().toLowerCase(Locale.ROOT).compareTo(right.trim().toLowerCase(Locale.ROOT));
    }
    return ours == null ? -1 : 1;
  }
}
