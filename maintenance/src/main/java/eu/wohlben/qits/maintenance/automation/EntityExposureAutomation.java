package eu.wohlben.qits.maintenance.automation;

import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.githost.FileLookup;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * <b>Entity exposure map</b> (qits-1958): regenerate a Java service's entity exposure map — {@code
 * docs/entities.json}, qits-1945's rendering of which {@code @Entity} reaches which JAX-RS
 * operation through which DTO — on every fold of every repository that declares one.
 *
 * <p><b>Why it exists.</b> Each adopting service keeps an ArchUnit test, {@code
 * EntityExposureTest}, whose {@code map} rule compares the rendered map with the checked-in file.
 * The map is a function of the whole code base, so it goes stale whenever the fold differs from the
 * branch it was generated on: another ticket's entities or DTOs in the same request, a moved {@code
 * main}, a {@code qits-arch-rules} bump that renders it differently. During the qits-1945 rollout
 * every such fold was a red gate an agent cleared by regenerating the file by hand. This kind is
 * that commit, written by the platform: qits-ci's {@code automations/entity-exposure.yml} runs
 * {@code EntityExposureTest} with {@code -Dgolden.update=true} in a checkout of the fold and commits
 * the payload's {@code commitPaths} onto this kind's own branch.
 *
 * <h2>Applicability, read from the fold's {@code release.yml}</h2>
 *
 * <p>It APPLIES when the fold's {@code .config/qits/release.yml} declares {@code
 * contracts.entities} — a non-blank repository-relative path, by convention {@code
 * docs/entities.json}; qits-ci's {@code CiContracts} gives the key its meaning (the map rides the
 * {@code @contracts} bundle). That declaration is the repository's own statement that it keeps a
 * map, and it is the only thing read: never the repository's name or archetype. A fold with no
 * {@code release.yml}, one that is not a YAML mapping, or one declaring no {@code
 * contracts.entities} is NOT_APPLICABLE, with the reason; so is a path qits-ci's postlude would
 * refuse to stage (absolute, upward, or outside its plain path charset). A git host that does not
 * answer makes it UNKNOWN, exactly as every other kind reads a fold. The cost is one blob read, the
 * same one {@link QaChecks} makes, and the {@link FoldReader} keeps it for the whole settle.
 *
 * <p><b>What it commits</b> is exactly the declared path, as a plain pathspec — the postlude accepts
 * only plain paths and {@code :(glob)}, and a plain path matches the file itself. The static {@link
 * #committablePaths()} the catalogue renders is the convention, {@value #CONVENTION}.
 *
 * <h2>The carry-over invariant</h2>
 *
 * <p>The map is this kind's OUTPUT and never its input — nor any other kind's. In update mode
 * {@code EntityExposure.verify} renders the map from the compiled classes and overwrites the file
 * without reading it, so the re-fold its own commit causes changes none of its {@link
 * #inputPaths()} (the sources, the poms and {@code release.yml}) and is carried with no second run,
 * the same shape {@link EntityDiagramAutomation} keeps for {@code docs/database/**}.
 *
 * <h2>The rule stays a gate</h2>
 *
 * <p>Only the FILE is regenerated. The test class's other rules — {@code EXPOSED}, {@code GAPS} and
 * {@code MARKERS} — judge the code, not the map, and still fail in update mode on an unmarked
 * entity: the run fails and nothing is committed, so an exposure nobody accounted for never ships
 * as a regenerated map. Nor does QA check this kind's output by {@code check-outputs:} ({@link
 * #checkedByQa()} is false): the gate's own run of {@code EntityExposureTest} — {@code map} compares
 * instead of writing — already fails a stale map as a test failure, as for {@code
 * golden-recordings-maven}, so the kind runs before QA rather than being DEFERRED to it.
 */
@ApplicationScoped
public class EntityExposureAutomation implements ReleaseRequestAutomation {

  public static final String KIND = "entity-exposure";

  /** Where the map lives when a repository follows the convention. */
  static final String CONVENTION = "docs/entities.json";

  private static final List<String> PATHS = List.of(CONVENTION);

  private static final List<String> INPUTS =
      List.of(":(glob)**/*.java", ":(glob)**/pom.xml", QaChecks.RELEASE_YML);

  /** qits-ci's postlude charset for a commit path, minus its wildcards: a plain file path. */
  private static final String PLAIN_PATH = "[0-9A-Za-z._/-]+";

  @Override
  public String kind() {
    return KIND;
  }

  @Override
  public String label() {
    return "Entity exposure map";
  }

  @Override
  public Applicability applicability(AutomationSubject subject) {
    FileLookup file = subject.fold().file(QaChecks.RELEASE_YML);
    return switch (file.status()) {
      case FOUND -> {
        Declared declared = declared(file.content());
        yield declared.path() != null
            ? Applicability.applies()
            : Applicability.notApplicable(declared.reason());
      }
      case ABSENT ->
          Applicability.notApplicable(
              "no " + QaChecks.RELEASE_YML + ", so no contracts.entities declares a map");
      default -> Applicability.unknown(unread(file));
    };
  }

  @Override
  public String pipeline() {
    return CiClient.AUTOMATION_EVENT_NAME;
  }

  /** The convention: what the catalogue renders, for every repository at once. */
  @Override
  public List<String> committablePaths() {
    return PATHS;
  }

  /** Exactly the path the fold's {@code release.yml} declares; the convention when it names none. */
  @Override
  public List<String> committablePaths(AutomationSubject subject) {
    FileLookup file = subject.fold().file(QaChecks.RELEASE_YML);
    if (file.status() == FileLookup.Status.FOUND) {
      String path = declared(file.content()).path();
      if (path != null) {
        return List.of(path);
      }
    }
    return PATHS;
  }

  @Override
  public Target target() {
    return Target.OWN_BRANCH;
  }

  /**
   * Rendered from compiled classes, so from whatever the SOURCE kinds left the poms at — a {@code
   * qits-arch-rules} bump regenerates the map in the same request.
   */
  @Override
  public Stage stage() {
    return Stage.DERIVED;
  }

  /** The gate's own {@code EntityExposureTest} fails a stale map: no {@code check-outputs:}. */
  @Override
  public boolean checkedByQa() {
    return false;
  }

  /** A regenerated map lands in the fold unreviewed (epic qits-1038), as the entity diagram does. */
  @Override
  public Set<String> approvalRoles() {
    return Set.of("agent", "person");
  }

  /**
   * What the run reads: the sources it compiles, the poms that decide what it compiles against, and
   * the {@code release.yml} applicability and the committed path are read from. Never the map.
   */
  @Override
  public List<String> inputPaths() {
    return INPUTS;
  }

  /** The declared path, or null and why not. */
  private record Declared(String path, String reason) {}

  /** {@code contracts.entities} from a {@code release.yml}, parsed as {@link QaChecks} parses it. */
  static Declared declared(String releaseYml) {
    Object document;
    try {
      LoaderOptions options = new LoaderOptions();
      options.setAllowDuplicateKeys(false);
      document = new Yaml(new SafeConstructor(options)).load(releaseYml);
    } catch (RuntimeException e) {
      return new Declared(null, QaChecks.RELEASE_YML + " is not valid yaml");
    }
    if (!(document instanceof Map<?, ?> slots)
        || !(slots.get("contracts") instanceof Map<?, ?> contracts)
        || !contracts.containsKey("entities")) {
      return new Declared(
          null, QaChecks.RELEASE_YML + " declares no contracts.entities: the repository keeps no"
              + " entity exposure map");
    }
    if (!(contracts.get("entities") instanceof String value) || value.isBlank()) {
      return new Declared(
          null, QaChecks.RELEASE_YML + "'s contracts.entities is not a non-blank path");
    }
    String path = value.trim();
    if (path.startsWith("/")
        || path.startsWith("-")
        || path.contains("..")
        || !path.matches(PLAIN_PATH)) {
      return new Declared(
          null, QaChecks.RELEASE_YML + "'s contracts.entities is not a plain repository-relative"
              + " path: " + path);
    }
    return new Declared(path, null);
  }

  private static String unread(FileLookup lookup) {
    return lookup.message() == null
        ? QaChecks.RELEASE_YML + " could not be read at the fold (" + lookup.status() + ")"
        : lookup.message();
  }
}
