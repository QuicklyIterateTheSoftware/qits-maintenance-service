package eu.wohlben.qits.maintenance.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.githost.FileLookup;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * <b>Screenshot baselines</b>: render a release request's screenshot tests in the CI image the QA
 * step compares in, and join the reference images that changed to the request.
 *
 * <p>Before qits-978 this was the manual-only BASELINES mode — a button somebody pressed after QA's
 * {@code test:browser} had already failed on a stale reference. Now it runs on every fold of every
 * repository whose screenshots follow the one storage convention the platform knows, and the request
 * holds until it is fresh, so a screenshot mismatch can no longer fail QA (decision 8 of campaign
 * qits-756).
 *
 * <h2>Applies when, and why those two reads</h2>
 *
 * <p>The fold's {@code package.json} declares {@code scripts["test:browser"]} AND the tree holds
 * {@code src/testing/browser/renderer.txt} — the record @qits/angular's vitest-browser setup writes
 * beside its references. The script alone is not enough: qits-integrations-angular-jslib has it and
 * no references at all, and running a browser for it on every fold would spend a CI slot to learn
 * nothing. Applicability is the platform's decision from the repository, never an opt-in file; a
 * repository's FIRST references come from the re-run door, which skips this test.
 *
 * <p>A file the fold does not carry is NOT_APPLICABLE; a git host that could not be asked is UNKNOWN,
 * so the request holds rather than shipping a stale reference on the strength of an outage.
 *
 * <h2>What it may commit, and the invariant it keeps</h2>
 *
 * <p>{@code **}{@code /__screenshots__/**} and {@code **}{@code /testing/browser/renderer.txt}, and
 * nothing else — qits-ci's postlude stages the payload's {@code commitPaths} and only those. Nothing
 * any other kind reads is in either: the renderer record and the references are this kind's output
 * and no other kind's input, which is what lets the re-fold its own join causes be carried rather
 * than run again.
 */
@ApplicationScoped
public class ScreenshotBaselinesAutomation implements ReleaseRequestAutomation {

  public static final String KIND = "screenshot-baselines";

  /** The script the run calls, in the fold's {@code package.json}. */
  static final String SCRIPT = "test:browser";

  /** The @qits/angular vitest-browser record that marks the convention. */
  static final String RENDERER = "src/testing/browser/renderer.txt";

  static final String PACKAGE_JSON = "package.json";

  private static final List<String> PATHS =
      List.of(":(glob)**/__screenshots__/**", ":(glob)**/testing/browser/renderer.txt");

  private static final ObjectMapper JSON = new ObjectMapper();

  @Override
  public String kind() {
    return KIND;
  }

  @Override
  public String label() {
    return "Screenshot baselines";
  }

  @Override
  public Applicability applicability(AutomationSubject subject) {
    FileLookup manifest = subject.fold().file(PACKAGE_JSON);
    switch (manifest.status()) {
      case FOUND -> {}
      case ABSENT -> {
        return Applicability.notApplicable("the fold carries no " + PACKAGE_JSON);
      }
      default -> {
        return Applicability.unknown(unread(PACKAGE_JSON, manifest));
      }
    }
    if (!declaresScript(manifest.content())) {
      return Applicability.notApplicable(PACKAGE_JSON + " declares no " + SCRIPT + " script");
    }
    FileLookup renderer = subject.fold().file(RENDERER);
    return switch (renderer.status()) {
      case FOUND -> Applicability.applies();
      case ABSENT ->
          Applicability.notApplicable(
              "the fold carries no " + RENDERER + ", so its screenshots follow no known convention");
      default -> Applicability.unknown(unread(RENDERER, renderer));
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

  /** Whether a {@code package.json} declares the script. One that does not parse declares nothing. */
  static boolean declaresScript(String content) {
    try {
      JsonNode root = JSON.readTree(content == null ? "" : content);
      JsonNode script = root == null ? null : root.path("scripts").get(SCRIPT);
      return script != null && script.isTextual() && !script.asText().isBlank();
    } catch (Exception e) {
      return false;
    }
  }

  private static String unread(String path, FileLookup lookup) {
    return lookup.message() == null
        ? path + " could not be read at the fold (" + lookup.status() + ")"
        : lookup.message();
  }
}
