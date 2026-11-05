package eu.wohlben.qits.maintenance.manifest;

import eu.wohlben.qits.bootstrap.graph.Ecosystem;
import eu.wohlben.qits.bootstrap.graph.manifest.GitmodulesParser;
import eu.wohlben.qits.bootstrap.graph.manifest.ManifestReader;
import eu.wohlben.qits.bootstrap.graph.manifest.ParsedPin;
import eu.wohlben.qits.bootstrap.graph.manifest.RepositoryFiles;
import eu.wohlben.qits.maintenance.catalog.CatalogEntry;
import eu.wohlben.qits.maintenance.githost.FileLookup;
import eu.wohlben.qits.maintenance.githost.GitHostReader;
import eu.wohlben.qits.maintenance.githost.TreeLookup;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
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
 * <p><b>That discovery is the library's</b> ({@link ManifestReader}, qits-bootstrap-graph): this
 * class answers its {@link RepositoryFiles} from the git host ({@link GitHostFiles}) and keeps the
 * statuses, the configuration and everything about where the files came from.
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
  public static final String PACKAGE_LOCK = ManifestReader.PACKAGE_LOCK;

  private static final Logger LOG = Logger.getLogger(ManifestScanner.class);

  /** The one directory below the root that a Dockerfile is looked for in. */
  private static final String DOCKER_DIRECTORY = "docker";

  /** A cap on the submodule walk. A wrapper repository declares dozens and nothing declares this
   * many; the bound is what keeps one malformed file from being an unbounded read. */
  private static final int MAX_GITLINKS = 128;

  /** What a gitlink pin's location says: the path the {@code 160000} entry sits at. */
  static final String GITLINK_LOCATION = ManifestReader.GITLINK_LOCATION;

  @Inject GitHostReader gitHost;

  /**
   * What one repository's scan read.
   *
   * @param status the row's status
   * @param headSha the commit every pin was read at, null unless the status is OK or CONFIG_ERROR
   * @param pins every direct pin found, in discovery order — an ignored ecosystem contributes none
   * @param groups the repository's grouping, empty when the status is CONFIG_ERROR
   * @param message the sentence for the row, null when the status is OK
   * @param bumps whether the repository is owed dependency bumps (qits-1784) — decided only on an OK
   *     read, and {@link DependencyBumps#ON} on every other, where the status already keeps the
   *     dispatcher away
   */
  public record Read(
      RepositoryStatus status,
      String headSha,
      List<ParsedPin> pins,
      List<GroupConfig.Group> groups,
      eu.wohlben.qits.maintenance.model.GroupSource groupSource,
      String message,
      DependencyBumps bumps) {

    public Read {
      bumps = bumps == null ? DependencyBumps.ON : bumps;
    }

    /** Every read that does not get as far as deciding the bumps. */
    public Read(
        RepositoryStatus status,
        String headSha,
        List<ParsedPin> pins,
        List<GroupConfig.Group> groups,
        eu.wohlben.qits.maintenance.model.GroupSource groupSource,
        String message) {
      this(status, headSha, pins, groups, groupSource, message, DependencyBumps.ON);
    }
  }

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

    List<ParsedPin> pins;
    try {
      pins = pins(project, name, headSha, root, config);
    } catch (UnservedManifest unserved) {
      // A file the tree LISTS that the git host would not serve. The pins it holds are unknown, not
      // absent, so the row is UNREACHABLE and the scan keeps what it had — see UnservedManifest.
      LOG.warnf("At %s: %s", headSha, unserved.getMessage());
      return new Read(
          RepositoryStatus.UNREACHABLE, null, List.of(), List.of(), null, unserved.getMessage());
    }

    if (!config.ok()) {
      return new Read(
          RepositoryStatus.CONFIG_ERROR, headSha, List.copyOf(pins), List.of(), null, config.error());
    }
    // AT THE SAME SHA, AND ONLY HERE (qits-1784). Whether the repository is owed bumps is a fact
    // about its current tree, which is what a scan writes the row from; the ledger's read of a
    // released tree (pinsAt) has no row to put it on.
    FileLookup repositoryFile = gitHost.blob(project, name, headSha, RepositoryConfig.PATH);
    RepositoryConfig.Parsed repositoryConfig =
        switch (repositoryFile.status()) {
          case FOUND -> RepositoryConfig.parse(repositoryFile.content());
          // GONE is how a 404 on a blob reads when the listing at the sha did not answer either; the
          // sha was resolved a moment ago, so it is the file that is missing, which is "not
          // archived".
          case ABSENT, GONE -> RepositoryConfig.fallback();
          default -> null;
        };
    if (repositoryConfig == null) {
      // NOT READ IS NOT "NOT ARCHIVED". Falling back, as an unreadable maintenance.yml does, would
      // switch an archived repository's bumps back on for one scan — long enough for the dispatcher
      // to open a request against it. UNREACHABLE keeps the row's pins and its last decision, and
      // the next scan reads the file again.
      String unread =
          name + " has " + RepositoryConfig.PATH + " and the git host did not serve it ("
              + repositoryFile.status()
              + (repositoryFile.message() == null ? "" : ": " + repositoryFile.message()) + ")";
      LOG.warnf("At %s: %s", headSha, unread);
      return new Read(RepositoryStatus.UNREACHABLE, null, List.of(), List.of(), null, unread);
    }
    if (!repositoryConfig.ok()) {
      return new Read(
          RepositoryStatus.CONFIG_ERROR,
          headSha,
          List.copyOf(pins),
          List.of(),
          null,
          repositoryConfig.error());
    }
    if (!config.ignored().isEmpty()) {
      LOG.debugf(
          "%s asks not to be scanned for %s",
          name, config.ignored().stream().map(Ecosystem::wireName).toList());
    }
    DependencyBumps bumps =
        DependencyBumps.of(config.dependencyBumps(), repositoryConfig.archived());
    if (!bumps.on()) {
      LOG.debugf("%s is owed no dependency bumps: %s", name, bumps.sentence());
    }
    return new Read(
        RepositoryStatus.OK,
        headSha,
        List.copyOf(pins),
        config.groups(),
        config.source(),
        null,
        bumps);
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
    try {
      return new Pins(FileLookup.Status.FOUND, sha, pins(project, name, sha, root, config), null);
    } catch (UnservedManifest unserved) {
      // Retryable for every caller: the ledger throws so its frame is offered again, the gitlink
      // read keeps the rows it reached last time, and the bump automation plans nothing.
      return new Pins(FileLookup.Status.UNREACHABLE, null, List.of(), unserved.getMessage());
    }
  }

  /**
   * The library's discovery over one resolved tree, and the per-line dedupe that follows it.
   *
   * <p><b>Shared by the scan and by the ledger's read of a released tree</b>, which is the whole of
   * what those two have in common: a second copy of this would be a second answer to "what does
   * this repository declare", and the two would disagree the first time a parser changed.
   *
   * <p><b>The discovery itself is {@link ManifestReader}'s</b> (qits-bootstrap-graph, qits-1879):
   * the root, the reactor, {@code docker/}, {@code .gitmodules} plus the gitlinks, the dedupe. What
   * stays here is everything about the git host — {@link GitHostFiles} answers the library's {@link
   * RepositoryFiles} from the tree and blob routes at {@code sha}, and a read it cannot make is
   * {@link UnservedManifest}, which propagates out of the library untouched.
   */
  private List<ParsedPin> pins(
      String project, String name, String sha, TreeLookup root, GroupConfig.Parsed config) {
    Set<Ecosystem> ignored = EnumSet.noneOf(Ecosystem.class);
    for (Ecosystem ecosystem : Ecosystem.values()) {
      if (config.ignores(ecosystem)) {
        ignored.add(ecosystem);
      }
    }
    GitHostFiles files = new GitHostFiles(project, name, sha, root, ignored);
    try {
      return ManifestReader.read(files, ignored);
    } catch (IllegalStateException contradiction) {
      // The library's own word for "listed and not read". GitHostFiles never answers a listed path
      // empty — it raises UnservedManifest with the host's status first — so this is the backstop,
      // and it means the same thing: the pins are unknown, the row is UNREACHABLE.
      throw new UnservedManifest(name + ": " + contradiction.getMessage());
    }
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
   * <b>One repository at one sha, as the git host's tree and blob routes answer it</b> — the
   * library's {@link RepositoryFiles}, and every rule about which reads are made and what a failed
   * one means.
   *
   * <ul>
   *   <li>{@link #paths()} is the root listing's blobs plus, when the root lists a {@code docker/}
   *       directory and docker is not ignored, the blobs of that one listing. Nothing else is
   *       listed: a repository without the directory is not asked for one, and one that ignores
   *       docker costs no listing for it.
   *   <li>{@link #read} of a path a listing named and the host did not serve — ABSENT included, a
   *       contradiction between two answers at the same sha — is {@link UnservedManifest}. A path no
   *       listing named (a reactor module's pom) answered ABSENT is genuinely absent; any other
   *       failure to ask is unserved. Every answer is kept, so a file is read once.
   *   <li>{@link #gitlinks()} lists each DIRECTORY a {@code .gitmodules} path sits in, once, and
   *       reports the sha of every entry there that is a gitlink. A directory the commit does not
   *       hold (ABSENT) is a stale section; a listing that fails is unserved; a gitlink the host
   *       reports no sha for is left out rather than guessed — on a host without object names that
   *       is every submodule, said in one line.
   * </ul>
   */
  final class GitHostFiles implements RepositoryFiles {

    private final String project;
    private final String name;
    private final String sha;
    private final TreeLookup root;
    private final Set<Ecosystem> ignored;
    private final Map<String, Optional<String>> contents = new LinkedHashMap<>();
    private List<String> paths;
    private Set<String> listed;
    private Map<String, String> gitlinks;

    GitHostFiles(
        String project, String name, String sha, TreeLookup root, Set<Ecosystem> ignored) {
      this.project = project;
      this.name = name;
      this.sha = sha;
      this.root = root;
      this.ignored = ignored;
    }

    @Override
    public List<String> paths() {
      if (paths == null) {
        List<String> all = new ArrayList<>();
        for (TreeLookup.TreeEntry entry : root.entries()) {
          if (entry.isBlob()) {
            all.add(entry.name());
          }
        }
        if (!ignored.contains(Ecosystem.DOCKER)
            && root.entry(DOCKER_DIRECTORY).filter(TreeLookup.TreeEntry::isTree).isPresent()) {
          // ONE named directory, not a walk: qits-workspace-daemon builds from docker/Dockerfile
          // and qits-projects-daemon from docker/Dockerfile.projects, and naming it costs exactly
          // one listing — and that only for a repository whose root says it has the directory.
          TreeLookup listing = gitHost.tree(project, name, sha, DOCKER_DIRECTORY);
          if (!listing.found()) {
            // The root listed the directory, so any answer but its listing is the host's failure.
            throw new UnservedManifest(
                name, DOCKER_DIRECTORY + "/", listing.status(), listing.message());
          }
          for (TreeLookup.TreeEntry entry : listing.entries()) {
            if (entry.isBlob()) {
              all.add(DOCKER_DIRECTORY + "/" + entry.name());
            }
          }
        }
        paths = List.copyOf(all);
        listed = Set.copyOf(all);
      }
      return paths;
    }

    @Override
    public Optional<String> read(String path) {
      Optional<String> known = contents.get(path);
      if (known != null) {
        return known;
      }
      paths();
      FileLookup file = gitHost.blob(project, name, sha, path);
      Optional<String> answer;
      if (file.found()) {
        answer = Optional.of(file.content());
      } else if (file.status() == FileLookup.Status.ABSENT && !listed.contains(path)) {
        // A module the pom NAMES and the commit does not carry. No listing said it was there — the
        // reactor walk asks by path, it does not list — so this is the git host's answer about a
        // path rather than a contradiction of one, and there is nothing at it to pin.
        answer = Optional.empty();
      } else {
        throw new UnservedManifest(name, path, file.status(), file.message());
      }
      contents.put(path, answer);
      return answer;
    }

    @Override
    public Map<String, String> gitlinks() {
      if (gitlinks != null) {
        return gitlinks;
      }
      gitlinks = new LinkedHashMap<>();
      Optional<String> declaration = read(GitmodulesParser.PATH);
      if (declaration.isEmpty()) {
        return gitlinks;
      }
      // One listing per DIRECTORY, not per submodule: a wrapper repository declares dozens, and the
      // ones sharing a parent share the read that answers for all of them.
      Map<String, TreeLookup> listings = new LinkedHashMap<>();
      List<String> unpinned = new ArrayList<>();
      int pinned = 0;
      for (GitmodulesParser.Submodule module : GitmodulesParser.parse(declaration.get())) {
        if (pinned >= MAX_GITLINKS) {
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
                : listings.computeIfAbsent(
                    directory, path -> gitHost.tree(project, name, sha, path));
        if (listing.status() == FileLookup.Status.ABSENT) {
          // The directory comes from .gitmodules, not from a listing, so a path this commit does
          // not hold is an answer — the same stale-section case as the missing entry below.
          LOG.debugf(
              "%s declares the submodule %s at %s, which this revision does not list",
              name, module.name(), module.path());
          continue;
        }
        if (!listing.found()) {
          throw new UnservedManifest(name, directory + "/", listing.status(), listing.message());
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
        String at = entry.get().isGitlink() ? entry.get().sha() : null;
        if (at == null || at.isBlank()) {
          // A gitlink whose sha the git host does not report is SKIPPED, and that is the whole
          // safety of this pass: a pin recorded with a made-up version would be one the pending
          // rule compares and the bump step then applies, into somebody else's repository.
          unpinned.add(module.name());
          continue;
        }
        gitlinks.putIfAbsent(module.path(), at);
        pinned++;
      }
      if (!unpinned.isEmpty()) {
        // ONE line per repository rather than one per submodule: on a git host that reports no
        // object names this is every submodule of every repository, every scan.
        LOG.warnf(
            "%s declares %d submodule(s) the git host reports no gitlink sha for (%s);"
                + " they are not inventoried",
            name, unpinned.size(), String.join(", ", unpinned));
      }
      return gitlinks;
    }
  }

  /**
   * <b>A manifest the tree lists and the git host would not serve (qits-1172).</b>
   *
   * <p>It used to read as absent: {@code root.hasBlob("pom.xml")} said the file was there, the
   * blob read came back not-found or failed, and the parser returned no pins — so the scan wrote a
   * repository with its maven half missing, {@code mt_main_pin} with it, and {@code GET /pins} told
   * the artifact GC those versions were unreferenced. A peer's hiccup became a deletion.
   *
   * <p><b>So it is the scan's UNREACHABLE, the one path that already keeps the pins.</b> {@link
   * #read} turns it into an UNREACHABLE {@link Read}, which {@code ScanService} answers with {@code
   * markRepository} and nothing else — {@code mt_pin}, {@code mt_main_pin} and the gitlink rows
   * stay what the last good scan read. {@link #pinsAt} turns it into an UNREACHABLE {@link Pins},
   * which every caller already treats as "ask again". It is an exception rather than a status
   * threaded through the parsers because it is all-or-nothing by design: one unserved file makes
   * the whole revision's reading untrustworthy, since a partial set is exactly the reduced set this
   * exists to keep out of the store.
   *
   * <p><b>Every status that is not FOUND counts, INVALID included.</b> A file too large to read is
   * permanent, so its repository stays UNREACHABLE with the sentence on its row until somebody
   * looks; that is loud and keeps more, where the old reading was silent and deleted. The cases that
   * are NOT this — a {@code <module>} whose pom the commit does not carry, a {@code .gitmodules}
   * path the tree does not hold — are paths no listing ever named, so ABSENT there is the honest
   * answer it always was, and only a failure to ask counts.
   *
   * <p>{@code .config/qits/maintenance.yml} is deliberately not routed through here: it is this
   * service's configuration rather than a manifest, an unreadable copy falls back to the default
   * grouping (see {@link #groups}), and the default ignores nothing — so it can only ever ADD pins.
   */
  static final class UnservedManifest extends RuntimeException {

    UnservedManifest(String repository, String path, FileLookup.Status status, String message) {
      super(
          repository + " has " + path + " and the git host did not serve it ("
              + status + (message == null ? "" : ": " + message)
              + "); the pins it holds are unknown, so none are written",
          null,
          false,
          false);
    }

    /** The library's own "listed and not read", already a sentence. */
    UnservedManifest(String message) {
      super(message, null, false, false);
    }
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
