package eu.wohlben.qits.maintenance.manifest;

import eu.wohlben.qits.maintenance.catalog.CatalogEntry;
import eu.wohlben.qits.maintenance.githost.FileLookup;
import eu.wohlben.qits.maintenance.githost.GitHostReader;
import eu.wohlben.qits.maintenance.githost.TreeLookup;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Reads one repository's manifests at one revision and turns them into pins.
 *
 * <p><b>The head sha is resolved ONCE and every file is read at it.</b> A scan that read each
 * manifest at {@code main} would produce an inventory of whatever moved while it ran, and the pins
 * of one repository would not correspond to any commit that ever existed.
 *
 * <p><b>Discovery is the repository ROOT plus the reactor, plus one named directory.</b> The root
 * listing is read, the manifests in it are taken, and a root pom's declared modules are followed
 * for their poms — transitively, because a module may name modules of its own. The one addition is
 * {@code docker/}, which is where several repositories keep the build file the root does not hold.
 * Nothing else is walked: a recursive search would read every vendored file in every repository on
 * the platform for a handful of pins.
 *
 * <p><b>{@code service/src/main/webui} is NOT scanned, and the shape of the discovery is what keeps
 * it out.</b> It is a gitlink to the SPA's own repository, which the catalog lists in its own
 * right; scanning it here would double every pin it holds and attribute the copy to the wrong
 * repository. The module walk refuses such a path outright, so a pom that ever named one still
 * cannot pull it in.
 *
 * <p><b>The gitlink ITSELF is a pin, and that is a different fact from what it contains.</b>
 * {@code .gitmodules} plus the mode-{@code 160000} entry in the tree say which repository is
 * embedded and at which commit — one line this repository owns and can move. Its contents belong to
 * the submodule's own row, which is why the two never meet HERE. The one exception lives outside
 * this class: the npm pins the submodule's lock holds AT THAT COMMIT are what the carrying
 * repository's build installs, so {@code scan/GitlinkNpmPins} reads them through {@link #pinsAt}
 * and stores them for the GC's pin source — never as pins of this repository's.
 *
 * <p><b>A repository may take a whole ecosystem off this scan.</b> {@code ignore:} in
 * {@code .config/qits/maintenance.yml} names ecosystems by their wire name, and one named there is
 * not parsed here at all — no reads, no pins, so nothing to store, group or offer. The motivating
 * case is the qits-qits wrapper, whose forty-seven gitlinks are DELIBERATELY lagging bank markers
 * rather than version pins; see {@link GroupConfig} for the whole of that argument.
 */
@ApplicationScoped
public class ManifestScanner {

  /** The lock beside a root package.json. Without it a manifest has ranges and no versions. */
  public static final String PACKAGE_LOCK = "package-lock.json";

  /** A cap on the reactor walk. A pom that names itself, or a cycle through two modules, would
   * otherwise be an unbounded read against another service. */
  private static final int MAX_POMS = 128;

  private static final Logger LOG = Logger.getLogger(ManifestScanner.class);

  /** The path fragment a gitlink to an embedded SPA sits at on this platform. */
  private static final String EMBEDDED_CLIENT = "src/main/webui";

  /** The one directory below the root that a Dockerfile is looked for in. */
  private static final String DOCKER_DIRECTORY = "docker";

  /** What PomParser writes as the location of a {@code <parent>} pin. */
  private static final String PARENT_LOCATION = "parent:";

  /** A cap on the submodule walk. A wrapper repository declares dozens and nothing declares this
   * many; the bound is what keeps one malformed file from being an unbounded read. */
  private static final int MAX_GITLINKS = 128;

  /** What a gitlink pin's location says: the path the {@code 160000} entry sits at. */
  static final String GITLINK_LOCATION = "gitlink:";

  @Inject GitHostReader gitHost;

  /**
   * What one repository's scan read.
   *
   * @param status the row's status
   * @param headSha the commit every pin was read at, null unless the status is OK or CONFIG_ERROR
   * @param pins every direct pin found, in discovery order — an ignored ecosystem contributes none
   * @param groups the repository's grouping, empty when the status is CONFIG_ERROR
   * @param message the sentence for the row, null when the status is OK
   */
  public record Read(
      RepositoryStatus status,
      String headSha,
      List<ParsedPin> pins,
      List<GroupConfig.Group> groups,
      eu.wohlben.qits.maintenance.model.GroupSource groupSource,
      String message) {}

  /** Reads one repository at its default branch — what every scheduled scan asks for. */
  public Read read(CatalogEntry entry) {
    return read(entry, null);
  }

  /**
   * Reads one repository at {@code revision}, or at its default branch when that is null.
   *
   * <p><b>The override exists so a release can be read as a release.</b> A release on this platform
   * is a TAG, and {@code main} is finalized afterwards — at once for a repository that deploys
   * nothing, after the deployment for one that does. So the manifests a release produced are
   * readable at {@code refs/tags/<version>} the instant the release is announced, and at {@code
   * main} only once something else has happened. A scan keyed on a release that read the branch
   * would record the PREVIOUS release's pins and stamp the row as freshly checked, which is worse
   * than not scanning: nothing afterwards looks stale enough to re-read. Measured live 2026-09-09 on
   * qits-artifacts-frontend — scan 32ms after the release, pins two days old.
   *
   * <p>It is also the more honest read whatever the timing, and {@code adoption/ReleaseLedger} says
   * so for the same reason: a pin read at a tag is release-grade, and a pin read at a branch is
   * whatever happened to be there. The two converge here because {@code main} only ever advances by
   * release — so the override buys the inventory hours of freshness and costs it no accuracy.
   *
   * <p><b>What it does change is what {@code head_sha} names</b>: the commit the pins were read at,
   * which for a tag read is the release's commit rather than the branch's tip. The same commit, once
   * the branch has caught up.
   */
  public Read read(CatalogEntry entry, String revision) {
    String project = entry.project();
    String name = entry.name();
    String branch =
        revision != null && !revision.isBlank()
            ? revision
            : entry.mainBranch() == null || entry.mainBranch().isBlank()
                ? "main"
                : entry.mainBranch();

    TreeLookup root = gitHost.head(project, name, branch);
    switch (root.status()) {
      case GONE ->
          // The catalog names it and the git host does not hold this branch. That is the honest
          // ABSENT: there is nothing to scan and nothing is wrong with this service.
          {
            return absent(name, branch);
          }
      case UNREACHABLE, INVALID -> {
        return new Read(
            RepositoryStatus.UNREACHABLE, null, List.of(), List.of(), null, root.message());
      }
      case ABSENT -> {
        return absent(name, branch);
      }
      default -> {
        // FOUND — carry on below.
      }
    }

    String headSha = root.headSha();

    // THE CONFIG IS READ BEFORE THE MANIFESTS, because it may say not to read some of them at all.
    // An ecosystem the repository named under `ignore` is skipped here rather than filtered later:
    // there is no pin to filter, and the reads that would have found one are not made either. A
    // file that will not parse ignores nothing — the set is empty on the error path — so a broken
    // config still reports whatever the manifests hold, exactly as it did before `ignore` existed.
    GroupConfig.Parsed config = groups(project, name, headSha);

    List<ParsedPin> pins = pins(project, name, headSha, root, config);

    if (!config.ok()) {
      return new Read(
          RepositoryStatus.CONFIG_ERROR, headSha, List.copyOf(pins), List.of(), null, config.error());
    }
    if (!config.ignored().isEmpty()) {
      LOG.debugf(
          "%s asks not to be scanned for %s",
          name, config.ignored().stream().map(Ecosystem::wireName).toList());
    }
    return new Read(
        RepositoryStatus.OK, headSha, List.copyOf(pins), config.groups(), config.source(), null);
  }

  /**
   * What one repository's manifests declared AT ONE REVISION, and nothing else.
   *
   * @param status the tree read's own outcome — FOUND, or why there are no pins. UNREACHABLE is the
   *     caller's to retry; GONE and ABSENT mean the git host does not hold this revision, which for
   *     a tag is as permanent as an answer gets
   * @param sha the commit the revision resolved to, null unless FOUND
   * @param pins every direct pin found at it, in discovery order
   * @param message the sentence, for UNREACHABLE and INVALID
   */
  public record Pins(FileLookup.Status status, String sha, List<ParsedPin> pins, String message) {}

  /**
   * <b>Every pin one repository declared at one revision — the same discovery, none of the
   * verdict.</b>
   *
   * <p>{@link #read(CatalogEntry)} answers a repository's CURRENT state and is what a scan writes
   * the inventory from: a status, a grouping, a sentence for the row. This answers a historical
   * question instead — what did the tree at {@code refs/tags/2026.906.1} declare — and a status
   * column, a branch row and a group have no meaning about a tag nobody can push to. So the
   * discovery is shared to the line (one head resolution, then the four parsers, then the
   * per-line dedupe) and everything the scan wraps around it is not.
   *
   * <p><b>The repository's own {@code ignore:} is honoured here too</b>, because it is read at the
   * same revision and says the same thing: an ecosystem a repository asks to be left out of is one
   * it is left out of at a tag as well as on main. The motivating case makes that obvious — the
   * wrapper's forty-seven lagging gitlinks are no more meaningful in a release of it than in its
   * working tree.
   *
   * <p><b>Nothing here throws and nothing here is judged.</b> A revision the host does not hold is
   * a status, not an exception; the caller decides whether that is poison or a retry. See {@code
   * adoption/ReleaseLedger}, which is the one caller and holds the seam's failure split.
   */
  public Pins pinsAt(String project, String name, String revision) {
    TreeLookup root = gitHost.head(project, name, revision);
    if (!root.found()) {
      return new Pins(root.status(), null, List.of(), root.message());
    }
    String sha = root.headSha();
    GroupConfig.Parsed config = groups(project, name, sha);
    return new Pins(FileLookup.Status.FOUND, sha, pins(project, name, sha, root, config), null);
  }

  /**
   * The four parsers over one resolved tree, and the per-line dedupe that follows them.
   *
   * <p><b>Shared by the scan and by the ledger's read of a released tree</b>, which is the whole of
   * what those two have in common: a second copy of this would be a second answer to "what does
   * this repository declare", and the two would disagree the first time a parser changed.
   */
  private List<ParsedPin> pins(
      String project, String name, String sha, TreeLookup root, GroupConfig.Parsed config) {
    List<ParsedPin> pins = new ArrayList<>();
    if (!config.ignores(Ecosystem.MAVEN)) {
      pins.addAll(mavenPins(project, name, sha, root));
    }
    if (!config.ignores(Ecosystem.NPM)) {
      pins.addAll(npmPins(project, name, sha, root));
    }
    if (!config.ignores(Ecosystem.DOCKER)) {
      pins.addAll(dockerPins(project, name, sha, root));
    }
    if (!config.ignores(Ecosystem.GITLINK)) {
      pins.addAll(gitlinkPins(project, name, sha, root));
    }
    // ONE ROW PER LINE. Every module of a reactor names the same root property, and each one
    // produced a pin against the root pom above. They are one line and one change; without this
    // the payload would carry the same edit once per module and the commit message would repeat it.
    return dedupe(pins);
  }

  private static Read absent(String name, String branch) {
    return new Read(
        RepositoryStatus.ABSENT,
        null,
        List.of(),
        List.of(),
        null,
        "the git host does not hold " + name + " at " + branch);
  }

  /**
   * The root pom and every pom the reactor reaches from it.
   *
   * <p>The ROOT pom's properties are carried into every module, because a module's version
   * expression is nearly always defined up there — resolving them per-file would drop most of the
   * platform's pins as unresolvable.
   *
   * <p><b>The whole reactor is read BEFORE any of it is parsed</b>, and that ordering is the point:
   * "is this dependency one of our own modules" cannot be answered from a single pom, and it is the
   * question that decides whether a pin is bumpable at all. A one-pass parser reported
   * {@code eu.wohlben.qits:qits-ci-domain} as a dependency of {@code qits-ci} to upgrade, which
   * would have been an offer to overwrite what its own release stamps.
   */
  private List<ParsedPin> mavenPins(String project, String name, String sha, TreeLookup root) {
    if (!root.hasBlob("pom.xml")) {
      return List.of();
    }
    FileLookup rootPom = gitHost.blob(project, name, sha, "pom.xml");
    if (!rootPom.found()) {
      return List.of();
    }

    // --- pass one: read the reactor ------------------------------------------------------------
    Map<String, String> poms = new LinkedHashMap<>();
    poms.put("pom.xml", rootPom.content());
    Deque<String> pending = new ArrayDeque<>();
    for (String module : PomParser.modules(rootPom.content())) {
      pending.add(modulePom("", module));
    }
    while (!pending.isEmpty() && poms.size() < MAX_POMS) {
      String path = pending.poll();
      if (path == null || poms.containsKey(path)) {
        continue;
      }
      if (path.contains(EMBEDDED_CLIENT)) {
        LOG.debugf("Not following %s of %s: it is an embedded client's own repository", path, name);
        continue;
      }
      FileLookup pom = gitHost.blob(project, name, sha, path);
      if (!pom.found()) {
        continue;
      }
      poms.put(path, pom.content());
      String directory = path.substring(0, path.length() - "pom.xml".length());
      for (String module : PomParser.modules(pom.content())) {
        pending.add(modulePom(directory, module));
      }
    }

    // Every coordinate this repository builds. A dependency on one of these is the repository
    // depending on itself, and a PARENT that is one of these is not a dependency at all.
    Set<String> reactor = new LinkedHashSet<>();
    for (String content : poms.values()) {
      PomParser.coordinate(content).ifPresent(reactor::add);
    }

    // --- pass two: parse, and judge each pin against the reactor -------------------------------
    Map<String, String> rootProperties = PomParser.properties(rootPom.content());
    List<ParsedPin> pins = new ArrayList<>();
    for (Map.Entry<String, String> pom : poms.entrySet()) {
      Map<String, String> inherited = pom.getKey().equals("pom.xml") ? Map.of() : rootProperties;
      for (ParsedPin pin : PomParser.parse(pom.getKey(), pom.getValue(), inherited, "pom.xml")) {
        boolean ownArtifact = reactor.contains(pin.name());
        if (ownArtifact && pin.location().startsWith(PARENT_LOCATION)) {
          // A module inheriting this repository's own root pom is the reactor's shape, not a
          // dependency anybody could upgrade. A parent from OUTSIDE the reactor — a shared
          // qits-parent from the registry — is not in this set and stays a pin.
          continue;
        }
        pins.add(pin.withReactorOwn(pin.reactorOwn() || ownArtifact));
      }
    }
    return pins;
  }

  /** A module entry may name a directory or a pom outright, which is maven's own rule. */
  private static String modulePom(String directory, String module) {
    String value = module.trim();
    while (value.startsWith("./")) {
      value = value.substring(2);
    }
    String path = value.endsWith(".xml") ? value : trimTrailingSlash(value) + "/pom.xml";
    return directory.isEmpty() ? path : directory + path;
  }

  private static String trimTrailingSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  /** The root package.json, resolved through the lock beside it. */
  private List<ParsedPin> npmPins(String project, String name, String sha, TreeLookup root) {
    if (!root.hasBlob("package.json")) {
      return List.of();
    }
    FileLookup manifest = gitHost.blob(project, name, sha, "package.json");
    if (!manifest.found()) {
      return List.of();
    }
    String lock = null;
    if (root.hasBlob(PACKAGE_LOCK)) {
      FileLookup lockFile = gitHost.blob(project, name, sha, PACKAGE_LOCK);
      if (lockFile.found()) {
        lock = lockFile.content();
      }
    }
    return NpmParser.parse("package.json", manifest.content(), lock);
  }

  /**
   * Every Dockerfile in the repository root, and every one in a {@code docker/} directory beside it.
   *
   * <p><b>ONE named directory, not a walk.</b> The root alone was the whole of this pass and it
   * missed real files: qits-workspace-daemon builds from {@code docker/Dockerfile} and
   * qits-projects-daemon from {@code docker/Dockerfile.projects}, so their base images were pinned
   * in the inventory of nothing. A recursive search would read every vendored file in every
   * repository on the platform to find them; {@code docker/} is the one place on this platform that
   * holds a build file that is not in the root, and naming it costs exactly one extra listing — and
   * that only for a repository that has the directory, because the root listing already says.
   */
  private List<ParsedPin> dockerPins(String project, String name, String sha, TreeLookup root) {
    List<ParsedPin> pins = new ArrayList<>();
    pins.addAll(dockerPins(project, name, sha, root, ""));
    if (root.entry(DOCKER_DIRECTORY).filter(TreeLookup.TreeEntry::isTree).isPresent()) {
      TreeLookup listing = gitHost.tree(project, name, sha, DOCKER_DIRECTORY);
      if (listing.found()) {
        pins.addAll(dockerPins(project, name, sha, listing, DOCKER_DIRECTORY + "/"));
      }
    }
    return pins;
  }

  /** The Dockerfiles of one listing, read at the path that listing sits at. */
  private List<ParsedPin> dockerPins(
      String project, String name, String sha, TreeLookup listing, String directory) {
    List<ParsedPin> pins = new ArrayList<>();
    for (TreeLookup.TreeEntry entry : listing.entries()) {
      if (!entry.isBlob() || !isDockerfile(entry.name())) {
        continue;
      }
      String path = directory + entry.name();
      FileLookup file = gitHost.blob(project, name, sha, path);
      if (file.found()) {
        pins.addAll(DockerParser.parse(path, file.content()));
      }
    }
    return pins;
  }

  /**
   * Every submodule {@code .gitmodules} declares, pinned at the sha its gitlink records.
   *
   * <p><b>The file names the submodule; the TREE holds the version.</b> {@code .gitmodules} is a
   * committed text file and carries a path, a url and nothing about what is checked out there — the
   * commit a submodule is pinned at lives in the parent's tree, as a mode-{@code 160000} entry at
   * that path. So this reads both: the file for the name (the url's basename) and the directory
   * listing above the path for the sha.
   *
   * <p><b>A gitlink whose sha the git host does not report is SKIPPED, and that is the whole
   * safety of this pass.</b> The tree route collapses every non-directory entry to {@code blob} and
   * has never reported an object name, so on such a host this produces nothing at all — which is
   * the honest answer. A pin recorded with a made-up version would be one the pending rule compares
   * and the bump step then applies, into somebody else's repository.
   *
   * <p><b>A file that will not parse is not a CONFIG_ERROR.</b> {@code .config/qits/maintenance.yml}
   * is this service's own configuration surface and a repository that writes it wrongly is told so
   * on its row; {@code .gitmodules} is git's file, written by {@code git submodule add}, and a
   * repository whose whole scan failed over one unreadable line in it would be this service
   * claiming ownership of a format it does not own. Unreadable entries are dropped and the rest of
   * the scan stands.
   */
  private List<ParsedPin> gitlinkPins(String project, String name, String sha, TreeLookup root) {
    if (!root.hasBlob(GitmodulesParser.PATH)) {
      return List.of();
    }
    FileLookup file = gitHost.blob(project, name, sha, GitmodulesParser.PATH);
    if (!file.found()) {
      return List.of();
    }
    List<GitmodulesParser.Submodule> modules = GitmodulesParser.parse(file.content());
    if (modules.isEmpty()) {
      return List.of();
    }
    // One listing per DIRECTORY, not per submodule: a wrapper repository declares dozens, and the
    // ones sharing a parent share the read that answers for all of them.
    Map<String, TreeLookup> listings = new LinkedHashMap<>();
    List<ParsedPin> pins = new ArrayList<>();
    List<String> unpinned = new ArrayList<>();
    for (GitmodulesParser.Submodule module : modules) {
      if (pins.size() >= MAX_GITLINKS) {
        LOG.warnf(
            "%s declares more than %d submodules; the rest of .gitmodules is not read",
            name, MAX_GITLINKS);
        break;
      }
      int slash = module.path().lastIndexOf('/');
      String directory = slash < 0 ? "" : module.path().substring(0, slash);
      String entryName = slash < 0 ? module.path() : module.path().substring(slash + 1);
      TreeLookup listing =
          directory.isEmpty()
              ? root
              : listings.computeIfAbsent(directory, path -> gitHost.tree(project, name, sha, path));
      if (!listing.found()) {
        LOG.debugf(
            "%s declares the submodule %s at %s, which this revision does not list",
            name, module.name(), module.path());
        continue;
      }
      Optional<TreeLookup.TreeEntry> entry = listing.entry(entryName);
      if (entry.isEmpty()) {
        // Declared in the file and not present in the tree: an ordinary state for a submodule
        // removed in a commit that left the section behind.
        LOG.debugf(
            "%s declares the submodule %s at %s, where the tree holds nothing",
            name, module.name(), module.path());
        continue;
      }
      String pinned = entry.get().isGitlink() ? entry.get().sha() : null;
      if (pinned == null || pinned.isBlank()) {
        // ONE line per repository rather than one per submodule: on a git host that reports no
        // object names this is every submodule of every repository, every scan, and a wrapper
        // declares dozens.
        unpinned.add(module.name());
        continue;
      }
      pins.add(
          new ParsedPin(
              Ecosystem.GITLINK,
              module.path(),
              module.name(),
              pinned.trim(),
              null,
              GITLINK_LOCATION + module.path(),
              false));
    }
    if (!unpinned.isEmpty()) {
      LOG.warnf(
          "%s declares %d submodule(s) the git host reports no gitlink sha for (%s);"
              + " they are not inventoried",
          name, unpinned.size(), String.join(", ", unpinned));
    }
    return pins;
  }

  /** One pin per (manifest, name, location) — see the call site for why there are duplicates. */
  private static List<ParsedPin> dedupe(List<ParsedPin> pins) {
    java.util.Map<String, ParsedPin> byLine = new java.util.LinkedHashMap<>();
    for (ParsedPin pin : pins) {
      byLine.putIfAbsent(pin.manifestPath() + " " + pin.name() + " " + pin.location(), pin);
    }
    return new ArrayList<>(byLine.values());
  }

  /**
   * Both spellings of a qualified build file, because this platform writes both:
   * {@code node.Dockerfile} beside {@code Dockerfile.projects}. Neither is a convention anybody
   * here owns — they are docker's two — and a repository that picked the other one is not a
   * repository whose base image goes uninventoried.
   */
  static boolean isDockerfile(String name) {
    return "Dockerfile".equals(name)
        || name.endsWith(".Dockerfile")
        || name.startsWith("Dockerfile.");
  }

  /**
   * The repository's own file: its grouping, and the ecosystems it asks to be left out of.
   *
   * <p>An UNREACHABLE read of the file is treated as absent rather than as a config error: the
   * repository is readable — its manifests were just read at this sha — so a single failing blob is
   * this service's problem and not the repository's. The next scan reads it again.
   */
  private GroupConfig.Parsed groups(String project, String name, String sha) {
    FileLookup file = gitHost.blob(project, name, sha, GroupConfig.PATH);
    if (!file.found()) {
      return GroupConfig.fallback();
    }
    return GroupConfig.parse(file.content());
  }
}
