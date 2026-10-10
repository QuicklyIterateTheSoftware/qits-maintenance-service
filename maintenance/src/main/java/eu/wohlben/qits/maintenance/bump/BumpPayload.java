package eu.wohlben.qits.maintenance.bump;

import eu.wohlben.qits.maintenance.bump.changelog.ChangelogRange;
import eu.wohlben.qits.maintenance.pending.Change;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What the bump step will accept, checked HERE before anything is sent.
 *
 * <p><b>The step validates the same things and refuses the same way.</b> Every value below reaches
 * a shell, a git ref or a file path in somebody else's repository, so the step holds them to a
 * character set — and a payload that fails over there is a red run, a red run row and a person
 * reading a step log to find out that a group was named with a slash. Checking first turns that
 * into a sentence on the bump row, written by the component that composed the payload.
 *
 * <p><b>What the step does NOT enforce, and what that means here.</b>
 *
 * <ul>
 *   <li>{@code from} is not a precondition. A manifest already at {@code to} is a quiet no-op, so a
 *       change computed against a pin that has since moved costs nothing — it simply does not
 *       write, and the branch not moving is what this service reads as NOTHING_TO_DO.
 *   <li>{@code location} is honoured for MAVEN ONLY — {@code property:<name>} or
 *       {@code dependency:<groupId>:<artifactId>}. npm edits whichever section holds the entry and
 *       docker anchors on the image name, so their locations are carried and ignored. They are sent
 *       anyway: the inventory records where a pin is, and a future step that wants it should not
 *       need a payload change.
 *   <li>For maven, {@code name} is COMMIT-MESSAGE ONLY. {@code location} carries the coordinates
 *       the step edits by, which is why a maven location has to be exact and a misspelled one is a
 *       silent no-op rather than a wrong edit.
 *   <li>For GITLINK, {@code name} is LOAD-BEARING and {@code manifestPath} is a DIRECTORY. The step
 *       derives the sibling clone url from the name — the repository the submodule's url points
 *       at — fetches {@code refs/tags/<to>} from it, and writes the commit that resolves to as a
 *       {@code 160000} index entry at {@code manifestPath}. So a gitlink change is the one whose
 *       {@code manifestPath} names no file, which is why the step's {@code -f} test is inside the
 *       npm and docker arms rather than in front of all three.
 * </ul>
 *
 * <p><b>The {@code changelog} field is held apart, by {@link #changelogProblems}</b> (qits-893): its
 * repository becomes a docs-site segment and a heading in somebody's commit, its versions path
 * segments, so both are held to the shapes the platform cuts.
 *
 * <p><b>Nothing below switches on the ecosystem, and that is deliberate.</b> Every rule here is
 * about a value reaching a shell or a ref, and those are the same values whichever step applies
 * them — so a fourth ecosystem is admitted by this validation the day its step exists, and refused
 * by that step if it is not ready. A per-ecosystem allow-list here would be a second place to
 * remember.
 */
public final class BumpPayload {

  /** A version reaches a shell and a docker reference. */
  static final Pattern VERSION = Pattern.compile("[0-9A-Za-z._+-]+");

  /** A group name becomes half a branch name. */
  static final Pattern GROUP = Pattern.compile("[0-9A-Za-z._-]+");

  /** A plain ref: segments of the same characters, slashes between them, nothing git rejects. */
  static final Pattern REF = Pattern.compile("[0-9A-Za-z._-]+(?:/[0-9A-Za-z._-]+)*");

  /** A full commit object name, as the step's {@code replaceHead} guard admits one. */
  static final Pattern SHA = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

  /** A changelog's repository: a catalog name, which becomes a docs-site segment and a heading. */
  static final Pattern CHANGELOG_REPOSITORY = Pattern.compile("[a-z0-9][a-z0-9-]{0,127}");

  /** A changelog's version: a calver the platform cut, {@code YYYY.MMDD.HHMMSS} unpadded. */
  static final Pattern CHANGELOG_VERSION = Pattern.compile("[0-9]{4}\\.[0-9]{1,4}\\.[0-9]+");

  private BumpPayload() {}

  /**
   * The two things the step refuses about a ref that {@link #REF} alone lets through: a LEADING DASH
   * and a {@code ..} anywhere in it.
   *
   * <p><b>Both were unreachable until a caller started naming the branch.</b> Every ref this service
   * composed was {@code maintenance/<group>} or a repository's main branch, so neither could begin
   * with a dash; the step checked them anyway, because a ref reaches an argv there. A TARGETED bump
   * takes the branch verbatim from another service, and the step's own guard —
   * {@code ''|-*|*..*|*[!0-9A-Za-z._/-]*} — is exactly the shape a payload must be held to on this
   * side, so that a bad ref is a sentence on a row rather than a red run and a step log.
   *
   * <p>A dash is admitted anywhere else in a ref, because {@code release/2026.910-1} is an ordinary
   * name and only the FIRST character can turn one into an option.
   */
  private static boolean plainRef(String ref) {
    return ref != null
        && REF.matcher(ref).matches()
        && !ref.startsWith("-")
        && !ref.contains("..");
  }

  /**
   * Every reason this payload cannot be sent, or an empty list.
   *
   * <p>Every problem is reported rather than the first: a repository whose configuration produces
   * three bad entries should be told three times, not made to fix them one run at a time.
   */
  public static List<String> problems(String group, String branch, String baseRef, List<Change> changes) {
    return problems(group, branch, baseRef, null, changes);
  }

  /**
   * The same, for a payload that may carry a {@code replaceHead} (qits-1081).
   *
   * <p><b>{@code replaceHead} is the one value here that licenses a non-fast-forward push</b> — the
   * step rebuilds the branch on {@code baseRef} under {@code --force-with-lease} on it — so it is
   * held to exactly what the step admits: lowercase hex, 40 characters or 64 (a SHA-256 repository's
   * object name), nothing else. Null is "continue the branch", the ordinary payload.
   */
  public static List<String> problems(
      String group, String branch, String baseRef, String replaceHead, List<Change> changes) {
    List<String> problems = new ArrayList<>();
    if (replaceHead != null && !SHA.matcher(replaceHead).matches()) {
      problems.add("the replace head '" + replaceHead + "' is not a full lowercase commit sha");
    }
    if (group == null || !GROUP.matcher(group).matches()) {
      problems.add("the group name '" + group + "' is not " + GROUP.pattern());
    }
    if (!plainRef(branch)) {
      problems.add("the branch '" + branch + "' is not a plain ref");
    }
    if (!plainRef(baseRef)) {
      problems.add("the base ref '" + baseRef + "' is not a plain ref");
    }
    for (Change change : changes) {
      if (change.to() == null || !VERSION.matcher(change.to()).matches()) {
        problems.add("the target version of " + change.name() + " is not " + VERSION.pattern());
      }
      String path = change.manifestPath();
      if (path == null || path.isBlank() || path.startsWith("/") || path.contains("..")) {
        problems.add(
            "the manifest path of " + change.name() + " must be relative and free of '..': " + path);
      }
    }
    return List.copyOf(problems);
  }

  /**
   * Every reason the {@code changelog} fields of a payload cannot be sent, or an empty list (epic
   * qits-893).
   *
   * <p><b>A separate check rather than a parameter of {@link #problems}</b>, because the two are
   * asked at different moments: the payload's own fields are checked before anything is read, and
   * the ranges only exist after the docs store has been asked. Pure, like the other — it is handed
   * the ranges and reads nothing.
   *
   * <p>The step's CLI turns each repository into a docs-site path and a {@code ## } heading, and
   * each version into a path segment and a {@code # } heading, so both are held to the shapes the
   * platform actually cuts: a catalog name, and a calver. An empty version list is a range that
   * says nothing, and {@code ChangelogRanges} never builds one.
   */
  public static List<String> changelogProblems(Map<Change, ChangelogRange> changelogs) {
    List<String> problems = new ArrayList<>();
    if (changelogs == null) {
      return List.of();
    }
    changelogs.forEach(
        (change, range) -> {
          String name = change == null ? null : change.name();
          if (range == null) {
            problems.add("the changelog of " + name + " is empty");
            return;
          }
          if (range.repository() == null
              || !CHANGELOG_REPOSITORY.matcher(range.repository()).matches()) {
            problems.add(
                "the changelog repository of "
                    + name
                    + " '"
                    + range.repository()
                    + "' is not "
                    + CHANGELOG_REPOSITORY.pattern());
          }
          if (range.versions().isEmpty()) {
            problems.add("the changelog of " + name + " names no version");
          }
          for (String version : range.versions()) {
            if (version == null || !CHANGELOG_VERSION.matcher(version).matches()) {
              problems.add(
                  "the changelog version '"
                      + version
                      + "' of "
                      + name
                      + " is not "
                      + CHANGELOG_VERSION.pattern());
            }
          }
        });
    return List.copyOf(problems);
  }
}
