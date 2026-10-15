package eu.wohlben.qits.maintenance.automation;

import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.entity.MtRelease;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.githost.FileLookup;
import eu.wohlben.qits.maintenance.githost.TreeLookup;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.RepositoryArchetype;
import eu.wohlben.qits.maintenance.pending.Change;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * <b>Estate pins</b>: a wrapper's gitlinks, moved to what each member released, as ONE commit on
 * {@code maintenance/automations/estate-pins/<request>} that is joined to the request — so that the
 * fold CI gates and a person approves names what every member has released.
 *
 * <p><b>The decision moved here from qits-projects</b> (qits-999), where {@code EstatePinRefresh}
 * computed it and {@code HttpEstatePins} posted one TARGETED bump per branch. The entries, the skips
 * and the change list are still that port's — byte for byte the six fields that door was sent —
 * and the facts are this service's own: the inventory row's archetype, its release ledger ({@code
 * mt_release}, written from {@code SCMRelease}) for each sibling's newest version, and the git host
 * for the fold.
 *
 * <h2>Why its own branch, and why the fold (qits-1258)</h2>
 *
 * <p>Until qits-1258 this was the one {@link Target#SOURCE_BRANCHES} kind: it planned PER SOURCE
 * BRANCH — the root tree, {@code .gitmodules} and one listing per component directory of every
 * branch the request named — and wrote one {@code bump(targeted)} commit onto each branch that lagged.
 * A nine-branch wrapper request (qits-qits-rr-137) made that hundreds of git-host reads per settle,
 * more than fitted in qits-projects' ten-second timeout; the abandoned settles kept reading, the git
 * host queued, and no release request on the estate moved for an hour. Meanwhile each round wrote
 * nine commits onto nine agents' branches, every one of which re-folded the request, and the folds
 * conflicted with each other until the breaker said "not converging".
 *
 * <p>Both halves had the same cure, the one {@code dependency-bump} already took: what the request
 * releases is its FOLD, so the fold is what is read and the fold is what one commit fixes. The plan
 * reads the fold's {@code .gitmodules} and its tree — one recursive listing — however many branches
 * the request names, and writes nothing onto anybody's branch: the commit lands on this kind's own
 * branch, joined at {@code LOWEST} like every other automation's. {@link
 * AutomationSubject#sourceBranches()} is not read here at all.
 *
 * <h2>Applies, plans, writes</h2>
 *
 * <ul>
 *   <li><b>Applies</b> when the inventory's archetype is {@code PROJECT} — a wrapper. Every other
 *       repository answers NOT_APPLICABLE with no read at all.
 *   <li><b>Plans AT THE FOLD.</b> A fold without {@code .gitmodules} pins nothing. Each declared
 *       entry naming a sibling of the same project that has a release is compared against the
 *       gitlink the fold holds at its path, and a difference — a gitlink at another sha, or none at
 *       all yet — is a change. A sibling with no release is simply not in the estate: pinning it to
 *       a branch head would invent a release nobody made.
 *   <li><b>One tree read.</b> The gitlinks come from ONE recursive listing of the fold's root
 *       ({@link FoldReader#recursiveTree}). A git host that predates {@code ?recursive=true} answers
 *       the root's plain listing instead, and only then are the component directories listed one by
 *       one, each at most once — the old cost, paid once per fold rather than once per branch.
 *   <li><b>No difference is FRESH, with no run</b> — the loop's terminator. This kind's own commit
 *       changes gitlink paths, which are its {@link #inputPaths(AutomationSubject) inputs}, so the
 *       fold its join causes is planned again rather than carried; it finds every pin at its release
 *       and ends there. An empty change list is never asked for.
 *   <li><b>Otherwise ONE run</b>, through qits-ci's shared core and its {@code
 *       automations/estate-pins.yml} kind file: {@code changes} in the {@link Change} entry shape —
 *       gitlinks only — and {@code commitPaths}, the gitlink paths those changes move.
 * </ul>
 *
 * <p><b>Any git-host read that does not answer makes the whole plan UNKNOWN</b>, not a partial
 * answer: "these eleven pins are current and the twelfth could not be read" is not a statement about
 * the fold. UNKNOWN is answered and not stored, so the next ask decides again.
 *
 * <h2>What it may commit, and what it reads</h2>
 *
 * <p>It commits the gitlink paths {@code .gitmodules} declares — computed per subject, from the fold
 * — and it reads those paths and {@code .gitmodules}. A gitlink in a wrapper is no other kind's input
 * and no other kind writes one ({@code dependency-bump} leaves a wrapper's gitlinks to this kind),
 * which is the invariant carry-over rests on: a fold that touched none of them — a screenshot join, a
 * person's README — carries this kind's outcome over without a read.
 *
 * <p><b>Rows opened before qits-1258 are still on source branches</b>, and still end the way they
 * were opened: {@code AutomationService} reads a row whose branch is not under this kind's own
 * prefix as a source-branch row, dispatched through {@code maintenance-bump.yml} under {@link
 * #TARGETED_GROUP} and ended by its run's verdict.
 */
@ApplicationScoped
public class EstatePinsAutomation implements ReleaseRequestAutomation {

  public static final String KIND = "estate-pins";

  /**
   * The payload's {@code group} on a SOURCE-BRANCH run — a sentinel rather than a real group, kept
   * so the step still refuses it the way a plain group name is refused, and so the commit it writes
   * still reads {@code bump(targeted): N dependencies}: a label for the history, never a key. {@code
   * mt_bump.automation_kind} is the discriminator.
   *
   * <p><b>Only rows opened before qits-1258 are sent with it</b>: since then this kind writes its
   * own branch through the shared core, and {@code AutomationService.dispatchSourceBranch} is left
   * for the source-branch rows such a release may still find REQUESTED or RUNNING. The constant goes
   * with that path.
   *
   * <p><b>Moved here from {@code BumpService} (qits-1006)</b>, where it was declared for the
   * {@code /branches/bumps} door's TARGETED mode.
   */
  public static final String TARGETED_GROUP = "targeted";

  /** Every path at or under the root: the inputs of a fold whose declaration could not be read. */
  private static final String EVERYTHING = ":(glob)**";

  @Inject MaintenanceStore store;

  /** One declared entry of the fold. */
  private record Declared(String path, String name) {}

  /** A declared entry whose sibling has a release, so there is a version to pin at. */
  private record Candidate(String path, String name, String version, String releasedSha) {}

  /**
   * The gitlinks the fold holds at the candidates' paths — a sha, or null where nothing is pinned
   * (no entry, or one that is no gitlink) — or why they could not be read.
   */
  private record Pinned(Map<String, String> shas, String unreadable) {

    static Pinned unreadable(String why) {
      return new Pinned(Map.of(), why);
    }
  }

  @Override
  public String kind() {
    return KIND;
  }

  @Override
  public String label() {
    return "Estate pins";
  }

  @Override
  public Applicability applicability(AutomationSubject subject) {
    Optional<RepositoryArchetype> archetype =
        RepositoryArchetype.of(subject.repository().archetype);
    return archetype.isPresent() && archetype.get() == RepositoryArchetype.PROJECT
        ? Applicability.applies()
        : Applicability.notApplicable(
            subject.repository().name + " is no wrapper (archetype "
                + subject.repository().archetype + "), so it pins no estate");
  }

  /** The shared core, and its {@code automations/estate-pins.yml} kind file in qits-ci. */
  @Override
  public String pipeline() {
    return CiClient.AUTOMATION_EVENT_NAME;
  }

  /** None that holds for every repository: the paths are the wrapper's own. */
  @Override
  public List<String> committablePaths() {
    return List.of();
  }

  /**
   * The gitlink paths the FOLD's {@code .gitmodules} declares. An unreadable declaration is no
   * paths, which is the conservative answer: nothing carries on them.
   */
  @Override
  public List<String> committablePaths(AutomationSubject subject) {
    FileLookup declaration = subject.fold().file(WrapperGitmodules.PATH);
    if (!declaration.found()) {
      return List.of();
    }
    List<String> paths = new ArrayList<>();
    for (WrapperGitmodules.Entry entry : WrapperGitmodules.entries(declaration.content())) {
      if (entry.path() != null && !entry.path().isBlank() && !paths.contains(entry.path())) {
        paths.add(entry.path());
      }
    }
    return List.copyOf(paths);
  }

  /**
   * What the plan reads: {@code .gitmodules} and the gitlink paths it declares. A fold that touched
   * none of them carries; this kind's own commit touches its gitlinks, so its re-fold is planned
   * again — and the plan, finding every pin at its release, is what ends the loop. A declaration
   * that could not be read names everything, so nothing carries on it.
   */
  @Override
  public List<String> inputPaths(AutomationSubject subject) {
    FileLookup declaration = subject.fold().file(WrapperGitmodules.PATH);
    return switch (declaration.status()) {
      case FOUND -> {
        List<String> paths = new ArrayList<>();
        paths.add(WrapperGitmodules.PATH);
        paths.addAll(committablePaths(subject));
        yield List.copyOf(paths);
      }
      case ABSENT -> List.of(WrapperGitmodules.PATH);
      default -> List.of(EVERYTHING);
    };
  }

  /** The part of the inputs that holds for every wrapper; the gitlink paths are each fold's own. */
  @Override
  public List<String> inputPaths() {
    return List.of(WrapperGitmodules.PATH);
  }

  /** Its own branch, since qits-1258: see the class javadoc. */
  @Override
  public Target target() {
    return Target.OWN_BRANCH;
  }

  /**
   * The re-run door plans this kind too: its plan IS its change list, and a run sent with none
   * would spend a CI slot writing nothing.
   */
  @Override
  public boolean planIsTheChangeList() {
    return true;
  }

  /** A gitlink is a build input: what the wrapper's members are built at. */
  @Override
  public Stage stage() {
    return Stage.SOURCE;
  }

  /** {@code EstatePinRefresh.attempt}'s decision, at the fold: see the class javadoc. */
  @Override
  public Plan plan(AutomationSubject subject) {
    MtRepository wrapper = subject.repository();
    FoldReader fold = subject.fold();
    FileLookup declaration = fold.file(WrapperGitmodules.PATH);
    switch (declaration.status()) {
      case FOUND -> {}
      case ABSENT -> {
        return Plan.fresh("the fold declares no submodules, so it pins no estate");
      }
      default -> {
        return Plan.unknown(
            "the submodule declaration at the fold could not be read: "
                + describe(declaration.status(), declaration.message()));
      }
    }
    List<Declared> declared = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (WrapperGitmodules.Entry entry : WrapperGitmodules.entries(declaration.content())) {
      if (entry.path() == null || entry.path().isBlank() || entry.name() == null) {
        // A section somebody is still writing: no pin to compare.
        continue;
      }
      if (seen.add(entry.path())) {
        declared.add(new Declared(entry.path(), entry.name()));
      }
    }
    List<Candidate> candidates = released(wrapper, declared);
    if (candidates.isEmpty()) {
      // Nothing declared has a release to be pinned at, so there is nothing to read the tree for.
      return Plan.fresh("no submodule the fold declares names a released member");
    }

    Pinned pinned = pinned(fold, candidates);
    if (pinned.unreadable() != null) {
      return Plan.unknown(pinned.unreadable());
    }
    List<Change> changes = new ArrayList<>();
    List<String> paths = new ArrayList<>();
    for (Candidate candidate : candidates) {
      String sha = pinned.shas().get(candidate.path());
      if (Objects.equals(sha, candidate.releasedSha())) {
        continue;
      }
      changes.add(change(candidate.path(), candidate.name(), sha, candidate.version()));
      paths.add(candidate.path());
    }
    // The terminating case: never a run with an empty change list.
    if (changes.isEmpty()) {
      return Plan.fresh("the estate pins already name what the members released");
    }
    return Plan.runs(
        List.of(
            new Plan.Run(
                null, changes, Map.of(DependencyBumpAutomation.COMMIT_PATHS, List.copyOf(paths)))));
  }

  /**
   * The gitlinks at the candidates' paths, read in ONE call where the git host can answer it.
   *
   * <p>A recursive answer names every gitlink under the root by its path. A git host that predates
   * {@code ?recursive=true} answers the root's own listing instead — not a statement that nothing
   * lies deeper — so then each candidate's directory is listed, at most once per directory, and the
   * plain answer already in hand serves for the root itself.
   */
  private static Pinned pinned(FoldReader fold, List<Candidate> candidates) {
    TreeLookup whole = fold.recursiveTree();
    if (!whole.found()) {
      return Pinned.unreadable(
          "the tree of the fold could not be read: " + describe(whole.status(), whole.message()));
    }
    Map<String, String> shas = new HashMap<>();
    if (whole.recursive()) {
      for (Candidate candidate : candidates) {
        shas.put(candidate.path(), gitlink(whole.entryAt(candidate.path())));
      }
      return new Pinned(shas, null);
    }
    Map<String, TreeLookup> listings = new HashMap<>();
    listings.put("", whole);
    for (Candidate candidate : candidates) {
      int slash = candidate.path().lastIndexOf('/');
      String directory = slash < 0 ? "" : candidate.path().substring(0, slash);
      String name = slash < 0 ? candidate.path() : candidate.path().substring(slash + 1);
      TreeLookup listing = listings.computeIfAbsent(directory, fold::tree);
      if (listing.status() == FileLookup.Status.UNREACHABLE
          || listing.status() == FileLookup.Status.INVALID) {
        return Pinned.unreadable(
            "the pin at " + candidate.path() + " could not be read: "
                + describe(listing.status(), listing.message()));
      }
      // A directory or an entry that is not there, or one that is no gitlink, is "nothing is pinned
      // here" — a declaration whose gitlink has not been committed yet — and a difference like any
      // other: the entry wants the released sha and does not have it.
      shas.put(candidate.path(), listing.found() ? gitlink(listing.entry(name)) : null);
    }
    return new Pinned(shas, null);
  }

  /** The sha a tree entry pins, or null when there is no entry or it is no gitlink. */
  private static String gitlink(Optional<TreeLookup.TreeEntry> entry) {
    return entry.filter(TreeLookup.TreeEntry::isGitlink).map(TreeLookup.TreeEntry::sha).orElse(null);
  }

  /**
   * One gitlink change, in the six fields {@code HttpEstatePins} sent and in its order: the
   * ecosystem, the submodule's directory, the sibling's name (what the step derives a clone url
   * from), the sha the tree holds (nullable: commit-message material, not a precondition), the
   * released version and {@code gitlink:<path>}.
   */
  static Change change(String path, String name, String from, String to) {
    return new Change(Ecosystem.GITLINK.wireName(), path, name, from, to, "gitlink:" + path);
  }

  /**
   * The declared entries that have a version to be pinned at: a repository of the wrapper's own
   * project under that name, with a release in the ledger. An entry naming nothing here, or a
   * sibling never released, is skipped — the wrapper is edited by people and reconciled
   * asynchronously, and a pin cannot name a release nobody made.
   */
  private List<Candidate> released(MtRepository wrapper, List<Declared> declared) {
    List<Candidate> out = new ArrayList<>();
    Map<String, Optional<MtRelease>> newest = new HashMap<>();
    for (Declared entry : declared) {
      Optional<MtRelease> latest =
          newest.computeIfAbsent(entry.name(), name -> newestRelease(wrapper, name));
      if (latest.isEmpty()) {
        continue;
      }
      out.add(
          new Candidate(
              entry.path(),
              entry.name(),
              latest.get().version,
              latest.get().sha));
    }
    return out;
  }

  private Optional<MtRelease> newestRelease(MtRepository wrapper, String name) {
    Optional<MtRepository> sibling = store.repository(name);
    if (sibling.isEmpty() || !Objects.equals(sibling.get().project, wrapper.project)) {
      return Optional.empty();
    }
    List<MtRelease> releases = store.releasesOf(name);
    if (releases.isEmpty()) {
      return Optional.empty();
    }
    MtRelease last = releases.get(releases.size() - 1);
    return last.version == null || last.sha == null ? Optional.empty() : Optional.of(last);
  }

  private static String describe(FileLookup.Status status, String message) {
    return message == null ? status.name() : message;
  }
}
