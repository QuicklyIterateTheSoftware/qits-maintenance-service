package eu.wohlben.qits.maintenance.automation;

import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.githost.FileLookup;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Set;

/**
 * <b>CLI tapes</b> (epic qits-881): play a repository's VHS tapes in the CI image qits-ci's {@code
 * cli-tapes} kind renders in, and join the text goldens and GIFs that changed to the release
 * request.
 *
 * <h2>Applies when, and why one file settles it</h2>
 *
 * <p>The fold carries {@code tapes/prepare.sh} — the hook a repository writes to put its own CLI on
 * PATH and seed whatever state its tapes need before qits-ci's generator plays {@code tapes/*.tape}.
 * That one file is the convention, the same way {@code src/testing/browser/renderer.txt} marks the
 * screenshot one: its presence is the repository opting into VHS tapes, and a repository with no
 * tapes to play has nothing for this kind to render.
 *
 * <p>A fold that does not carry it is NOT_APPLICABLE; a git host that could not be asked is UNKNOWN,
 * so the request holds rather than shipping a stale recording on the strength of an outage.
 *
 * <h2>What it may commit, and the invariant it keeps</h2>
 *
 * <p>{@code tapes/golden/**} — the text goldens, which are what QA compares a tape's recorded output
 * against — and {@code tapes/recordings/**}, the GIFs. The GIFs are the expensive half: the generator
 * re-renders one only when its golden changed or the GIF is missing, never on every fold, while the
 * text goldens are played and compared every time. Nothing any other kind reads is in either path —
 * qits-ci's postlude stages the payload's {@code commitPaths} and only those.
 */
@ApplicationScoped
public class CliTapesAutomation implements ReleaseRequestAutomation {

  public static final String KIND = "cli-tapes";

  /** The hook that marks the convention: a repository's own setup for its tapes. */
  static final String PREPARE_SCRIPT = "tapes/prepare.sh";

  private static final List<String> PATHS =
      List.of(":(glob)tapes/golden/**", ":(glob)tapes/recordings/**");

  @Override
  public String kind() {
    return KIND;
  }

  @Override
  public String label() {
    return "CLI tapes";
  }

  @Override
  public Applicability applicability(AutomationSubject subject) {
    FileLookup prepare = subject.fold().file(PREPARE_SCRIPT);
    return switch (prepare.status()) {
      case FOUND -> Applicability.applies();
      case ABSENT ->
          Applicability.notApplicable(
              "the fold has no " + PREPARE_SCRIPT + ", so the repository has no CLI tapes");
      default -> Applicability.unknown(unread(PREPARE_SCRIPT, prepare));
    };
  }

  @Override
  public String pipeline() {
    return CiClient.AUTOMATION_EVENT_NAME;
  }

  @Override
  public List<String> committablePaths() {
    return PATHS;
  }

  @Override
  public Target target() {
    return Target.OWN_BRANCH;
  }

  /** Rendered from what the fold builds — after the bumps, never beside them. */
  @Override
  public Stage stage() {
    return Stage.DERIVED;
  }

  /** QA runs the same generator and fails on a stale golden (qits-1180). */
  @Override
  public boolean checkedByQa() {
    return true;
  }

  /** A generated tape golden or GIF lands in the fold unreviewed; an agent or a person approves it. */
  @Override
  public Set<String> approvalRoles() {
    return Set.of("agent", "person");
  }

  private static String unread(String path, FileLookup lookup) {
    return lookup.message() == null
        ? path + " could not be read at the fold (" + lookup.status() + ")"
        : lookup.message();
  }
}
