package eu.wohlben.qits.maintenance.automation;

import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.config.MaintenanceConfig;
import eu.wohlben.qits.maintenance.entity.MtLatest;
import eu.wohlben.qits.maintenance.entity.MtPin;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.githost.FileLookup;
import eu.wohlben.qits.maintenance.manifest.GroupConfig;
import eu.wohlben.qits.maintenance.manifest.ManifestScanner;
import eu.wohlben.qits.maintenance.manifest.ParsedPin;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.model.RepositoryArchetype;
import eu.wohlben.qits.maintenance.pending.Change;
import eu.wohlben.qits.maintenance.pending.PendingChanges;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * <b>Dependency bump</b> (ticket qits-1133, decision D2): move a request's platform-internal pins to
 * their newest release INSIDE the request, before its QA run, instead of on a separate
 * {@code maintenance/<group>} train.
 *
 * <h2>Which pins</h2>
 *
 * <p><b>Platform-internal only, in every request</b> (user decision 2026-10-09): the platform's own
 * maven artifacts, npm packages, images and sibling gitlinks — what {@link
 * MaintenanceConfig#kindOf(ParsedPin)} calls INTERNAL. Third-party pins are never bumped here. A
 * wrapper's gitlinks belong to {@link EstatePinsAutomation}, so this kind drops GITLINK pins when the
 * archetype is {@code PROJECT}. A dependency named under {@code hold:} in the fold's {@code
 * .config/qits/maintenance.yml} is left alone: the escape for an upstream release that breaks the
 * repository.
 *
 * <h2>Applies, plans, writes</h2>
 *
 * <ul>
 *   <li><b>Applies</b> when the fold declares at least one platform-internal pin ({@link
 *       ManifestScanner#pinsAt}, one read per fold, cached). An unreadable fold is UNKNOWN.
 *   <li><b>Plans</b> the pins AT THE FOLD against {@code mt_latest}, through {@link
 *       PendingChanges#newerVersion} (same order and prerelease rules as every bump). Nothing newer:
 *       FRESH, no run.
 *   <li><b>Otherwise one run</b> of the bump pipeline ({@code MaintenanceBump}, group {@code
 *       dependencies}) that rebuilds {@code maintenance/automations/dependency-bump/<rr>} as ONE
 *       commit on main. The changes are measured against main, so pins an earlier run already moved
 *       are in the payload again and survive the rebuild. The branch head travels as {@code
 *       replaceHead}; the step pushes under {@code --force-with-lease} and refuses a branch with a
 *       commit it did not write (exit 42). A green run that moved the branch joins it to the
 *       request at priority LOWEST.
 * </ul>
 *
 * <p><b>Payload</b>: {@code repository}, {@code group: dependencies}, {@code branch}, {@code
 * baseRef: main}, {@code changes}, {@code kind: dependency-bump}, {@code requestId}, {@code
 * foldSha}, {@code workItem} when there is one, {@code replaceHead} when the branch exists.
 */
@ApplicationScoped
public class DependencyBumpAutomation implements ReleaseRequestAutomation {

  public static final String KIND = "dependency-bump";

  /** The switch (release R1): off until the cutover. */
  public static final String SWITCH = "qits.maintenance.automations.dependency-bump.enabled";

  /** The lock npm rewrites beside a {@code package.json}. */
  static final String PACKAGE_LOCK = ManifestScanner.PACKAGE_LOCK;

  /** How many folds' reads are kept. Small: a fold is asked about a few times in a minute. */
  private static final int CACHE_SIZE = 64;

  @Inject ManifestScanner manifests;

  @Inject MaintenanceConfig config;

  @Inject MaintenanceStore store;

  @ConfigProperty(name = SWITCH, defaultValue = "false")
  boolean enabled;

  /** One fold's manifests, read once: {@code project/name@sha}. */
  private final Map<String, FoldPins> cache = new ConcurrentHashMap<>();

  /**
   * What one fold declares, as this kind reads it.
   *
   * @param status FOUND, or why nothing could be read
   * @param message the sentence for a read that failed
   * @param all every pin the scan found
   * @param internal the pins this kind may move: platform-internal, actionable, gitlinks dropped on
   *     a wrapper
   */
  record FoldPins(
      FileLookup.Status status, String message, List<ParsedPin> all, List<ParsedPin> internal) {

    boolean found() {
      return status == FileLookup.Status.FOUND;
    }
  }

  @Override
  public String kind() {
    return KIND;
  }

  @Override
  public String label() {
    return "Dependency bump";
  }

  @Override
  public boolean enabled() {
    return enabled;
  }

  @Override
  public Stage stage() {
    return Stage.SOURCE;
  }

  /** The bump pipeline, not the shared core: its step owns the one-commit rebuild (qits-1133). */
  @Override
  public String pipeline() {
    return CiClient.EVENT_NAME;
  }

  /** The payload's group, and the commit subject's scope when the request names no work item. */
  @Override
  public String bumpGroup() {
    return GroupConfig.DEFAULT_GROUP;
  }

  @Override
  public Target target() {
    return Target.OWN_BRANCH;
  }

  @Override
  public String joinPriority() {
    return "LOWEST";
  }

  @Override
  public Applicability applicability(AutomationSubject subject) {
    FoldPins pins = read(subject.repository(), subject.foldSha());
    if (!pins.found()) {
      return Applicability.unknown("the manifests at the fold could not be read: " + pins.message());
    }
    if (pins.all().isEmpty()) {
      return Applicability.notApplicable("no manifest the scan knows");
    }
    if (pins.internal().isEmpty()) {
      return Applicability.notApplicable(
          wrapper(subject.repository())
              ? "no platform dependency outside its gitlinks, which estate pins owns"
              : "no platform dependency: every pin is third-party or the repository's own");
    }
    return Applicability.applies();
  }

  /** None that hold for every repository: the paths are the fold's manifests. */
  @Override
  public List<String> committablePaths() {
    return List.of();
  }

  /**
   * Every manifest the fold's pins sit in (gitlink paths included), plus the lock beside each
   * {@code package.json}. An unreadable fold is no paths: nothing carries on them.
   */
  @Override
  public List<String> committablePaths(AutomationSubject subject) {
    FoldPins pins = read(subject.repository(), subject.foldSha());
    if (!pins.found()) {
      return List.of();
    }
    boolean wrapper = wrapper(subject.repository());
    List<ParsedPin> mine = new ArrayList<>();
    for (ParsedPin pin : pins.all()) {
      if (!(wrapper && pin.ecosystem() == Ecosystem.GITLINK)) {
        mine.add(pin);
      }
    }
    return paths(mine);
  }

  /** The branch head the step rebuilds over, when there is a branch. */
  @Override
  public Map<String, String> dispatchExtras(AutomationSubject subject, String startHead) {
    return startHead == null || startHead.isBlank() ? Map.of() : Map.of("replaceHead", startHead);
  }

  @Override
  public Plan plan(AutomationSubject subject) {
    MtRepository repository = subject.repository();
    FoldPins atFold = read(repository, subject.foldSha());
    if (!atFold.found()) {
      return Plan.unknown("the manifests at the fold could not be read: " + atFold.message());
    }
    FileLookup file = subject.fold().file(GroupConfig.PATH);
    GroupConfig.Parsed settings;
    switch (file.status()) {
      case FOUND -> settings = GroupConfig.parse(file.content());
      case ABSENT -> settings = GroupConfig.fallback();
      default -> {
        return Plan.unknown(
            GroupConfig.PATH + " could not be read at the fold: "
                + (file.message() == null ? file.status().name() : file.message()));
      }
    }
    if (!settings.ok()) {
      return Plan.unknown(settings.error() + " — fix it on the request's branch");
    }
    Map<String, MtLatest> latest = PendingChanges.index(store.allLatest());

    List<String> behind = new ArrayList<>();
    for (ParsedPin pin : atFold.internal()) {
      if (!settings.holds(pin.name()) && newer(pin, latest).isPresent()) {
        behind.add(pin.name());
      }
    }
    String heldNote =
        settings.held().isEmpty() ? "" : " (held: " + String.join(", ", settings.held()) + ")";
    if (behind.isEmpty()) {
      return Plan.fresh("every platform dependency is at its newest release" + heldNote);
    }

    // THE CHANGES ARE MEASURED AGAINST MAIN, the base the one commit is cut from, never against
    // the fold: the fold already carries the previous bump commit, and a rebuild that left those
    // pins out would undo them.
    String base =
        repository.mainBranch == null || repository.mainBranch.isBlank()
            ? "main"
            : repository.mainBranch;
    FoldPins atBase = read(repository, base, false);
    if (!atBase.found()) {
      return Plan.unknown(
          "the manifests at " + base + " could not be read: " + atBase.message());
    }
    List<Change> changes = new ArrayList<>();
    for (ParsedPin pin : atBase.internal()) {
      if (settings.holds(pin.name())) {
        continue;
      }
      Optional<String> to = newer(pin, latest);
      if (to.isPresent()) {
        changes.add(
            new Change(
                pin.ecosystem().wireName(),
                pin.manifestPath(),
                pin.name(),
                pin.version(),
                to.get(),
                pin.location()));
      }
    }
    if (changes.isEmpty()) {
      // Behind only on lines the request's own branches added: the base has nothing to move.
      return Plan.fresh(
          "behind only on pins the request's own branches declare ("
              + String.join(", ", behind) + "); the bump writes from " + base + heldNote);
    }
    return Plan.runs(List.of(new Plan.Run(null, changes, Map.of())));
  }

  // --- reads ------------------------------------------------------------------------------------

  /** One fold's pins, cached by sha. */
  FoldPins read(MtRepository repository, String sha) {
    return read(repository, sha, true);
  }

  private FoldPins read(MtRepository repository, String revision, boolean cacheable) {
    String key = repository.project + "/" + repository.name + "@" + revision;
    if (cacheable) {
      FoldPins hit = cache.get(key);
      if (hit != null) {
        return hit;
      }
    }
    ManifestScanner.Pins read = manifests.pinsAt(repository.project, repository.name, revision);
    FoldPins answer;
    if (read.status() != FileLookup.Status.FOUND) {
      answer =
          new FoldPins(
              read.status(),
              read.message() == null ? read.status().name() : read.message(),
              List.of(),
              List.of());
    } else {
      boolean wrapper = wrapper(repository);
      List<ParsedPin> internal = new ArrayList<>();
      for (ParsedPin pin : read.pins()) {
        if (wrapper && pin.ecosystem() == Ecosystem.GITLINK) {
          continue;
        }
        if (config.kindOf(pin) == PinKind.INTERNAL) {
          internal.add(pin);
        }
      }
      answer =
          new FoldPins(FileLookup.Status.FOUND, null, List.copyOf(read.pins()), List.copyOf(internal));
    }
    // Only a found read is kept: a failed one is asked again on the next fold ask.
    if (cacheable && answer.found()) {
      if (cache.size() >= CACHE_SIZE) {
        cache.clear();
      }
      cache.put(key, answer);
    }
    return answer;
  }

  /** The newer version of one pin, by the rules every bump uses. */
  private static Optional<String> newer(ParsedPin pin, Map<String, MtLatest> latest) {
    MtPin row = new MtPin();
    row.ecosystem = pin.ecosystem().wireName();
    row.name = pin.name();
    row.version = pin.version();
    row.range = pin.range();
    row.manifestPath = pin.manifestPath();
    row.location = pin.location();
    row.kind = PinKind.INTERNAL.name();
    return PendingChanges.newerVersion(row, latest);
  }

  /** The manifests of these pins, in order, with the lock beside each {@code package.json}. */
  static List<String> paths(List<ParsedPin> pins) {
    Set<String> out = new LinkedHashSet<>();
    for (ParsedPin pin : pins) {
      String path = pin.manifestPath();
      if (path == null || path.isBlank()) {
        continue;
      }
      out.add(path);
      if (pin.ecosystem() == Ecosystem.NPM && path.endsWith("package.json")) {
        out.add(path.substring(0, path.length() - "package.json".length()) + PACKAGE_LOCK);
      }
    }
    return List.copyOf(out);
  }

  private static boolean wrapper(MtRepository repository) {
    return RepositoryArchetype.of(repository.archetype)
        .map(archetype -> archetype == RepositoryArchetype.PROJECT)
        .orElse(false);
  }
}
