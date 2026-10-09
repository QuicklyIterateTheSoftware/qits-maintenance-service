package eu.wohlben.qits.maintenance.automation;

import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.entity.MtRelease;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.githost.FileLookup;
import eu.wohlben.qits.maintenance.githost.GitHostReader;
import eu.wohlben.qits.maintenance.githost.TreeLookup;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.RepositoryArchetype;
import eu.wohlben.qits.maintenance.pending.Change;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * <b>Estate pins</b>: a wrapper's gitlinks, written onto the branches its release request releases
 * so that the fold CI gates and a person approves names what every member has released.
 *
 * <p><b>The decision moved here from qits-projects</b> (qits-999), where {@code EstatePinRefresh}
 * computed it and {@code HttpEstatePins} posted one TARGETED bump per branch. It is a port, and the
 * decisions are the same ones: same branches, same entries, same skips, and a change list that is
 * byte for byte what that door was sent. What changed is who reads the facts — this service's own:
 * the inventory row's archetype, its own release ledger ({@code mt_release}, written from {@code
 * SCMRelease}) for each sibling's newest version, and the git host for the branches.
 *
 * <h2>Applies, plans, writes</h2>
 *
 * <ul>
 *   <li><b>Applies</b> when the inventory's archetype is {@code PROJECT} — a wrapper. Every other
 *       repository answers NOT_APPLICABLE with no read at all.
 *   <li><b>Plans</b> per source branch — the request's named branches minus main (a source and
 *       never a target: it is protected, and a bump onto it fails on the push and holds the request
 *       for ever) and minus the automations' own. A branch without {@code .gitmodules} contributes
 *       nothing; each entry naming a sibling of the same project that has a release is compared
 *       against the gitlink the branch holds at its path, and a difference is a change. A sibling
 *       with no release is simply not in the estate: pinning it to a branch head would invent a
 *       release nobody made.
 *   <li><b>No difference anywhere is FRESH, with no run</b> — the terminating case, and the one that
 *       matters: the bump's own commit re-folds the request, and the next fold's plan finds nothing
 *       to change. An empty change list is never asked for, because it would end NOTHING_TO_DO over
 *       there and leave nothing to re-fold the request.
 *   <li><b>Otherwise one run per branch with changes</b>, through the unchanged {@code
 *       maintenance-bump.yml} under the {@code targeted} group, so the commit still reads {@code
 *       bump(targeted): N dependencies}.
 * </ul>
 *
 * <p><b>Any git-host read that does not answer makes the whole plan UNKNOWN</b>, not a partial
 * answer: "these eleven pins are current and the twelfth could not be read" is not a statement about
 * the fold. UNKNOWN is answered and not stored, so the sweep asks again.
 *
 * <h2>What it may commit</h2>
 *
 * <p>The gitlink paths {@code .gitmodules} declares — computed per subject, from the fold. A gitlink
 * is no other kind's input and no other kind writes one, which is the invariant carry-over rests on:
 * the re-fold a screenshot join causes is carried here, and the re-fold this kind's commit causes is
 * carried for the screenshots.
 */
@ApplicationScoped
public class EstatePinsAutomation implements ReleaseRequestAutomation {

  public static final String KIND = "estate-pins";

  /**
   * The payload's {@code group} on every run this kind dispatches — a sentinel rather than a real
   * group, kept so the step still refuses it the way a plain group name is refused, and so the
   * commit it writes still reads {@code bump(targeted): N dependencies}: a label for the history,
   * never a key. {@code mt_bump.automation_kind} is the discriminator.
   *
   * <p><b>Moved here from {@code BumpService} (qits-1006)</b>, where it was declared for the
   * {@code /branches/bumps} door's TARGETED mode. That door is retired and this kind is its only
   * reader now, so the constant lives beside the one place that still writes the sentinel.
   */
  public static final String TARGETED_GROUP = "targeted";

  @Inject GitHostReader gitHost;

  @Inject MaintenanceStore store;

  /** One declared entry of one branch. */
  private record Declared(String branch, String path, String name) {}

  /** A declared entry whose sibling has a release, so there is a version to pin at. */
  private record Candidate(
      String branch, String path, String name, String version, String releasedSha) {}

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

  @Override
  public String pipeline() {
    return CiClient.EVENT_NAME;
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

  @Override
  public Target target() {
    return Target.SOURCE_BRANCHES;
  }

  /** Gitlinks are inputs of the build: a SOURCE kind, planned on every fold (qits-1133). */
  @Override
  public Stage stage() {
    return Stage.SOURCE;
  }

  /** A port of {@code EstatePinRefresh.attempt}: see the class javadoc. */
  @Override
  public Plan plan(AutomationSubject subject) {
    MtRepository wrapper = subject.repository();
    Map<String, String> heads = new LinkedHashMap<>();
    List<Declared> declared = new ArrayList<>();
    for (String branch : subject.sourceBranches()) {
      // THE TREE, and not the file: a file read answers "absent" both to "this branch declares no
      // submodules" and — as a 404 — to a branch that is not there, and those are opposite answers
      // here. The root listing resolves the branch, and every read below names the sha it answered.
      TreeLookup root = gitHost.head(wrapper.project, wrapper.name, branch);
      if (!root.found()) {
        return Plan.unknown(
            "the tree of " + branch + " could not be read: " + describe(root.status(), root.message()));
      }
      if (!root.hasBlob(WrapperGitmodules.PATH)) {
        // A source branch that declares no submodules pins nothing that could be out of date.
        continue;
      }
      heads.put(branch, root.headSha());
      FileLookup content =
          gitHost.blob(wrapper.project, wrapper.name, root.headSha(), WrapperGitmodules.PATH);
      if (!content.found()) {
        return Plan.unknown(
            "the submodule declaration of " + branch + " could not be read: "
                + describe(content.status(), content.message()));
      }
      for (WrapperGitmodules.Entry entry : WrapperGitmodules.entries(content.content())) {
        if (entry.path() == null || entry.path().isBlank() || entry.name() == null) {
          // A section somebody is still writing: no pin to compare.
          continue;
        }
        declared.add(new Declared(branch, entry.path(), entry.name()));
      }
    }

    Map<String, List<Change>> wanted = new LinkedHashMap<>();
    for (String branch : subject.sourceBranches()) {
      wanted.put(branch, new ArrayList<>());
    }
    Map<String, TreeLookup> listings = new HashMap<>();
    for (Candidate candidate : released(wrapper, declared)) {
      String head = heads.get(candidate.branch());
      int slash = candidate.path().lastIndexOf('/');
      String directory = slash < 0 ? "" : candidate.path().substring(0, slash);
      String name = slash < 0 ? candidate.path() : candidate.path().substring(slash + 1);
      TreeLookup listing =
          listings.computeIfAbsent(
              head + ":" + directory,
              ignored -> gitHost.tree(wrapper.project, wrapper.name, head, directory));
      if (listing.status() == FileLookup.Status.UNREACHABLE
          || listing.status() == FileLookup.Status.INVALID) {
        return Plan.unknown(
            "the pin at " + candidate.path() + " on " + candidate.branch()
                + " could not be read: " + describe(listing.status(), listing.message()));
      }
      // A directory or an entry that is not there, or one that is no gitlink, is "nothing is pinned
      // here" — a declaration whose gitlink has not been committed yet — and a difference like any
      // other: the entry wants the released sha and does not have it.
      String pinned =
          listing.found()
              ? listing
                  .entry(name)
                  .filter(TreeLookup.TreeEntry::isGitlink)
                  .map(TreeLookup.TreeEntry::sha)
                  .orElse(null)
              : null;
      if (Objects.equals(pinned, candidate.releasedSha())) {
        continue;
      }
      wanted.get(candidate.branch()).add(change(candidate.path(), candidate.name(), pinned,
          candidate.version()));
    }

    List<Plan.Run> runs = new ArrayList<>();
    for (Map.Entry<String, List<Change>> entry : wanted.entrySet()) {
      // The terminating case: never a run with an empty change list.
      if (!entry.getValue().isEmpty()) {
        runs.add(new Plan.Run(entry.getKey(), entry.getValue(), Map.of()));
      }
    }
    return runs.isEmpty()
        ? Plan.fresh("the estate pins already name what the members released")
        : Plan.runs(runs);
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
              entry.branch(),
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
