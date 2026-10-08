package eu.wohlben.qits.maintenance.bump;

import java.math.BigInteger;
import java.util.Comparator;

/**
 * The order of the platform's release versions, {@code YYYY.MMDD.HHMMSS}.
 *
 * <p><b>Every dot segment is a NUMBER, and a string compare gets the estate's own versions
 * wrong.</b> The segments are not zero-padded — a release cut at 06:18:54 is {@code
 * 2026.1007.61854} — so {@code 2026.1007.171656} sorts before it as text and after it as the clock
 * says. The same holds across months: {@code 2026.1006.x} is newer than {@code 2026.919.x}. This is
 * the ordering qits-githost's pin rule uses for the same strings (component by component as
 * numbers, the shorter padded with zeros), so the two services agree about which tag is newest.
 *
 * <p>A version that is not all digits and dots is not one the platform cut. It is ordered BELOW
 * every calver rather than guessed at, and among such strings by their text, so the comparison
 * stays total and a stray value can never be chosen over a real release.
 */
public final class Calver {

  /** Oldest first. */
  public static final Comparator<String> ORDER = Calver::compare;

  private Calver() {}

  /** Whether the string is a run of dot-separated digit groups. */
  public static boolean isCalver(String version) {
    return version != null && version.matches("[0-9]+(?:\\.[0-9]+)*");
  }

  /** Negative when {@code a} is older than {@code b}, positive when newer, zero when equal. */
  public static int compare(String a, String b) {
    boolean calverA = isCalver(a);
    boolean calverB = isCalver(b);
    if (!calverA || !calverB) {
      if (calverA != calverB) {
        return calverA ? 1 : -1;
      }
      return String.valueOf(a).compareTo(String.valueOf(b));
    }
    String[] left = a.split("\\.");
    String[] right = b.split("\\.");
    for (int index = 0; index < Math.max(left.length, right.length); index++) {
      BigInteger l = index < left.length ? new BigInteger(left[index]) : BigInteger.ZERO;
      BigInteger r = index < right.length ? new BigInteger(right[index]) : BigInteger.ZERO;
      int compared = l.compareTo(r);
      if (compared != 0) {
        return compared;
      }
    }
    return 0;
  }
}
