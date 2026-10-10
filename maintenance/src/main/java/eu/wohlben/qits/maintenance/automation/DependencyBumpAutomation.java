package eu.wohlben.qits.maintenance.automation;

import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.config.MaintenanceConfig;
import eu.wohlben.qits.maintenance.entity.MtLatest;
import eu.wohlben.qits.maintenance.entity.MtPin;
import eu.wohlben.qits.maintenance.entity.MtReleaseRequest;
import eu.wohlben.qits.maintenance.githost.FileLookup;
import eu.wohlben.qits.maintenance.manifest.GitmodulesParser;
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
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * <b>Dependency bump</b> (qits-1133): every pin a release request's FOLD carries that is behind its
 * latest, written as ONE commit onto {@code maintenance/automations/dependency-bump/<request>} and
 * joined to the request at {@code LOWEST} — before the request's first build, rather than as a
 * separate {@code maintenance/<group>} branch whose every push re-folded the request and restarted
 * its QA.
 *
 * <h2>Applies, plans, writes</h2>
 *
 * <ul>
 *   <li><b>Applies</b> when the inventory knows a pin of the repository a bump could move — an
 *       INTERNAL or EXTERNAL one, and in a wrapper not a gitlink (below). One store read, no git
 *       host.
 *   <li><b>Plans AT THE FOLD</b>, never at main: the fold's manifests are read through the scan's
 *       own discovery ({@link ManifestScanner#pinsAt}, the same parsers and the same {@code ignore:}),
 *       each pin is judged by the pending rule ({@link PendingChanges#newerVersion}) against {@code
 *       mt_latest}, and what is left after the fold's own {@code hold:} list is the change list.
 *       Nothing behind is FRESH with no run — the loop's terminator, as estate pins' is: the
 *       re-fold this kind's own commit causes is planned again and finds nothing.
 *   <li><b>Writes</b> through the shared core's {@code automations/dependency-bump.yml}: the payload
 *       carries {@code changes} in exactly the {@code MaintenanceBump} entry shape ({@link Change})
 *       and {@code commitPaths}, the manifests those changes touch.
 *   <li><b>Proves its changelogs before anything is sent</b> (qits-893): its commit is a bump commit
 *       like any other, so at dispatch {@link AutomationService} resolves each change's range
 *       through {@link eu.wohlben.qits.maintenance.bump.changelog.ChangelogRanges} and each change
 *       that has one carries {@code "changelog": {"repository": "…", "versions": ["…"]}}, spelled by
 *       {@link CiClient#changes} exactly as the {@code MaintenanceBump} trigger spells it — an
 *       external change, or one whose repository predates changelogs, carries no key. A missing
 *       changelog FAILS the run with the problems joined "; " and triggers nothing; a docs store
 *       that could not be read leaves it REQUESTED for the sweep.
 * </ul>
 *
 * <h2>Whose upgrades</h2>
 *
 * <p><b>Only the platform's own releases are planned, with one exception</b> — INTERNAL pins,
 * decided by the same name rule the scan stores ({@link MaintenanceConfig#kindOf(ParsedPin)}).
 * Somebody else's framework major in a person's request would be an opinion pushed into their
 * release, and a group bump's request keeps the pre-1133 rule that external upgrades are a person's
 * press. The exception is the MAIN-ONLY request the dispatcher opens on the upstream path ({@code
 * mt_release_request.purpose = MAIN_ONLY}): it exists to carry upgrades and plans EXTERNAL ones
 * too.
 *
 * <h2>Who owns which path</h2>
 *
 * <p><b>In a wrapper the gitlinks are {@code estate-pins}'</b>, and this kind never plans, applies to
 * or claims one there; everywhere else a gitlink is a pin like any other. {@link
 * #committablePaths(AutomationSubject)} is the manifests and, outside a wrapper, the gitlink paths
 * the fold's {@code .gitmodules} declares — which is what keeps the two kinds disjoint, and what the
 * engine checks again at run time before a plan is opened.
 */
@ApplicationScoped
public class DependencyBumpAutomation implements ReleaseRequestAutomation {

  public static final String KIND = "dependency-bump";

  /**
   * The switch, ON since the cutover (MT-5, qits-1133 R2), when group bumps were retired and this
   * kind became the only writer of a pin — and since R5, which removed the upstream switch, the one
   * kill switch left for the whole bump path. Off — an emergency — the kind is not listed, planned
   * or started, the upstream hook re-plans nothing and the dispatcher opens no main-only request.
   */
  public static final String SWITCH = "qits.maintenance.automations.dependency-bump.enabled";

  /** The manifests the four parsers edit: every pom, the npm pair, both Dockerfile spellings. */
  public static final List<String> MANIFESTS =
      List.of(
          ":(glob)**/pom.xml",
          ":(glob)**/package.json",
          ":(glob)**/" + ManifestScanner.PACKAGE_LOCK,
          ":(glob)**/Dockerfile",
          ":(glob)**/Dockerfile.*",
          ":(glob)**/*.Dockerfile");

  @Inject MaintenanceStore store;

  @Inject MaintenanceConfig config;

  @Inject ManifestScanner scanner;

  @ConfigProperty(name = SWITCH, defaultValue = "true")
  boolean enabled;

  @Override
  public boolean enabled() {
    return enabled;
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
  public Stage stage() {
    return Stage.SOURCE;
  }

  @Override
  public Applicability applicability(AutomationSubject subject) {
    boolean wrapper = wrapper(subject);
    for (MtPin pin : store.pins(subject.repository().name)) {
      if (!PendingChanges.kindOf(pin).actionable()) {
        continue;
      }
      if (wrapper && Ecosystem.GITLINK.wireName().equals(pin.ecosystem)) {
        continue;
      }
      return Applicability.applies();
    }
    return Applicability.notApplicable(
        "the inventory knows no pin of " + subject.repository().name + " a bump could move"
            + (wrapper ? " (a wrapper's gitlinks are estate-pins')" : ""));
  }

  @Override
  public String pipeline() {
    return CiClient.AUTOMATION_EVENT_NAME;
  }

  @Override
  public List<String> committablePaths() {
    return MANIFESTS;
  }

  /** The manifests, and outside a wrapper the gitlink paths the fold declares. */
  @Override
  public List<String> committablePaths(AutomationSubject subject) {
    if (wrapper(subject)) {
      return MANIFESTS;
    }
    FileLookup declaration = subject.fold().file(GitmodulesParser.PATH);
    if (!declaration.found()) {
      return MANIFESTS;
    }
    List<String> paths = new ArrayList<>(MANIFESTS);
    for (GitmodulesParser.Submodule module : GitmodulesParser.parse(declaration.content())) {
      if (module.path() != null && !module.path().isBlank() && !paths.contains(module.path())) {
        paths.add(module.path());
      }
    }
    return List.copyOf(paths);
  }

  /**
   * What the plan reads: its own manifests, the {@code .gitmodules} that names the gitlinks and the
   * config that says what is ignored and held. A fold that touched none of them carries; one that
   * did — this kind's own commit included — is planned again, and the plan is what ends the loop.
   */
  @Override
  public List<String> inputPaths(AutomationSubject subject) {
    List<String> paths = new ArrayList<>(committablePaths(subject));
    paths.add(GitmodulesParser.PATH);
    paths.add(GroupConfig.PATH);
    return List.copyOf(paths);
  }

  @Override
  public List<String> inputPaths() {
    List<String> paths = new ArrayList<>(MANIFESTS);
    paths.add(GitmodulesParser.PATH);
    paths.add(GroupConfig.PATH);
    return List.copyOf(paths);
  }

  @Override
  public Target target() {
    return Target.OWN_BRANCH;
  }

  @Override
  public Plan plan(AutomationSubject subject) {
    FoldReader fold = subject.fold();
    FileLookup file = fold.file(GroupConfig.PATH);
    GroupConfig.Parsed declared =
        switch (file.status()) {
          case FOUND -> GroupConfig.parse(file.content());
          case ABSENT -> GroupConfig.fallback();
          default -> null;
        };
    if (declared == null) {
      return Plan.unknown(
          GroupConfig.PATH + " could not be read at the fold: "
              + (file.message() == null ? file.status().name() : file.message()));
    }
    if (!declared.ok()) {
      // The scan's own rule: an invalid file is a CONFIG_ERROR and nothing is bumped for it. FRESH
      // rather than FAILED, because a person's request must not hold on a file that is theirs to fix.
      return Plan.fresh(declared.error() + " — nothing is bumped until it parses");
    }

    ManifestScanner.Pins read =
        scanner.pinsAt(subject.repository().project, subject.repository().name, fold.sha());
    if (read.status() != FileLookup.Status.FOUND) {
      return Plan.unknown(
          "the manifests at the fold could not be read: "
              + (read.message() == null ? read.status().name() : read.message()));
    }

    boolean wrapper = wrapper(subject);
    boolean external = plansExternal(subject.requestId());
    Map<String, MtLatest> latest = PendingChanges.index(store.allLatest());
    List<Change> changes = new ArrayList<>();
    List<String> held = new ArrayList<>();
    for (ParsedPin pin : read.pins()) {
      if (wrapper && pin.ecosystem() == Ecosystem.GITLINK) {
        continue;
      }
      PinKind kind = config.kindOf(pin);
      if (!kind.actionable() || (!external && kind != PinKind.INTERNAL)) {
        continue;
      }
      MtPin row = asRow(pin, kind);
      Optional<String> newer = PendingChanges.newerVersion(row, latest);
      if (newer.isEmpty()) {
        continue;
      }
      if (declared.holds(pin.name())) {
        held.add(pin.name());
        continue;
      }
      changes.add(
          new Change(
              row.ecosystem, row.manifestPath, row.name, row.version, newer.get(), row.location));
    }
    if (changes.isEmpty()) {
      return Plan.fresh(
          "every " + (external ? "" : "internal ") + "pin at the fold names its latest"
              + (held.isEmpty() ? "" : "; held by " + GroupConfig.PATH + ": " + held));
    }
    return Plan.runs(
        List.of(new Plan.Run(null, changes, Map.of(COMMIT_PATHS, commitPaths(changes)))));
  }

  /** The payload field naming the files a run may stage, as the engine and qits-ci both read it. */
  public static final String COMMIT_PATHS = "commitPaths";

  /**
   * The files a change list touches: each change's manifest, and beside an npm manifest its lock,
   * which the step rewrites with it. A gitlink's "manifest" is its path, which is what is staged.
   */
  static List<String> commitPaths(List<Change> changes) {
    Set<String> paths = new LinkedHashSet<>();
    for (Change change : changes) {
      paths.add(change.manifestPath());
      if (Ecosystem.NPM.wireName().equals(change.ecosystem())) {
        int slash = change.manifestPath().lastIndexOf('/');
        String directory = slash < 0 ? "" : change.manifestPath().substring(0, slash + 1);
        paths.add(directory + ManifestScanner.PACKAGE_LOCK);
      }
    }
    return List.copyOf(paths);
  }

  /**
   * Whether this request may carry EXTERNAL upgrades: only a MAIN-ONLY request the dispatcher's
   * upstream path opened. A person's request and a legacy group bump's ({@code maintenance/<group>})
   * get INTERNAL pins only.
   */
  boolean plansExternal(String requestId) {
    return store
        .releaseRequest(requestId)
        .filter(memo -> memo.opened && MtReleaseRequest.MAIN_ONLY.equals(memo.purpose))
        .isPresent();
  }

  /** A pin read at the fold, in the shape the pending rule judges a stored one in. Never persisted. */
  private static MtPin asRow(ParsedPin pin, PinKind kind) {
    MtPin row = new MtPin();
    row.ecosystem = pin.ecosystem().wireName();
    row.manifestPath = pin.manifestPath();
    row.name = pin.name();
    row.version = pin.version();
    row.range = pin.range();
    row.kind = kind.name();
    row.location = pin.location();
    return row;
  }

  private static boolean wrapper(AutomationSubject subject) {
    return RepositoryArchetype.of(subject.repository().archetype)
        .filter(archetype -> archetype == RepositoryArchetype.PROJECT)
        .isPresent();
  }
}
