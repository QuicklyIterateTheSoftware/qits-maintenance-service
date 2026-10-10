package eu.wohlben.qits.maintenance.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.maintenance.bump.BumpPayload;
import eu.wohlben.qits.maintenance.bump.BumpService;
import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.bump.ReleaseRequestClient;
import eu.wohlben.qits.maintenance.bump.changelog.ChangelogRanges;
import eu.wohlben.qits.maintenance.config.MaintenanceConfig;
import eu.wohlben.qits.maintenance.dto.FailureDto;
import eu.wohlben.qits.maintenance.dto.ReleaseRequestAutomationsDto;
import eu.wohlben.qits.maintenance.dto.ReleaseRequestAutomationsDto.AutomationDto;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.entity.MtReleaseRequest;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.error.AutomationNotRunnableException;
import eu.wohlben.qits.maintenance.error.BadRequestException;
import eu.wohlben.qits.maintenance.error.BumpDisabledException;
import eu.wohlben.qits.maintenance.error.NoSuchAutomationException;
import eu.wohlben.qits.maintenance.error.NoSuchRepositoryException;
import eu.wohlben.qits.maintenance.error.ReleaseRequestNotOpenException;
import eu.wohlben.qits.maintenance.githost.GitHostReader;
import eu.wohlben.qits.maintenance.githost.TreeLookup;
import eu.wohlben.qits.maintenance.model.BumpMode;
import eu.wohlben.qits.maintenance.model.BumpStatus;
import eu.wohlben.qits.maintenance.model.BumpTrigger;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.pending.Change;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore.AutomationOpening;
import eu.wohlben.qits.maintenance.scan.ScanService;
import eu.wohlben.qits.maintenance.scan.ScanTrigger;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

/**
 * <b>The one engine every release-request automation runs on</b> (epic qits-978): decide, open,
 * dispatch, follow, end, join — written once, for every kind.
 *
 * <p>Before this, the two regenerations that land inside a release request were two bump MODES with
 * their dispatch and their ending written twice ({@code finishTargeted}, {@code dispatchBaselines}/
 * {@code finishBaselines}) and an {@code if} in front of each. They differed in exactly one fact —
 * whose branch the commit lands on — which is now the kind's {@link Target}, and that is the only
 * place this class asks a kind how to read an ending.
 *
 * <h2>Settling a fold</h2>
 *
 * <p>qits-projects posts every fold of every request ({@link #trigger}). For each registered kind,
 * in this order:
 *
 * <ol>
 *   <li><b>Applicability</b>, from the repository at the fold. NOT_APPLICABLE is left out of the
 *       answer; UNKNOWN is answered and not stored, so the sweep over there asks again.
 *   <li><b>Carry-over — the main terminator.</b> An automation's own commit re-folds the request,
 *       which asks every automation again. When this kind's outcome on the previous fold was FRESH or
 *       COMMITTED and every path that changed since lies under the union of the applicable kinds'
 *       {@code committablePaths}, the new fold is FRESH with no run. It holds even when a generator
 *       is not byte-deterministic, and it rests on one invariant: no automation's output is another
 *       automation's input. An unknown diff never carries.
 *   <li><b>The plan</b>, which may answer FRESH with no run — estate pins ends its loop that way.
 *   <li><b>A row</b>: FRESH recorded, or REQUESTED and queued.
 * </ol>
 *
 * <p>Idempotent per (request, fold): a fold that already has rows is answered from them, so the
 * thirty-second re-ask costs one read.
 *
 * <h2>Two stages (qits-1133)</h2>
 *
 * <p>The kinds are settled in their {@link Stage} order: every SOURCE kind first ({@code
 * estate-pins}, {@code dependency-bump} — build inputs), then every DERIVED one ({@code
 * screenshot-baselines}, {@code entity-diagram} — output built from those inputs). A DERIVED kind is
 * neither planned nor stored while any applicable SOURCE kind is not FRESH at the fold: it answers
 * WAITING, and the ask after the sources settle decides it. A SOURCE commit is a new fold, so the
 * DERIVED kinds run again on what it wrote; their carry-over is over their own stage's paths (and
 * their declared inputs), never over a SOURCE commit's. A kind that declares {@link
 * ReleaseRequestAutomation#inputPaths() inputs} carries over exactly when the fold touched none of
 * them. A plan that would write another applicable kind's path is refused FAILED before it runs.
 *
 * <h2>Concurrency, on a platform whose CI slots are few</h2>
 *
 * <p><b>At most one RUNNING run per (request, kind)</b> — per branch, which for a kind's own branch
 * is the same thing. A newer fold arriving while one runs waits REQUESTED, and only the newest
 * waiting fold is kept: the older become SUPERSEDED. Nothing is cancelled (qits-ci's cancel door is a
 * person's); a stale run is simply discarded when it ends, and its push, if any, re-folds. <b>At most
 * {@value #MAX_RUNNING} automation runs RUNNING estate-wide</b>, a code constant rather than a knob;
 * the rest wait and the ordinary bump sweep dispatches them. Both obey {@code
 * qits.maintenance.bump.enabled}.
 *
 * <h2>The ending</h2>
 *
 * <p>One method, reading the target. OWN_BRANCH compares its branch head (unmoved is FRESH —
 * nothing to do — moved is a join) and SOURCE_BRANCHES takes the run's verdict and the commit it
 * left. Red is FAILED, and FAILED HOLDS the request: a push re-folds it, the re-run door retries it,
 * a person may waive it.
 *
 * <p><b>A green outcome is recorded against its OWN fold even when the request has moved on</b>,
 * because the commonest mover is the run itself: estate pins push onto a source branch, and once a
 * kind's own branch is a source of the request every later run's push re-folds it before the poll
 * sees the run end. Superseding those would leave the next fold nothing to carry from and cost a
 * second run on every push. It is safe whatever moved the fold — the outcome is a statement about
 * the fold the run fetched (the core refuses a fold that is no longer {@code foldSha} before it
 * starts), the gate only ever reads the rows of the sha it is about to release, and carry-over onto
 * the newer fold still demands that every changed path is automation output, so a person's push is
 * never carried. <b>Only a RED run on a fold that moved on is SUPERSEDED</b>: the core's own "superseded
 * before start" refusal is red, and a red verdict about a fold nobody will release must not hold
 * the request.
 *
 * <p><b>The circuit breaker</b> is the third terminator, behind carry-over and the plans: a fourth
 * consecutive COMMITTED for one (request, kind) whose folds changed nothing but automation output is
 * FAILED, "not converging after 3 commits", and is not re-triggered by the folds such a loop keeps
 * producing.
 */
@ApplicationScoped
public class AutomationService {

  private static final Logger LOG = Logger.getLogger(AutomationService.class);

  /** Every automation's own branch is under this prefix, then the kind, then the request. */
  public static final String BRANCH_PREFIX = "maintenance/automations/";

  /** The branch qits-projects folds a release request into, before the request id. */
  public static final String FOLD_BRANCH_PREFIX = "release/";

  /**
   * How many automation runs may be RUNNING at once across the estate. A code constant on purpose:
   * qits-ci's slots are few and shared with every release request's QA, and this is the one number
   * that keeps a burst of folds from taking all of them.
   */
  public static final int MAX_RUNNING = 2;

  /** The breaker trips on the commit after this many. */
  public static final int BREAKER_COMMITS = 3;

  /** What a tripped breaker says, and what keeps it tripped on the next automation-only fold. */
  public static final String NOT_CONVERGING = "not converging after " + BREAKER_COMMITS + " commits";

  /** A release request id as qits-projects mints it. */
  private static final Pattern REQUEST_ID =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

  /** A work item's qualified id, the scope of a commit subject: {@code qits-112}. */
  private static final Pattern WORK_ITEM = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9-]*-[0-9]{1,18}$");

  /** A full commit sha, sha-1 or sha-256. The shared core checks the same before it fetches. */
  private static final Pattern SHA = Pattern.compile("^[0-9a-f]{40}(?:[0-9a-f]{24})?$");

  /** The states in which a release request still takes a branch. */
  private static final Set<String> OPEN =
      Set.of("PENDING", "READY", "REJECTED", "FAILED", "CONFLICTED");

  /** Which state of several rows a kind's fold reads as, most telling first. */
  private static final List<AutomationState> PRECEDENCE =
      List.of(
          AutomationState.SUPERSEDED,
          AutomationState.FAILED,
          AutomationState.RUNNING,
          AutomationState.REQUESTED,
          AutomationState.COMMITTED,
          AutomationState.FRESH);

  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject MaintenanceStore store;

  @Inject MaintenanceConfig config;

  @Inject CiClient ci;

  @Inject GitHostReader gitHost;

  @Inject ReleaseRequestClient releases;

  @Inject WorkQueue queue;

  /** What a trigger for a repository no scan has read yet asks to read it. */
  @Inject ScanService scans;

  /** Which changelogs a source-branch run's changes pull in, and the proof they exist (qits-893). */
  @Inject ChangelogRanges changelogRanges;

  @Inject @Any Instance<ReleaseRequestAutomation> registered;

  /**
   * One fold, as qits-projects posts it.
   *
   * @param repository the repository, as the catalog spells it
   * @param foldSha the request's merged sha
   * @param previousFoldSha the fold before it, or null when this is the first
   * @param changedSincePrevious every path that changed between the two, or null when there was no
   *     previous fold or the diff could not be read — which never carries
   * @param sourceBranches the request's named branches
   * @param workItem the work item a commit subject names, or null
   * @param accepts the answer words the caller understands beyond the old ones — {@code WAITING},
   *     {@code NOT_APPLICABLE} (qits-1133); empty for a caller that predates them
   */
  public record Fold(
      String repository,
      String foldSha,
      String previousFoldSha,
      List<String> changedSincePrevious,
      List<String> sourceBranches,
      String workItem,
      List<String> accepts) {

    public Fold {
      accepts = accepts == null ? List.of() : List.copyOf(accepts);
    }

    /** A fold from a caller that accepts no new word: the answer is exactly the old one. */
    public Fold(
        String repository,
        String foldSha,
        String previousFoldSha,
        List<String> changedSincePrevious,
        List<String> sourceBranches,
        String workItem) {
      this(
          repository, foldSha, previousFoldSha, changedSincePrevious, sourceBranches, workItem,
          List.of());
    }
  }

  /** Which of the qits-1133 words a caller said it understands. */
  private static Set<AutomationState> accepted(List<String> accepts) {
    Set<AutomationState> words = new LinkedHashSet<>();
    for (String word : accepts == null ? List.<String>of() : accepts) {
      if (word == null) {
        continue;
      }
      String value = word.trim().toUpperCase(java.util.Locale.ROOT);
      if (AutomationState.WAITING.name().equals(value)) {
        words.add(AutomationState.WAITING);
      } else if (AutomationState.NOT_APPLICABLE.name().equals(value)) {
        words.add(AutomationState.NOT_APPLICABLE);
      }
    }
    return words;
  }

  /**
   * Every kind this build offers (registered and switched on), ordered by id — the order every
   * answer lists them in.
   */
  public List<ReleaseRequestAutomation> kinds() {
    return registeredKinds().stream().filter(ReleaseRequestAutomation::enabled).toList();
  }

  /** Every registered kind, switched on or not, ordered by id. */
  private List<ReleaseRequestAutomation> registeredKinds() {
    List<ReleaseRequestAutomation> all = new ArrayList<>();
    for (ReleaseRequestAutomation kind : registered) {
      all.add(kind);
    }
    all.sort(Comparator.comparing(ReleaseRequestAutomation::kind));
    return List.copyOf(all);
  }

  /** The kind offered under that id: registered and switched on. */
  public Optional<ReleaseRequestAutomation> kind(String kind) {
    return kinds().stream().filter(candidate -> candidate.kind().equals(kind)).findFirst();
  }

  /**
   * The kind registered under that id, switched on or not: what a row it already opened ends
   * against.
   */
  private Optional<ReleaseRequestAutomation> registeredKind(String kind) {
    return registeredKinds().stream()
        .filter(candidate -> candidate.kind().equals(kind))
        .findFirst();
  }

  // --- the every-fold trigger -------------------------------------------------------------------

  /**
   * A trigger for a repository the inventory does not hold yet, answered — and asked to be read.
   *
   * <p>A newly created repository's first release request is the ordinary case (qits-1118): nothing
   * else scans it before the nightly scan, so without this the request held on UNKNOWN until then.
   * The thirty-second re-ask is the debounce's other half — {@link MaintenanceStore#scanPending}
   * keeps it to one scan at a time, as on the bus, and a scan that fails (a name the catalog does
   * not list) closes FAILED, so the next ask queues another rather than waiting on it for ever.
   * INTERNAL scope for the bus's reason: every scan reads every manifest, and the external lookups
   * are the daily scan's job.
   */
  private NoSuchRepositoryException unscanned(String name) {
    if (!store.scanPending(name)) {
      UUID id = scans.request(ScanScope.INTERNAL, name, ScanTrigger.EVENT);
      LOG.infof(
          "a release request of %s was settled before any scan read it; queued the scan %s", name,
          id);
    }
    return NoSuchRepositoryException.scanQueued(name);
  }

  /**
   * Settles every kind at one fold of one request and answers where each stands.
   *
   * @throws BadRequestException not a request id, not a sha, not a work item — a 400
   * @throws NoSuchRepositoryException the inventory has no such repository yet — a 404, after
   *     queueing a scan of that one repository unless one is already queued or running
   */
  public ReleaseRequestAutomationsDto trigger(String requestId, Fold fold) {
    requireRequestId(requestId);
    if (fold == null) {
      throw new BadRequestException("the trigger names the repository and the fold");
    }
    String foldSha = requireSha("foldSha", fold.foldSha());
    String previous =
        fold.previousFoldSha() == null || fold.previousFoldSha().isBlank()
            ? null
            : requireSha("previousFoldSha", fold.previousFoldSha());
    String item = workItem(fold.workItem());
    if (fold.repository() == null || fold.repository().isBlank()) {
      throw new BadRequestException("the trigger names the repository the request belongs to");
    }
    String name = fold.repository().trim();
    MtRepository row = store.repository(name).orElseThrow(() -> unscanned(name));
    AutomationSubject subject = subject(row, requestId, foldSha, fold.sourceBranches(), item);

    // A kind this fold already has rows for is answered from them — and it applied, or it would
    // have none, so its paths join the union without asking the git host again.
    Set<String> stored = new LinkedHashSet<>();
    for (MtBump existing : store.automations(requestId, foldSha)) {
      stored.add(existing.automationKind);
    }
    List<ReleaseRequestAutomation> kinds = kinds();
    Map<String, Applicability> decided = new LinkedHashMap<>();
    for (ReleaseRequestAutomation kind : kinds) {
      decided.put(
          kind.kind(),
          stored.contains(kind.kind()) ? Applicability.applies() : applicability(kind, subject));
    }

    // THE UNION IS OVER THE KINDS THAT APPLY, and that is what lets one kind's commit be carried by
    // another: the re-fold a screenshot join causes changes only __screenshots__ paths, which lie
    // under the union, so estate pins is carried on it too rather than asked to plan again. A
    // DERIVED kind reads its own stage's union only (qits-1133): a SOURCE commit is a build input,
    // never "automation output" as far as what is built from it is concerned.
    Map<String, List<String>> paths = new LinkedHashMap<>();
    List<String> union = new ArrayList<>();
    List<String> derivedUnion = new ArrayList<>();
    for (ReleaseRequestAutomation kind : kinds) {
      if (decided.get(kind.kind()).applied()) {
        List<String> own = committablePaths(kind, subject);
        paths.put(kind.kind(), own);
        union.addAll(own);
        if (kind.stage() == Stage.DERIVED) {
          derivedUnion.addAll(own);
        }
      }
    }

    Map<String, String> unknown = new LinkedHashMap<>();
    Map<String, String> waiting = new LinkedHashMap<>();
    Map<String, String> notApplicable = new LinkedHashMap<>();
    // SOURCE FIRST, then DERIVED behind it: what the first stage writes is what the second is built
    // from, so the second is not asked until the first is FRESH at this fold.
    for (ReleaseRequestAutomation kind : kinds) {
      if (kind.stage() != Stage.DERIVED) {
        decide(kind, subject, previous, fold.changedSincePrevious(), stored, decided, paths, union,
            derivedUnion, unknown, notApplicable, null, waiting);
      }
    }
    String hold = sourcesNotFresh(kinds, decided, requestId, foldSha);
    if (hold == null && withdrawIfNothingToBump(row, requestId)) {
      hold = "the release request was withdrawn: its pre-run found nothing to bump";
    }
    for (ReleaseRequestAutomation kind : kinds) {
      if (kind.stage() == Stage.DERIVED) {
        decide(kind, subject, previous, fold.changedSincePrevious(), stored, decided, paths, union,
            derivedUnion, unknown, notApplicable, hold, waiting);
      }
    }
    return answer(requestId, foldSha, unknown, waiting, notApplicable, accepted(fold.accepts()));
  }

  /** One kind at the fold, after applicability: answered from rows, listed, or settled. */
  private void decide(
      ReleaseRequestAutomation kind,
      AutomationSubject subject,
      String previous,
      List<String> changed,
      Set<String> stored,
      Map<String, Applicability> decided,
      Map<String, List<String>> paths,
      List<String> union,
      List<String> derivedUnion,
      Map<String, String> unknown,
      Map<String, String> notApplicable,
      String hold,
      Map<String, String> waiting) {
    if (stored.contains(kind.kind())) {
      return;
    }
    Applicability applicability = decided.get(kind.kind());
    switch (applicability.state()) {
      case NOT_APPLICABLE -> notApplicable.put(kind.kind(), applicability.reason());
      case UNKNOWN -> unknown.put(kind.kind(), applicability.reason());
      case APPLIES -> {
        if (hold != null) {
          // WAITING is answered and never stored: the ask after the sources settle decides it.
          waiting.put(kind.kind(), hold);
          return;
        }
        Boolean carries = carries(kind, subject, previous, changed, union, derivedUnion);
        String undecided = settle(kind, subject, previous, carries, paths);
        if (undecided != null) {
          unknown.put(kind.kind(), undecided);
        }
      }
    }
  }

  /**
   * Whether the paths that changed since the previous fold let this kind carry its outcome over —
   * null when there was no previous fold or its diff could not be read, which never carries.
   *
   * <p>A kind that declares {@link ReleaseRequestAutomation#inputPaths() inputs} is asked the
   * precise question: did the fold touch anything it reads. Every other kind is asked the union
   * question — did only automation output change — over its own stage's union for a DERIVED kind
   * and over every applicable kind's for a SOURCE one.
   */
  private static Boolean carries(
      ReleaseRequestAutomation kind,
      AutomationSubject subject,
      String previous,
      List<String> changed,
      List<String> union,
      List<String> derivedUnion) {
    if (previous == null || changed == null) {
      return null;
    }
    List<String> inputs = inputPaths(kind, subject);
    if (inputs != null) {
      return changed.stream().noneMatch(path -> Pathspecs.matchesAny(inputs, path));
    }
    List<String> scope = kind.stage() == Stage.DERIVED ? derivedUnion : union;
    return changed.stream().allMatch(path -> Pathspecs.matchesAny(scope, path));
  }

  /**
   * Why the DERIVED kinds wait at this fold, or null when every applicable SOURCE kind is FRESH
   * there — the gate between the two halves of the pre-run (qits-1133).
   */
  private String sourcesNotFresh(
      List<ReleaseRequestAutomation> kinds,
      Map<String, Applicability> decided,
      String requestId,
      String foldSha) {
    List<String> behind = new ArrayList<>();
    for (ReleaseRequestAutomation kind : kinds) {
      if (kind.stage() == Stage.DERIVED) {
        continue;
      }
      Applicability applicability = decided.get(kind.kind());
      if (applicability.state() == Applicability.State.NOT_APPLICABLE) {
        continue;
      }
      AutomationState state =
          applicability.applied() ? foldState(requestId, kind.kind(), foldSha) : null;
      if (state != AutomationState.FRESH) {
        behind.add(
            kind.label().toLowerCase(java.util.Locale.ROOT) + " "
                + (state == null ? AutomationState.UNKNOWN : state));
      }
    }
    return behind.isEmpty()
        ? null
        : "waiting for the source automations to be fresh at this fold: " + String.join(", ", behind);
  }

  /**
   * One kind at one fold, after it was found to apply: carry-over, the tripped breaker, the plan, the
   * disjointness check, a row.
   *
   * @param carries whether the fold's changed paths let this kind carry over (see {@link #carries});
   *     stored on the row as {@code automation_only}, where the dispatch and the breaker read it
   * @param paths the committable paths of every kind that applies at this fold
   * @return why it could not be decided — answered UNKNOWN and not stored — or null
   */
  private String settle(
      ReleaseRequestAutomation kind,
      AutomationSubject subject,
      String previous,
      Boolean carries,
      Map<String, List<String>> paths) {
    Boolean automationOnly = carries;
    if (Boolean.TRUE.equals(automationOnly)) {
      AutomationState before = foldState(subject.requestId(), kind.kind(), previous);
      if (before != null && before.carries()) {
        record(kind, subject, previous, automationOnly, BumpStatus.NOTHING_TO_DO,
            carried(previous, before));
        return null;
      }
      Optional<MtBump> last =
          store.automationHistory(subject.requestId(), kind.kind()).stream()
              .filter(row -> !BumpStatus.SUPERSEDED.name().equals(row.status))
              .findFirst();
      if (last.isPresent()
          && BumpStatus.FAILED.name().equals(last.get().status)
          && last.get().message != null
          && last.get().message.startsWith(NOT_CONVERGING)) {
        // THE BREAKER STAYS TRIPPED on the folds such a loop keeps producing. Only a change that is
        // not automation output — a push — or the re-run door asks this kind to run again.
        record(kind, subject, previous, automationOnly, BumpStatus.FAILED, last.get().message);
        return null;
      }
    }
    Plan plan = plan(kind, subject);
    switch (plan.kind()) {
      case FRESH -> {
        record(kind, subject, previous, automationOnly, BumpStatus.NOTHING_TO_DO,
            "fresh: " + plan.reason());
        return null;
      }
      case UNKNOWN -> {
        return plan.reason();
      }
      default -> {}
    }
    String clash = clash(kind, plan, paths);
    if (clash != null) {
      // A PLAN THAT WOULD WRITE ANOTHER KIND'S PATH IS REFUSED, not run: two kinds committing one
      // path is what breaks carry-over for both, and a refusal on the row is a sentence a person
      // reads, where a quiet skip would be a pre-run that never finishes.
      record(kind, subject, previous, automationOnly, BumpStatus.FAILED, clash);
      LOG.warnf("The %s automation of %s at %s: %s", kind.kind(), subject.repository().name,
          subject.foldSha(), clash);
      return null;
    }
    if (!config.bumpEnabled()) {
      return "bumping is disabled (qits.maintenance.bump.enabled=false), so "
          + kind.label().toLowerCase() + " cannot be regenerated";
    }
    for (Plan.Run run : plan.runs()) {
      UUID id =
          open(kind, subject, previous, automationOnly, run, BumpTrigger.FOLD, false);
      queueDispatch(id);
    }
    return null;
  }

  /**
   * <b>Disjointness, at run time</b> (qits-1133): the sentence when a RUN plan names a path another
   * applicable kind commits for this subject, or null. The registry test holds the static pathspecs
   * apart; this holds apart what a plan actually wants to write — a gitlink a wrapper's estate pins
   * owns, a manifest under another kind's output — which only the fold can say.
   *
   * @param paths every applicable kind's committable paths at this fold, the planning kind included
   */
  public static String clash(
      ReleaseRequestAutomation kind, Plan plan, Map<String, List<String>> paths) {
    for (Plan.Run run : plan.runs()) {
      for (String path : plannedPaths(run)) {
        for (Map.Entry<String, List<String>> other : paths.entrySet()) {
          if (other.getKey().equals(kind.kind())) {
            continue;
          }
          if (Pathspecs.matchesAny(other.getValue(), path)) {
            return "refused: " + kind.kind() + " planned to write " + path + ", which "
                + other.getKey() + " commits — the kinds' paths must stay disjoint, so nothing was"
                + " run";
          }
        }
      }
    }
    return null;
  }

  /** The files one run would write: its planned {@code commitPaths}, or its changes' manifests. */
  private static List<String> plannedPaths(Plan.Run run) {
    Object planned = run.extras().get(DependencyBumpAutomation.COMMIT_PATHS);
    List<String> out = new ArrayList<>();
    if (planned instanceof List<?> list) {
      for (Object path : list) {
        if (path != null) {
          out.add(path.toString());
        }
      }
      return out;
    }
    for (Change change : run.changes()) {
      if (change.manifestPath() != null) {
        out.add(change.manifestPath());
      }
    }
    return out;
  }

  // --- the read door ----------------------------------------------------------------------------

  /**
   * Where one request's automations stand at one fold — the newest fold anything was asked about
   * when none is named.
   */
  public ReleaseRequestAutomationsDto automations(String requestId, String foldSha) {
    requireRequestId(requestId);
    String fold =
        foldSha == null || foldSha.isBlank()
            ? store.newestAutomation(requestId).map(row -> row.foldSha).orElse(null)
            : requireSha("foldSha", foldSha);
    if (fold == null) {
      return new ReleaseRequestAutomationsDto(requestId, null, List.of());
    }
    return answer(requestId, fold, Map.of(), Map.of(), Map.of(), Set.of());
  }

  // --- the re-run door --------------------------------------------------------------------------

  /**
   * Runs one kind on a request's CURRENT fold now, and does not wait.
   *
   * <p><b>It skips carry-over and applicability</b>, so a repository's FIRST screenshot references
   * come from here: the kind applies only once the fold carries a renderer record, which is what the
   * first run writes. The kind's own precondition still holds over there — a fold with no {@code
   * test:browser} fails the run with a sentence. A kind that writes the request's own branches is
   * still planned, because its plan IS its change list.
   *
   * @param repository the repository, or null: it is then the one this request's automations were
   *     last asked about
   * @throws BadRequestException not a request id, or not a work item — a 400
   * @throws NoSuchAutomationException no such kind — a 404
   * @throws NoSuchRepositoryException no such repository, or none known for the request — a 404
   * @throws ReleaseRequestNotOpenException the request is settled or cannot be read — a 409
   * @throws eu.wohlben.qits.maintenance.error.BumpAlreadyActiveException one is active for (request,
   *     kind) — a 409
   * @throws BumpDisabledException {@code qits.maintenance.bump.enabled} is false — a 409
   * @throws AutomationNotRunnableException no fold yet, or a plan that could not be decided — a 409
   */
  public UUID run(
      String repository, String requestId, String kindName, String workItem, BumpTrigger trigger) {
    return start(repository, requestId, kindName, workItem, trigger, false);
  }

  /**
   * <b>Re-plans one kind on a request's CURRENT fold</b> — the upstream hook's door (qits-1133). The
   * re-run door's path, with one difference that is the whole point: the PLAN is honoured. A FRESH
   * plan opens nothing and answers null, because an upstream release that this request's fold
   * already carries (or holds) is not a reason to spend a CI run; a RUN plan is held to the
   * disjointness rule before it is opened. The work item is the one the request's automations
   * last named.
   *
   * @return the first row opened, or null when the plan was FRESH
   * @throws AutomationNotRunnableException no fold, an undecidable plan, or a plan that would write
   *     another kind's path
   * @throws eu.wohlben.qits.maintenance.error.BumpAlreadyActiveException one is active already
   */
  public UUID replan(String repository, String requestId, String kindName, BumpTrigger trigger) {
    String item = store.newestAutomation(requestId).map(row -> row.workItem).orElse(null);
    return start(repository, requestId, kindName, item, trigger, true);
  }

  private UUID start(
      String repository,
      String requestId,
      String kindName,
      String workItem,
      BumpTrigger trigger,
      boolean honourPlan) {
    if (!config.bumpEnabled()) {
      throw new BumpDisabledException();
    }
    requireRequestId(requestId);
    String item = workItem(workItem);
    ReleaseRequestAutomation kind =
        kind(kindName).orElseThrow(() -> new NoSuchAutomationException(kindName));
    String name =
        repository != null && !repository.isBlank()
            ? repository.trim()
            : store
                .newestAutomation(requestId)
                .map(row -> row.repository)
                .orElseThrow(() -> NoSuchRepositoryException.forRequest(requestId));
    MtRepository row =
        store.repository(name).orElseThrow(() -> new NoSuchRepositoryException(name));
    if (row.catalogId == null || row.catalogId.isBlank()) {
      throw new ReleaseRequestNotOpenException(
          name + " has no catalog id, so its release requests cannot be addressed");
    }
    ReleaseRequestClient.ReleaseState state = releases.state(row.catalogId, requestId);
    if (!state.readable()) {
      throw new ReleaseRequestNotOpenException(state.sentence());
    }
    if (!OPEN.contains(state.state())) {
      throw new ReleaseRequestNotOpenException(
          "the release request " + requestId + " is " + state.state() + " and takes no branch");
    }
    if (state.mergedSha() == null) {
      throw new AutomationNotRunnableException(
          "the release request " + requestId + " has not been folded yet; there is no fold to run on");
    }
    AutomationSubject subject = subject(row, requestId, state.mergedSha(), state.branches(), item);

    Plan plan = plan(kind, subject);
    List<Plan.Run> runs;
    if (honourPlan) {
      switch (plan.kind()) {
        case FRESH -> {
          LOG.infof(
              "Re-planned %s for %s of %s at %s on %s: fresh, nothing opened (%s)",
              kind.kind(), requestId, name, state.mergedSha(), trigger, plan.reason());
          return null;
        }
        case UNKNOWN ->
            throw new AutomationNotRunnableException(
                kind.label() + " could not be planned: " + plan.reason());
        default -> {}
      }
      String clash = clash(kind, plan, applicablePaths(subject));
      if (clash != null) {
        throw new AutomationNotRunnableException(clash);
      }
      runs = plan.runs();
    } else if (kind.target() == Target.SOURCE_BRANCHES) {
      switch (plan.kind()) {
        case FRESH -> {
          return openOutcome(kind, subject, trigger, BumpStatus.NOTHING_TO_DO,
              "fresh: " + plan.reason());
        }
        case UNKNOWN ->
            throw new AutomationNotRunnableException(
                kind.label() + " could not be planned: " + plan.reason());
        default -> runs = plan.runs();
      }
    } else {
      // A re-run is asked for because somebody wants it RUN: a FRESH or undecided plan does not
      // talk an own-branch kind out of it, and only a RUN plan's own payload fields are kept.
      runs =
          plan.kind() == Plan.Kind.RUN
              ? plan.runs()
              : List.of(new Plan.Run(null, List.of(), Map.of()));
    }
    UUID first = null;
    for (Plan.Run run : runs) {
      // EXCLUSIVE FOR THE FIRST RUN ONLY: that is the "one is already active" 409. A plan of two
      // branches opens its second beside its first, which would otherwise refuse it.
      UUID id = open(kind, subject, null, null, run, trigger, first == null);
      first = first == null ? id : first;
      queueDispatch(id);
    }
    LOG.infof(
        "Opened the %s re-run of %s for %s of %s at %s",
        trigger, kind.kind(), requestId, name, state.mergedSha());
    return first;
  }

  // --- dispatch -----------------------------------------------------------------------------------

  /** Queues one row's dispatch on the worker. */
  private void queueDispatch(UUID id) {
    queue.submit("automation " + id, () -> dispatch(id));
  }

  /** Sends one automation row to qits-ci, or leaves it waiting. */
  public void dispatch(UUID id) {
    store.bump(id).ifPresent(this::dispatch);
  }

  /**
   * Sends one automation row to qits-ci — or leaves it REQUESTED, which is waiting: behind a run of
   * the same branch, behind the estate-wide cap, or while bumping is switched off. The sweep asks
   * again, and so does every ending.
   */
  public void dispatch(MtBump row) {
    if (!BumpStatus.REQUESTED.name().equals(row.status)
        || !BumpMode.AUTOMATION.name().equals(row.mode)) {
      return;
    }
    if (!config.bumpEnabled()) {
      return;
    }
    if (registeredKind(row.automationKind).filter(found -> !found.enabled()).isPresent()) {
      // Switched off after the row was opened: it ends FRESH rather than FAILED, so it holds
      // nothing.
      store.bumpFinished(
          row.id,
          BumpStatus.NOTHING_TO_DO,
          null,
          "the " + row.automationKind + " automation is switched off; nothing was run",
          Instant.now());
      return;
    }
    ReleaseRequestAutomation kind = kind(row.automationKind).orElse(null);

    // CARRY-OVER, ASKED AGAIN. The trigger asked it too, but the fold this one was opened behind may
    // have had no outcome yet: an own-branch run joins and only then records COMMITTED, and the
    // re-fold its join causes can be posted in between. Asked here, that race costs no CI run.
    if (BumpTrigger.FOLD.name().equals(row.trigger)
        && Boolean.TRUE.equals(row.automationOnly)
        && row.previousFoldSha != null) {
      AutomationState before =
          foldState(row.releaseRequestId, row.automationKind, row.previousFoldSha);
      if (before != null && before.carries()) {
        store.bumpFinished(
            row.id,
            BumpStatus.NOTHING_TO_DO,
            null,
            carried(row.previousFoldSha, before),
            Instant.now());
        return;
      }
    }
    if (store.runningAutomation(row.repository, row.automationKind, row.branch).isPresent()) {
      return;
    }
    if (store.runningAutomationCount() >= MAX_RUNNING) {
      LOG.debugf("The automation %s waits: %d already run", row.id, MAX_RUNNING);
      return;
    }
    Optional<MtRepository> repository = store.repository(row.repository);
    if (repository.isEmpty()) {
      store.bumpFinished(
          row.id, BumpStatus.FAILED, null, "the repository left the inventory", Instant.now());
      return;
    }
    if (target(row, kind) == Target.OWN_BRANCH) {
      dispatchOwnBranch(row, kind, repository.get());
    } else {
      dispatchSourceBranch(row, kind, repository.get());
    }
  }

  /**
   * An own-branch run on qits-ci's shared core. The branch head is read first: nothing else writes
   * {@code maintenance/automations/<kind>/<request>}, so the ending can tell a run that pushed from
   * one that found nothing to write.
   */
  private void dispatchOwnBranch(
      MtBump row, ReleaseRequestAutomation kind, MtRepository repository) {
    if (kind == null) {
      fail(row, "no automation of kind " + row.automationKind + " is registered in this build");
      return;
    }
    if (row.releaseRequestId == null || row.foldSha == null) {
      fail(row, "an own-branch automation runs on a release request's fold, and this row names none");
      return;
    }
    if (!CiClient.AUTOMATION_EVENT_NAME.equals(kind.pipeline())) {
      fail(row, kind.kind() + " names the pipeline " + kind.pipeline()
          + ", which does not write a branch of its own");
      return;
    }
    String baseRef = FOLD_BRANCH_PREFIX + row.releaseRequestId;
    List<String> problems = BumpPayload.problems(row.automationKind, row.branch, baseRef, List.of());
    if (!problems.isEmpty()) {
      fail(row, String.join("; ", problems));
      return;
    }
    store.bumpStartHead(row.id, branchHead(repository, row.branch));
    AutomationSubject subject =
        subject(repository, row.releaseRequestId, row.foldSha, List.of(), row.workItem);
    // A PLAN THAT NAMED ITS FILES STAGES THOSE AND NOTHING ELSE (qits-1133): the dependency bump's
    // `commitPaths` are the manifests its changes touch, frozen on the row at the plan, and they
    // replace the kind's whole pathspec list. A plan's `changes` ride beside them in the
    // MaintenanceBump entry shape — the same records the bump pipeline has always been sent.
    Map<String, Object> extras = new LinkedHashMap<>(extras(row));
    List<String> commitPaths = committablePaths(kind, subject);
    Object planned = extras.remove(DependencyBumpAutomation.COMMIT_PATHS);
    if (planned instanceof List<?> list && !list.isEmpty()) {
      commitPaths = list.stream().map(String::valueOf).toList();
    }
    List<Change> changes = BumpService.changes(row);
    if (!changes.isEmpty()) {
      extras.put("changes", changes);
    }
    CiClient.TriggerResult result =
        ci.triggerAutomation(
            row.id.toString(),
            row.automationKind,
            row.repository,
            row.releaseRequestId,
            row.foldSha,
            baseRef,
            row.branch,
            commitPaths,
            row.workItem,
            extras);
    dispatched(row, result);
  }

  /**
   * A run that writes one of the request's own branches, in place, through the bump pipeline — the
   * payload the TARGETED mode sent, unchanged, so {@code maintenance-bump.yml} and the commit
   * subject it prints ({@code bump(targeted): N dependencies}) are what they were.
   */
  private void dispatchSourceBranch(
      MtBump row, ReleaseRequestAutomation kind, MtRepository repository) {
    List<Change> changes = BumpService.changes(row);
    if (changes.isEmpty()) {
      // Nothing to write, and no run is started for it: the honest ending of an empty list, and the
      // one a plan never asks for — it answers FRESH instead.
      store.bumpFinished(
          row.id,
          BumpStatus.NOTHING_TO_DO,
          null,
          "the bump named no changes to write onto " + row.branch,
          Instant.now());
      return;
    }
    String pipeline = kind == null ? CiClient.EVENT_NAME : kind.pipeline();
    if (!CiClient.EVENT_NAME.equals(pipeline)) {
      fail(row, row.automationKind + " names the pipeline " + pipeline
          + ", which does not write a request's own branches");
      return;
    }
    String baseRef = baseRef(repository);
    List<String> problems =
        BumpPayload.problems(EstatePinsAutomation.TARGETED_GROUP, row.branch, baseRef, changes);
    if (!problems.isEmpty()) {
      fail(row, String.join("; ", problems));
      return;
    }
    // THE CHANGELOGS, as a group bump proves them (qits-893): a missing one FAILS the row with the
    // sentence naming it, an unreadable docs store leaves it REQUESTED for the sweep — the same two
    // answers a refused payload and a 503 get — and nothing is triggered by either.
    ChangelogRanges.Result changelogs = changelogRanges.resolve(changes);
    List<String> changelogProblems = new ArrayList<>(changelogs.problems());
    changelogProblems.addAll(BumpPayload.changelogProblems(changelogs.ranges()));
    if (!changelogProblems.isEmpty()) {
      fail(row, String.join("; ", changelogProblems));
      LOG.warnf("The automation %s was not sent: %s", row.id, changelogProblems);
      return;
    }
    if (changelogs.transientFailure()) {
      store.bumpFinished(
          row.id, BumpStatus.REQUESTED, null, BumpService.CHANGELOGS_UNREADABLE, Instant.now());
      return;
    }
    Map<String, String> extra = new LinkedHashMap<>();
    extra.put("kind", row.automationKind);
    if (row.releaseRequestId != null) {
      extra.put("requestId", row.releaseRequestId);
    }
    if (row.foldSha != null) {
      extra.put("foldSha", row.foldSha);
    }
    CiClient.TriggerResult result =
        ci.trigger(
            row.id.toString(),
            row.repository,
            EstatePinsAutomation.TARGETED_GROUP,
            row.branch,
            baseRef,
            changes,
            extra,
            changelogs.ranges());
    dispatched(row, result);
  }

  /** What qits-ci said to a trigger, onto the row — the three answers every bump reads alike. */
  private void dispatched(MtBump row, CiClient.TriggerResult result) {
    switch (result.outcome()) {
      case ACCEPTED -> {
        store.bumpDispatched(row.id, result.eventId(), result.runIds());
        LOG.infof(
            "qits-ci accepted the %s automation %s of %s as run(s) %s",
            row.automationKind, row.id, row.repository, result.runIds());
      }
      case RETRY ->
          // Still REQUESTED, same event id next time. The sweep sends it again.
          store.bumpFinished(row.id, BumpStatus.REQUESTED, null, result.message(), Instant.now());
      case FAILED -> {
        fail(row, result.message());
        LOG.warnf("The automation %s failed at the trigger: %s", row.id, result.message());
      }
    }
  }

  /** Every waiting row, offered a dispatch — what an ending does, so the newest fold goes next. */
  public void dispatchWaiting() {
    for (MtBump row : store.waitingAutomations()) {
      dispatch(row);
    }
  }

  // --- the ending ---------------------------------------------------------------------------------

  /**
   * The verdict, once every run is terminal: what the TARGET says a green run means — against the
   * run's own fold, whether or not the request has moved on (see the class javadoc) — and for a red
   * one SUPERSEDED when the fold moved on, FAILED when it did not.
   *
   * <p>A red run says why (qits-1116): the failing step, its exit code and the first line of its
   * excerpt go into the sentence, and the whole failure onto the row's own columns, where {@code
   * GET /bumps/{id}} and the automations answer read it. A null failure — a run whose steps named
   * none — keeps the sentence it always had.
   */
  public void finish(MtBump row, boolean passed, String ciRunStatus, CiClient.Failure failure) {
    ReleaseRequestAutomation kind = registeredKind(row.automationKind).orElse(null);
    Target target = target(row, kind);
    Optional<MtRepository> repository = store.repository(row.repository);
    Instant now = Instant.now();
    String label = kind == null ? row.automationKind : kind.label();

    String moved = passed ? null : movedFold(row, repository);
    if (moved != null) {
      store.bumpFinished(
          row.id,
          BumpStatus.SUPERSEDED,
          ciRunStatus,
          "superseded: release request " + row.releaseRequestId + " moved on to fold "
              + abbreviate(moved) + " before this run ended " + ciRunStatus,
          now);
      LOG.infof("The %s automation %s was superseded by fold %s", row.automationKind, row.id, moved);
    } else if (!passed) {
      store.bumpFailed(
          row.id,
          ciRunStatus,
          failedMessage(target == Target.OWN_BRANCH ? label : null, ciRunStatus, failure),
          failure,
          now);
      LOG.warnf("The %s automation %s of %s ended %s", row.automationKind, row.id, row.repository,
          ciRunStatus);
    } else if (target == Target.OWN_BRANCH) {
      finishOwnBranch(row, label, repository, ciRunStatus, now);
    } else {
      finishSourceBranch(row, repository, ciRunStatus, now);
    }
    // An ending frees a slot, and the newest waiting fold of this request may be behind it.
    queue.submit("dispatch the waiting automations", this::dispatchWaiting);
  }

  /**
   * The sentence of a red run: {@code the <label> run ended FAILED at step 0 (exit 1): <line>} on
   * an own-branch kind, {@code the ci run ended FAILED at step 0 (exit 1): <line>} on a source-branch
   * one. With no failure it is exactly the sentence before qits-1116.
   *
   * @param label the kind's label on an own-branch kind, null on a source-branch one
   */
  static String failedMessage(String label, String ciRunStatus, CiClient.Failure failure) {
    String run = label == null ? "the ci run" : "the " + label.toLowerCase() + " run";
    if (failure == null) {
      return label == null
          ? run + " ended " + ciRunStatus
          : run + " ended " + ciRunStatus + "; its step log says why";
    }
    StringBuilder message =
        new StringBuilder(run).append(" ended ").append(ciRunStatus)
            .append(" at step ").append(failure.stepIndex());
    if (failure.exitCode() != null) {
      message.append(" (exit ").append(failure.exitCode()).append(')');
    }
    String line = failure.firstLine();
    if (line != null) {
      message.append(": ").append(line);
    }
    return message.toString();
  }

  /**
   * A green own-branch run: unmoved is FRESH (NOTHING_TO_DO), moved is joined to the request. A join
   * qits-projects refuses fails the outcome with its reason; one it did not answer leaves it
   * COMMITTED with the reason on the row — the commit is on the branch, and joining by hand adds it.
   */
  private void finishOwnBranch(
      MtBump row, String label, Optional<MtRepository> repository, String ciRunStatus,
      Instant now) {
    String after = repository.map(repo -> branchHead(repo, row.branch)).orElse(null);
    String before = row.resultSha;
    if (after == null || after.equals(before)) {
      store.bumpFinished(
          row.id,
          BumpStatus.NOTHING_TO_DO,
          ciRunStatus,
          "unchanged: the run passed and " + row.branch + " did not move, so "
              + label.toLowerCase() + " are fresh at " + abbreviate(row.foldSha),
          before,
          now);
      if (DependencyBumpAutomation.KIND.equals(row.automationKind)) {
        repository.ifPresent(repo -> withdrawIfNothingToBump(repo, row.releaseRequestId));
      }
      return;
    }
    String tripped = breaker(row);
    if (tripped != null) {
      // Not joined: a fourth commit of a loop that is not converging is exactly what must not be
      // folded in again.
      store.bumpFinished(row.id, BumpStatus.FAILED, ciRunStatus, tripped, after, now);
      LOG.warnf("The %s automation %s: %s", row.automationKind, row.id, tripped);
      return;
    }
    String repoId = repository.map(repo -> repo.catalogId).orElse(null);
    ReleaseRequestClient.RequestResult joined =
        repoId == null || repoId.isBlank()
            ? new ReleaseRequestClient.RequestResult(
                ReleaseRequestClient.RequestResult.Outcome.REFUSED,
                ReleaseRequestClient.REFUSED,
                row.repository + " has no catalog id")
            // LOWEST, for every kind (qits-1133): a request's priority is the max over its named
            // branches, and an automation's branch is never what anybody is waiting for.
            : releases.join(repoId, row.releaseRequestId, row.branch, ReleaseRequestClient.LOWEST);
    store.bumpJoined(row.id, joined.outcome().name(), joined.message(), now);
    String base = label.toLowerCase() + " written on " + row.branch + " at " + after;
    switch (joined.outcome()) {
      case REQUESTED, CONVERGED ->
          store.bumpFinished(
              row.id,
              BumpStatus.SUCCEEDED,
              ciRunStatus,
              base + ", joined to release request " + row.releaseRequestId,
              after,
              now);
      case REFUSED ->
          store.bumpFinished(
              row.id, BumpStatus.FAILED, ciRunStatus, base + "; " + joined.message(), after, now);
      case RETRY ->
          store.bumpFinished(
              row.id,
              BumpStatus.SUCCEEDED,
              ciRunStatus,
              base + "; not joined yet: " + joined.message(),
              after,
              now);
    }
    LOG.infof("The %s automation %s of %s: %s", row.automationKind, row.id, row.repository,
        joined.message());
  }

  /**
   * A green run onto one of the request's own branches: <b>the run's verdict, and nothing about where
   * the head happens to be.</b> The branch belongs to whoever opened the request and moves while the
   * run goes, so a before/after comparison would credit their commits to this run, or call an
   * ff-rejection somebody's rewrite. The head is read once, afterwards, for the commit it reports; an
   * unreadable one costs the sha and a sentence, never the verdict.
   */
  private void finishSourceBranch(
      MtBump row, Optional<MtRepository> repository, String ciRunStatus, Instant now) {
    String after = repository.map(repo -> branchHead(repo, row.branch)).orElse(null);
    String tripped = breaker(row);
    if (tripped != null) {
      store.bumpFinished(row.id, BumpStatus.FAILED, ciRunStatus, tripped, after, now);
      LOG.warnf("The %s automation %s: %s", row.automationKind, row.id, tripped);
      return;
    }
    int written = BumpService.changes(row).size();
    String message =
        after == null
            ? written + " dependencies on " + row.branch
                + "; the run passed and its head could not be read"
            : written + " dependencies on " + row.branch + " at " + after;
    store.bumpFinished(row.id, BumpStatus.SUCCEEDED, ciRunStatus, message, after, now);
    LOG.infof(
        "The %s automation %s wrote %d change(s) onto %s of %s; it now stands at %s",
        row.automationKind, row.id, written, row.branch, row.repository,
        after == null ? "an unreadable head" : after);
  }

  /**
   * The fold the request has moved on to, or null when it has not — or when that cannot be said: a
   * row with no request or no fold (the targeted door's), or a qits-projects that would not answer.
   * Not being able to ask is not evidence the fold moved, and the ending carries on.
   */
  private String movedFold(MtBump row, Optional<MtRepository> repository) {
    if (row.releaseRequestId == null || row.foldSha == null) {
      return null;
    }
    String repoId = repository.map(repo -> repo.catalogId).orElse(null);
    if (repoId == null || repoId.isBlank()) {
      return null;
    }
    ReleaseRequestClient.ReleaseState state = releases.state(repoId, row.releaseRequestId);
    if (!state.readable() || state.mergedSha() == null) {
      return null;
    }
    return state.mergedSha().equals(row.foldSha) ? null : state.mergedSha();
  }

  /**
   * THE CIRCUIT BREAKER: the sentence when this COMMITTED would be the fourth in a row for one
   * (request, kind, branch) and the folds between them changed nothing but automation output — or
   * null. Carry-over makes that unreachable in the ordinary case; this is what stops a generator
   * that keeps disagreeing with itself when carry-over could not apply.
   */
  private String breaker(MtBump row) {
    if (!Boolean.TRUE.equals(row.automationOnly) || row.releaseRequestId == null) {
      return null;
    }
    List<MtBump> history = store.automationHistory(row.releaseRequestId, row.automationKind);
    List<MtBump> earlier = new ArrayList<>();
    boolean past = false;
    for (MtBump candidate : history) {
      if (candidate.id.equals(row.id)) {
        past = true;
        continue;
      }
      if (past
          && row.branch.equals(candidate.branch)
          && !BumpStatus.SUPERSEDED.name().equals(candidate.status)) {
        earlier.add(candidate);
      }
    }
    if (earlier.size() < BREAKER_COMMITS) {
      return null;
    }
    for (int index = 0; index < BREAKER_COMMITS; index++) {
      MtBump candidate = earlier.get(index);
      if (!BumpStatus.SUCCEEDED.name().equals(candidate.status)) {
        return null;
      }
      // The first commit of the chain may follow anybody's push; the ones after it must each have
      // been folded on automation output alone, or a person's work was between them.
      if (index < BREAKER_COMMITS - 1 && !Boolean.TRUE.equals(candidate.automationOnly)) {
        return null;
      }
    }
    return NOT_CONVERGING + ": each fold since the first changed only automation output, and "
        + row.automationKind + " wrote another commit every time; re-run it or push to try again";
  }

  // --- answers ------------------------------------------------------------------------------------

  /**
   * <b>Withdraws a request the upstream hook opened, once its pre-run found nothing to bump</b>
   * (qits-1133) — and answers whether the request is withdrawn by this service. A main-only request
   * exists only to carry the bump its pre-run writes, so a {@code dependency-bump} that ended FRESH
   * with no commit anywhere in the request's history leaves it nothing to release. Any other request
   * is never touched: a person's, a group bump's, or an upstream one whose bump already joined.
   */
  public boolean withdrawIfNothingToBump(MtRepository repository, String requestId) {
    if (requestId == null) {
      return false;
    }
    Optional<MtReleaseRequest> memo = store.releaseRequest(requestId);
    if (memo.isEmpty()
        || !memo.get().opened
        || !MtReleaseRequest.MAIN_ONLY.equals(memo.get().purpose)) {
      return false;
    }
    if (memo.get().withdrawnAt != null) {
      return true;
    }
    List<MtBump> history =
        store.automationHistory(requestId, DependencyBumpAutomation.KIND).stream()
            .filter(row -> !BumpStatus.SUPERSEDED.name().equals(row.status))
            .toList();
    if (history.isEmpty()
        || !BumpStatus.NOTHING_TO_DO.name().equals(history.getFirst().status)
        || history.stream().anyMatch(row -> BumpStatus.SUCCEEDED.name().equals(row.status))) {
      return false;
    }
    if (repository.catalogId == null || repository.catalogId.isBlank()) {
      return false;
    }
    String reason =
        "qits-maintenance opened this request for a dependency bump, and its pre-run found nothing"
            + " to bump at fold " + abbreviate(history.getFirst().foldSha);
    ReleaseRequestClient.RequestResult result =
        releases.withdraw(repository.catalogId, requestId, reason);
    switch (result.outcome()) {
      case REQUESTED, CONVERGED -> {
        store.requestWithdrawn(requestId, reason, Instant.now());
        LOG.infof("Withdrew the upstream release request %s of %s: %s", requestId,
            repository.name, result.message());
        return true;
      }
      default -> {
        LOG.warnf("Could not withdraw the upstream release request %s of %s: %s", requestId,
            repository.name, result.message());
        return false;
      }
    }
  }

  /**
   * Every kind with a row at this fold, plus the undecided ones, in kind order — and, for a caller
   * that accepts them (qits-1133), the waiting and the inapplicable ones.
   *
   * @param waiting DERIVED kinds held behind their SOURCE kinds, with why: WAITING to a caller that
   *     accepts it, REQUESTED to one that does not
   * @param notApplicable kinds that do not apply, with why: listed only for a caller that accepts it
   */
  private ReleaseRequestAutomationsDto answer(
      String requestId,
      String foldSha,
      Map<String, String> unknown,
      Map<String, String> waiting,
      Map<String, String> notApplicable,
      Set<AutomationState> accepts) {
    Map<String, List<MtBump>> byKind = new TreeMap<>();
    for (MtBump row : store.automations(requestId, foldSha)) {
      byKind.computeIfAbsent(row.automationKind, ignored -> new ArrayList<>()).add(row);
    }
    boolean listInapplicable = accepts.contains(AutomationState.NOT_APPLICABLE);
    Set<String> listed = new java.util.TreeSet<>(byKind.keySet());
    listed.addAll(unknown.keySet());
    listed.addAll(waiting.keySet());
    if (listInapplicable) {
      listed.addAll(notApplicable.keySet());
    }
    List<AutomationDto> entries = new ArrayList<>();
    for (String kind : listed) {
      String label = registeredKind(kind).map(ReleaseRequestAutomation::label).orElse(kind);
      List<MtBump> rows = byKind.get(kind);
      if (rows != null && !rows.isEmpty()) {
        entries.add(aggregate(kind, label, rows));
      } else if (unknown.containsKey(kind)) {
        entries.add(answered(kind, label, AutomationState.UNKNOWN, unknown.get(kind), null));
      } else if (waiting.containsKey(kind)) {
        // THE OLD READER IS TOLD REQUESTED: "not run yet", which it holds the request on and asks
        // again about — exactly what WAITING asks of it. UNKNOWN would say nobody could decide,
        // which is false, and leaving the kind out would hide a gate that is holding.
        String why = waiting.get(kind);
        entries.add(
            accepts.contains(AutomationState.WAITING)
                ? answered(kind, label, AutomationState.WAITING, why, why)
                : answered(kind, label, AutomationState.REQUESTED, why, null));
      } else {
        String why = notApplicable.get(kind);
        entries.add(answered(kind, label, AutomationState.NOT_APPLICABLE, why, why));
      }
    }
    return new ReleaseRequestAutomationsDto(requestId, foldSha, List.copyOf(entries));
  }

  /** An entry with no row behind it: answered, never stored. */
  private static AutomationDto answered(
      String kind, String label, AutomationState state, String detail, String reason) {
    return new AutomationDto(
        kind, label, state.name(), detail, null, List.of(), null, null, Instant.now(), null,
        reason);
  }

  /**
   * One kind's rows at one fold, read as one outcome: the newest row per branch, and of those the
   * most telling state — FRESH only when every branch is fresh, FAILED when any is red.
   */
  private static AutomationDto aggregate(String kind, String label, List<MtBump> rows) {
    List<MtBump> current = newestPerBranch(rows);
    AutomationState state = stateOf(current);
    MtBump deciding =
        current.stream()
            .filter(row -> AutomationState.of(row.status) == state)
            .max(Comparator.comparing(row -> row.startedAt))
            .orElse(current.get(current.size() - 1));
    List<String> runIds = new ArrayList<>();
    Instant updated = null;
    for (MtBump row : current) {
      for (String id : runIds(row)) {
        if (!runIds.contains(id)) {
          runIds.add(id);
        }
      }
      Instant at = row.finishedAt != null ? row.finishedAt : row.startedAt;
      updated = updated == null || at.isAfter(updated) ? at : updated;
    }
    String detail =
        current.size() == 1
            ? deciding.message
            : String.join(
                "; ",
                current.stream()
                    .map(row -> row.branch + ": " + (row.message == null ? row.status : row.message))
                    .toList());
    return new AutomationDto(
        kind,
        label,
        state.name(),
        detail,
        deciding.id.toString(),
        List.copyOf(runIds),
        deciding.branch,
        state == AutomationState.COMMITTED ? deciding.resultSha : null,
        updated,
        // Why it is red, from the row that makes it red — and only then (qits-1116).
        state == AutomationState.FAILED && BumpStatus.FAILED.name().equals(deciding.status)
            ? FailureDto.of(
                deciding.failedStepIndex,
                deciding.failedStepImage,
                deciding.failedStepExit,
                deciding.failureExcerpt)
            : null,
        null);
  }

  /** The state several rows of one kind and fold read as together. */
  private static AutomationState stateOf(List<MtBump> rows) {
    Set<AutomationState> states = new LinkedHashSet<>();
    for (MtBump row : rows) {
      states.add(AutomationState.of(row.status));
    }
    for (AutomationState candidate : PRECEDENCE) {
      if (states.contains(candidate)) {
        return candidate;
      }
    }
    return AutomationState.UNKNOWN;
  }

  private static List<MtBump> newestPerBranch(List<MtBump> rows) {
    Map<String, MtBump> newest = new LinkedHashMap<>();
    for (MtBump row : rows) {
      MtBump held = newest.get(row.branch);
      if (held == null || !row.startedAt.isBefore(held.startedAt)) {
        newest.put(row.branch, row);
      }
    }
    return new ArrayList<>(newest.values());
  }

  /** The state one kind's rows read as at one fold, or null when that fold has none. */
  private AutomationState foldState(String requestId, String kind, String foldSha) {
    if (requestId == null || foldSha == null) {
      return null;
    }
    List<MtBump> rows =
        store.automations(requestId, foldSha).stream()
            .filter(row -> kind.equals(row.automationKind))
            .toList();
    return rows.isEmpty() ? null : stateOf(newestPerBranch(rows));
  }

  // --- rows -----------------------------------------------------------------------------------

  /** Opens a REQUESTED row for one run of a plan. */
  private UUID open(
      ReleaseRequestAutomation kind,
      AutomationSubject subject,
      String previous,
      Boolean automationOnly,
      Plan.Run run,
      BumpTrigger trigger,
      boolean exclusive) {
    String branch = run.branch() != null ? run.branch() : kind.branchPrefix() + subject.requestId();
    UUID id =
        store.openAutomation(
            new AutomationOpening(
                subject.repository().name,
                kind.kind(),
                subject.requestId(),
                subject.foldSha(),
                previous,
                automationOnly,
                branch,
                subject.workItem(),
                config.environment(),
                trigger,
                run.changes(),
                run.extras(),
                BumpStatus.REQUESTED,
                null),
            exclusive,
            Instant.now());
    LOG.infof(
        "Opened the %s automation %s of %s for %s at %s onto %s",
        kind.kind(), id, subject.repository().name, subject.requestId(), subject.foldSha(), branch);
    return id;
  }

  /** Records an outcome no run is needed for, on the fold's trigger. */
  private void record(
      ReleaseRequestAutomation kind,
      AutomationSubject subject,
      String previous,
      Boolean automationOnly,
      BumpStatus status,
      String message) {
    store.openAutomation(
        opening(kind, subject, previous, automationOnly, BumpTrigger.FOLD, status, message),
        false,
        Instant.now());
  }

  /** Records an outcome no run is needed for, on the re-run door, refusing while one is active. */
  private UUID openOutcome(
      ReleaseRequestAutomation kind,
      AutomationSubject subject,
      BumpTrigger trigger,
      BumpStatus status,
      String message) {
    return store.openAutomation(
        opening(kind, subject, null, null, trigger, status, message), true, Instant.now());
  }

  private AutomationOpening opening(
      ReleaseRequestAutomation kind,
      AutomationSubject subject,
      String previous,
      Boolean automationOnly,
      BumpTrigger trigger,
      BumpStatus status,
      String message) {
    // An outcome with no run is about the fold rather than about a branch: an own-branch kind's is
    // still its own branch, and a kind that writes the request's branches records it against the
    // fold branch, which is the one ref every such outcome shares.
    String branch =
        kind.target() == Target.OWN_BRANCH
            ? kind.branchPrefix() + subject.requestId()
            : subject.foldRef();
    return new AutomationOpening(
        subject.repository().name,
        kind.kind(),
        subject.requestId(),
        subject.foldSha(),
        previous,
        automationOnly,
        branch,
        subject.workItem(),
        config.environment(),
        trigger,
        List.of(),
        Map.of(),
        status,
        message);
  }

  // --- helpers --------------------------------------------------------------------------------

  /** The subject of one request at one fold. */
  public AutomationSubject subject(
      MtRepository repository,
      String requestId,
      String foldSha,
      List<String> sourceBranches,
      String workItem) {
    String main = baseRef(repository);
    List<String> branches = new ArrayList<>();
    if (sourceBranches != null) {
      for (String branch : sourceBranches) {
        String name = branch == null ? "" : branch.trim();
        // MAIN IS A SOURCE AND NEVER A TARGET, and an automation's own branch is output rather than
        // work: neither is somewhere a kind writes the request's changes.
        if (!name.isEmpty()
            && !name.equals(main)
            && !name.startsWith(BRANCH_PREFIX)
            && !branches.contains(name)) {
          branches.add(name);
        }
      }
    }
    return new AutomationSubject(
        repository,
        requestId,
        foldSha,
        FOLD_BRANCH_PREFIX + requestId,
        branches,
        workItem,
        new FoldReader(gitHost, repository.project, repository.name, foldSha));
  }

  /** Where a row's commit lands — the kind's answer, or, for a kind this build no longer has, the branch's. */
  private static Target target(MtBump row, ReleaseRequestAutomation kind) {
    if (kind != null) {
      return kind.target();
    }
    return row.branch != null
            && (row.branch.startsWith(BRANCH_PREFIX) || row.branch.startsWith("maintenance/baselines/"))
        ? Target.OWN_BRANCH
        : Target.SOURCE_BRANCHES;
  }

  /**
   * The pipeline file qits-ci records a row's run under: the kind file the shared core composes, or
   * the bump pipeline.
   */
  public String configPath(MtBump row) {
    ReleaseRequestAutomation kind = registeredKind(row.automationKind).orElse(null);
    boolean core =
        kind != null
            ? CiClient.AUTOMATION_EVENT_NAME.equals(kind.pipeline())
            : target(row, null) == Target.OWN_BRANCH;
    return core
        ? CiClient.AUTOMATION_CONFIG_DIR + row.automationKind + ".yml"
        : CiClient.CONFIG_PATH;
  }

  private static Applicability applicability(
      ReleaseRequestAutomation kind, AutomationSubject subject) {
    try {
      Applicability answer = kind.applicability(subject);
      return answer == null ? Applicability.unknown(kind.kind() + " gave no answer") : answer;
    } catch (RuntimeException e) {
      LOG.warnf(e, "The %s automation could not decide whether it applies", kind.kind());
      return Applicability.unknown(kind.kind() + " could not decide whether it applies: " + e);
    }
  }

  private static Plan plan(ReleaseRequestAutomation kind, AutomationSubject subject) {
    try {
      Plan plan = kind.plan(subject);
      return plan == null ? Plan.unknown(kind.kind() + " gave no plan") : plan;
    } catch (RuntimeException e) {
      LOG.warnf(e, "The %s automation could not plan", kind.kind());
      return Plan.unknown(kind.kind() + " could not plan: " + e);
    }
  }

  /** Every kind that applies to the subject, with its committable paths — for a plan's clash check. */
  private Map<String, List<String>> applicablePaths(AutomationSubject subject) {
    Map<String, List<String>> paths = new LinkedHashMap<>();
    for (ReleaseRequestAutomation kind : kinds()) {
      if (applicability(kind, subject).applied()) {
        paths.put(kind.kind(), committablePaths(kind, subject));
      }
    }
    return paths;
  }

  private static List<String> inputPaths(ReleaseRequestAutomation kind, AutomationSubject subject) {
    try {
      return kind.inputPaths(subject);
    } catch (RuntimeException e) {
      // Everything is the conservative answer: no fold carries on an input nobody could name.
      LOG.warnf(e, "The %s automation could not name its inputs", kind.kind());
      return List.of(":(glob)**");
    }
  }

  private static List<String> committablePaths(
      ReleaseRequestAutomation kind, AutomationSubject subject) {
    try {
      List<String> paths = kind.committablePaths(subject);
      return paths == null ? List.of() : paths;
    } catch (RuntimeException e) {
      // No paths is the conservative answer: nothing carries on them.
      LOG.warnf(e, "The %s automation could not name its paths", kind.kind());
      return List.of();
    }
  }

  private static String carried(String previous, AutomationState before) {
    return "fresh: carried from fold " + abbreviate(previous) + ", where it was " + before
        + " — only automation output changed since";
  }

  private void fail(MtBump row, String message) {
    store.bumpFinished(row.id, BumpStatus.FAILED, null, message, Instant.now());
  }

  private Map<String, Object> extras(MtBump row) {
    if (row.automationExtras == null || row.automationExtras.isBlank()) {
      return Map.of();
    }
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> read = JSON.readValue(row.automationExtras, Map.class);
      return read == null ? Map.of() : read;
    } catch (Exception e) {
      return Map.of();
    }
  }

  private String branchHead(MtRepository repository, String branch) {
    TreeLookup lookup = gitHost.head(repository.project, repository.name, branch);
    return lookup.found() ? lookup.headSha() : null;
  }

  private static List<String> runIds(MtBump row) {
    if (row.ciRunId == null || row.ciRunId.isBlank()) {
      return List.of();
    }
    return java.util.Arrays.stream(row.ciRunId.split(","))
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .toList();
  }

  private static String baseRef(MtRepository repository) {
    return repository.mainBranch == null || repository.mainBranch.isBlank()
        ? "main"
        : repository.mainBranch;
  }

  private static void requireRequestId(String requestId) {
    if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
      throw new BadRequestException("a release request id is a UUID: " + requestId);
    }
  }

  private static String requireSha(String field, String sha) {
    String value = sha == null ? null : sha.trim();
    if (value == null || !SHA.matcher(value).matches()) {
      throw new BadRequestException(field + " is a full lower-case commit sha: " + sha);
    }
    return value;
  }

  /** The work item, trimmed, or null; refused when it is not one. */
  static String workItem(String workItem) {
    String item = workItem == null || workItem.isBlank() ? null : workItem.trim();
    if (item != null && !WORK_ITEM.matcher(item).matches()) {
      throw new BadRequestException("a work item is <project>-<n>, for example qits-112: " + item);
    }
    return item;
  }

  private static String abbreviate(String sha) {
    return sha == null || sha.length() <= 12 ? sha : sha.substring(0, 12);
  }
}
