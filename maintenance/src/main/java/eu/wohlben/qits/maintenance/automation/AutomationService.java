package eu.wohlben.qits.maintenance.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.maintenance.bump.BumpPayload;
import eu.wohlben.qits.maintenance.bump.BumpService;
import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.bump.ReleaseRequestClient;
import eu.wohlben.qits.maintenance.config.MaintenanceConfig;
import eu.wohlben.qits.maintenance.dto.FailureDto;
import eu.wohlben.qits.maintenance.dto.ReleaseRequestAutomationsDto;
import eu.wohlben.qits.maintenance.dto.ReleaseRequestAutomationsDto.AutomationDto;
import eu.wohlben.qits.maintenance.entity.MtBump;
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
 * <p>qits-projects posts every fold of every request ({@link #trigger}). This is the PRE-RUN
 * (qits-1133): SOURCE kinds (estate pins, dependency bump) write the build's inputs, DERIVED kinds
 * (screenshot baselines, entity diagram) write what is generated from them. In this order:
 *
 * <ol>
 *   <li><b>Applicability</b> of every kind, from the repository at the fold. NOT_APPLICABLE is
 *       stored per (request, fold, kind) and answered with its reason to a caller that accepts the
 *       word; UNKNOWN is answered and not stored, so the sweep over there asks again.
 *   <li><b>The runtime disjointness check</b>: two applicable kinds whose paths at this fold overlap
 *       are both UNKNOWN, with a sentence naming them.
 *   <li><b>SOURCE kinds</b>: always planned, no carry-over. A FRESH plan costs no run.
 *   <li><b>DERIVED kinds</b>, only once every applicable SOURCE kind is FRESH at this fold; until
 *       then WAITING (answered, not stored). Then <b>carry-over — the main terminator</b>: when this
 *       kind's outcome on the previous fold was FRESH or COMMITTED and every changed path lies under
 *       the DERIVED kinds' {@code committablePaths}, or none matches this kind's {@code inputPaths},
 *       the new fold is FRESH with no run. A SOURCE kind's commit is input, so it re-runs them. An
 *       unknown diff never carries. Then the plan, then a row.
 * </ol>
 *
 * <p>Idempotent per (request, fold): a fold that already has rows is answered from them, so the
 * thirty-second re-ask costs one read.
 *
 * <h2>Concurrency, on a platform whose CI slots are few</h2>
 *
 * <p><b>At most one RUNNING run per (request, kind)</b> — per branch, which for a kind's own branch
 * is the same thing. A newer fold arriving while one runs waits REQUESTED, and only the newest
 * waiting fold is kept: the older become SUPERSEDED. Nothing is cancelled (qits-ci's cancel door is a
 * person's); a stale run is simply discarded when it ends, and its push, if any, re-folds. <b>The
 * estate-wide cap follows qits-ci's free slots</b> (qits-1133, {@link #cap}): at least one, at most
 * half of qits-ci's slots, {@value #MAX_RUNNING} when the queue cannot be read; the rest wait and the
 * ordinary bump sweep dispatches them. Both obey {@code
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
   * How many automation runs may be RUNNING at once when qits-ci's queue cannot be read. Otherwise
   * the cap follows its free slots; see {@link #cap}.
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
   */
  public record Fold(
      String repository,
      String foldSha,
      String previousFoldSha,
      List<String> changedSincePrevious,
      List<String> sourceBranches,
      String workItem,
      List<String> accepts) {

    /** A fold from a caller that names no extra states it accepts — the answer before qits-1133. */
    public Fold(
        String repository,
        String foldSha,
        String previousFoldSha,
        List<String> changedSincePrevious,
        List<String> sourceBranches,
        String workItem) {
      this(repository, foldSha, previousFoldSha, changedSincePrevious, sourceBranches, workItem,
          null);
    }
  }

  /**
   * The answered states a caller has to say it understands before it is sent them (qits-1133). An
   * older qits-projects reads a word it does not know as UNKNOWN, which holds the request, so it is
   * sent WAITING as UNKNOWN and no NOT_APPLICABLE entry at all.
   */
  public static final Set<AutomationState> OPT_IN_STATES =
      Set.of(AutomationState.WAITING, AutomationState.NOT_APPLICABLE);

  /** The opt-in states a caller named, read leniently: an unknown word is ignored. */
  public static Set<AutomationState> accepted(List<String> accepts) {
    Set<AutomationState> out = new LinkedHashSet<>();
    if (accepts == null) {
      return out;
    }
    for (String word : accepts) {
      if (word == null) {
        continue;
      }
      for (String part : word.split(",")) {
        String trimmed = part.trim().toUpperCase(java.util.Locale.ROOT);
        for (AutomationState state : OPT_IN_STATES) {
          if (state.name().equals(trimmed)) {
            out.add(state);
          }
        }
      }
    }
    return out;
  }

  /**
   * Every registered kind this build has switched on, ordered by id — the order every answer lists
   * them in. A kind behind a switch that is off is not listed, asked or run.
   */
  public List<ReleaseRequestAutomation> kinds() {
    return allKinds().stream().filter(ReleaseRequestAutomation::enabled).toList();
  }

  /** Every registered kind, switched on or not — what the registry invariants are checked over. */
  public List<ReleaseRequestAutomation> allKinds() {
    List<ReleaseRequestAutomation> all = new ArrayList<>();
    for (ReleaseRequestAutomation kind : registered) {
      all.add(kind);
    }
    all.sort(Comparator.comparing(ReleaseRequestAutomation::kind));
    return List.copyOf(all);
  }

  /** The kind registered under that id. */
  public Optional<ReleaseRequestAutomation> kind(String kind) {
    return kinds().stream().filter(candidate -> candidate.kind().equals(kind)).findFirst();
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
    Set<AutomationState> accepts = accepted(fold.accepts());

    // A kind this fold already has rows for is answered from them — and it applied, or it would
    // have none, so its paths join the union without asking the git host again. A kind already
    // found not to apply at this fold is not asked again either.
    Set<String> stored = new LinkedHashSet<>();
    for (MtBump existing : store.automations(requestId, foldSha)) {
      stored.add(existing.automationKind);
    }
    Map<String, String> skipped = store.notApplicable(requestId, foldSha);
    List<ReleaseRequestAutomation> kinds = kinds();
    Map<String, Applicability> decided = new LinkedHashMap<>();
    for (ReleaseRequestAutomation kind : kinds) {
      Applicability applicability;
      if (stored.contains(kind.kind())) {
        applicability = Applicability.applies();
      } else if (skipped.containsKey(kind.kind())) {
        applicability = Applicability.notApplicable(skipped.get(kind.kind()));
      } else {
        applicability = applicability(kind, subject);
        if (applicability.state() == Applicability.State.NOT_APPLICABLE) {
          store.recordNotApplicable(
              name, requestId, foldSha, kind.kind(), applicability.reason(), Instant.now());
        }
      }
      decided.put(kind.kind(), applicability);
    }

    Map<String, List<String>> paths = new LinkedHashMap<>();
    for (ReleaseRequestAutomation kind : kinds) {
      if (decided.get(kind.kind()).applied()) {
        paths.put(kind.kind(), committablePaths(kind, subject));
      }
    }

    Map<String, String> unknown = new LinkedHashMap<>();
    for (ReleaseRequestAutomation kind : kinds) {
      if (decided.get(kind.kind()).state() == Applicability.State.UNKNOWN) {
        unknown.put(kind.kind(), decided.get(kind.kind()).reason());
      }
    }
    // THE RUNTIME DISJOINTNESS CHECK. The registry test checks static paths only; a kind whose paths
    // are read from the fold (estate pins, dependency bump) can only be checked here. Two kinds that
    // would commit the same path cannot both be settled: neither is.
    for (int one = 0; one < kinds.size(); one++) {
      for (int other = one + 1; other < kinds.size(); other++) {
        ReleaseRequestAutomation a = kinds.get(one);
        ReleaseRequestAutomation b = kinds.get(other);
        if (!paths.containsKey(a.kind()) || !paths.containsKey(b.kind())) {
          continue;
        }
        String shared = Pathspecs.overlap(paths.get(a.kind()), paths.get(b.kind()));
        if (shared == null) {
          continue;
        }
        String sentence =
            a.label() + " and " + b.label() + " would both commit " + shared
                + "; neither is run until one of them stops claiming it";
        for (ReleaseRequestAutomation clash : List.of(a, b)) {
          if (!stored.contains(clash.kind())) {
            unknown.put(clash.kind(), sentence);
          }
        }
      }
    }

    // THE UNION IS OVER THE KINDS THAT APPLY. It is what the circuit breaker counts ("only
    // automation output changed"). Carry-over of a DERIVED kind reads the DERIVED kinds' union only:
    // a SOURCE kind's commit (a pom, a lockfile) is input of the derived kinds, so it re-runs them.
    List<String> union = new ArrayList<>();
    List<String> derivedUnion = new ArrayList<>();
    for (ReleaseRequestAutomation kind : kinds) {
      List<String> own = paths.get(kind.kind());
      if (own == null) {
        continue;
      }
      union.addAll(own);
      if (kind.stage() == Stage.DERIVED) {
        derivedUnion.addAll(own);
      }
    }
    List<String> changed =
        previous == null || fold.changedSincePrevious() == null ? null : fold.changedSincePrevious();
    Boolean automationOnly =
        changed == null ? null : changed.stream().allMatch(path -> Pathspecs.matchesAny(union, path));

    // SOURCE KINDS FIRST. They are always planned; a plan with nothing to change is FRESH, no run.
    for (ReleaseRequestAutomation kind : kinds) {
      if (kind.stage() != Stage.SOURCE
          || stored.contains(kind.kind())
          || unknown.containsKey(kind.kind())
          || !decided.get(kind.kind()).applied()) {
        continue;
      }
      String undecided = settle(kind, subject, previous, automationOnly, false);
      if (undecided != null) {
        unknown.put(kind.kind(), undecided);
      }
    }

    // THEN THE DERIVED KINDS, once every SOURCE kind that applies is FRESH at this fold.
    List<String> waitingFor = new ArrayList<>();
    for (ReleaseRequestAutomation kind : kinds) {
      if (kind.stage() != Stage.SOURCE || !decided.get(kind.kind()).applied()) {
        continue;
      }
      AutomationState state =
          unknown.containsKey(kind.kind())
              ? AutomationState.UNKNOWN
              : foldState(requestId, kind.kind(), foldSha);
      if (state != AutomationState.FRESH) {
        waitingFor.add(kind.label());
      }
    }
    Map<String, String> waiting = new LinkedHashMap<>();
    for (ReleaseRequestAutomation kind : kinds) {
      if (kind.stage() != Stage.DERIVED
          || stored.contains(kind.kind())
          || unknown.containsKey(kind.kind())
          || !decided.get(kind.kind()).applied()) {
        continue;
      }
      if (!waitingFor.isEmpty()) {
        waiting.put(kind.kind(), "waits for " + String.join(", ", waitingFor));
        continue;
      }
      // Carried when only derived output changed, or when nothing this kind reads changed.
      Boolean carryable =
          changed == null
              ? null
              : changed.stream().allMatch(path -> Pathspecs.matchesAny(derivedUnion, path))
                  || changed.stream().noneMatch(path -> Pathspecs.matchesAny(kind.inputPaths(), path));
      String undecided = settle(kind, subject, previous, carryable, true);
      if (undecided != null) {
        unknown.put(kind.kind(), undecided);
      }
    }
    return answer(requestId, foldSha, unknown, waiting, accepts);
  }

  /**
   * One kind at one fold, after it was found to apply: carry-over, the tripped breaker, the plan, a
   * row.
   *
   * @return why it could not be decided — answered UNKNOWN and not stored — or null
   */
  private String settle(
      ReleaseRequestAutomation kind,
      AutomationSubject subject,
      String previous,
      Boolean automationOnly,
      boolean carry) {
    if (Boolean.TRUE.equals(automationOnly)) {
      AutomationState before =
          carry ? foldState(subject.requestId(), kind.kind(), previous) : null;
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

  // --- the read door ----------------------------------------------------------------------------

  /**
   * Where one request's automations stand at one fold — the newest fold anything was asked about
   * when none is named.
   */
  public ReleaseRequestAutomationsDto automations(String requestId, String foldSha) {
    return automations(requestId, foldSha, List.of());
  }

  /**
   * The same, for a caller that names the opt-in states it accepts (qits-1133). It answers from
   * what is stored: rows, and the kinds found not to apply. A DERIVED kind with neither, on a fold
   * where a SOURCE kind is not FRESH yet, reads WAITING.
   */
  public ReleaseRequestAutomationsDto automations(
      String requestId, String foldSha, List<String> accepts) {
    requireRequestId(requestId);
    String fold =
        foldSha == null || foldSha.isBlank()
            ? store.newestAutomation(requestId).map(row -> row.foldSha).orElse(null)
            : requireSha("foldSha", foldSha);
    if (fold == null) {
      return new ReleaseRequestAutomationsDto(requestId, null, List.of());
    }
    Set<String> stored = new LinkedHashSet<>();
    for (MtBump row : store.automations(requestId, fold)) {
      stored.add(row.automationKind);
    }
    Map<String, String> skipped = store.notApplicable(requestId, fold);
    List<String> waitingFor = new ArrayList<>();
    for (ReleaseRequestAutomation kind : kinds()) {
      if (kind.stage() == Stage.SOURCE && stored.contains(kind.kind())) {
        AutomationState state = foldState(requestId, kind.kind(), fold);
        if (state != AutomationState.FRESH) {
          waitingFor.add(kind.label());
        }
      }
    }
    Map<String, String> waiting = new LinkedHashMap<>();
    if (!waitingFor.isEmpty()) {
      for (ReleaseRequestAutomation kind : kinds()) {
        if (kind.stage() == Stage.DERIVED
            && !stored.contains(kind.kind())
            && !skipped.containsKey(kind.kind())) {
          waiting.put(kind.kind(), "waits for " + String.join(", ", waitingFor));
        }
      }
    }
    return answer(requestId, fold, Map.of(), waiting, accepted(accepts));
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
    if (kind.target() == Target.SOURCE_BRANCHES) {
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
    ReleaseRequestAutomation kind = kind(row.automationKind).orElse(null);

    // CARRY-OVER, ASKED AGAIN. The trigger asked it too, but the fold this one was opened behind may
    // have had no outcome yet: an own-branch run joins and only then records COMMITTED, and the
    // re-fold its join causes can be posted in between. Asked here, that race costs no CI run.
    // A SOURCE kind is never carried: its plan is the cheap check.
    if (BumpTrigger.FOLD.name().equals(row.trigger)
        && (kind == null || kind.stage() != Stage.SOURCE)
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
    long running = store.runningAutomationCount();
    int cap = cap(running);
    if (running >= cap) {
      LOG.debugf("The automation %s waits: %d already run (cap %d)", row.id, running, cap);
      return;
    }
    Optional<MtRepository> repository = store.repository(row.repository);
    if (repository.isEmpty()) {
      store.bumpFinished(
          row.id, BumpStatus.FAILED, null, "the repository left the inventory", Instant.now());
      return;
    }
    if (target(row, kind) == Target.OWN_BRANCH
        && kind != null
        && CiClient.EVENT_NAME.equals(kind.pipeline())) {
      dispatchOwnBranchBump(row, kind, repository.get());
    } else if (target(row, kind) == Target.OWN_BRANCH) {
      dispatchOwnBranch(row, kind, repository.get());
    } else {
      dispatchSourceBranch(row, kind, repository.get());
    }
  }

  /**
   * How many automation runs may be RUNNING at once, from qits-ci's free slots (qits-1133): every
   * pre-run waits in front of a QA run now, so the cap follows what qits-ci can take. At least one
   * (an idle estate always moves), at most half of qits-ci's slots (QA keeps the other half). An
   * unreadable queue keeps the old constant, {@value #MAX_RUNNING}.
   *
   * @param running how many automation runs are RUNNING now; they are inside qits-ci's active count
   */
  int cap(long running) {
    CiClient.QueueState queue = ci.queue();
    if (!queue.readable()) {
      return MAX_RUNNING;
    }
    int ceiling = Math.max(1, queue.slots() / 2);
    long free = Math.max(0, queue.slots() - queue.active());
    long cap = Math.min(ceiling, running + free);
    return (int) Math.max(1, cap);
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
    String startHead = branchHead(repository, row.branch);
    store.bumpStartHead(row.id, startHead);
    AutomationSubject subject =
        subject(repository, row.releaseRequestId, row.foldSha, List.of(), row.workItem);
    Map<String, Object> extras = new LinkedHashMap<>(extras(row));
    extras.putAll(kind.dispatchExtras(subject, startHead));
    CiClient.TriggerResult result =
        ci.triggerAutomation(
            row.id.toString(),
            row.automationKind,
            row.repository,
            row.releaseRequestId,
            row.foldSha,
            baseRef,
            row.branch,
            committablePaths(kind, subject),
            row.workItem,
            extras);
    dispatched(row, result);
  }

  /**
   * An own-branch run on the bump pipeline ({@code MaintenanceBump}) — the dependency bump
   * (qits-1133). The step rebuilds {@code maintenance/automations/<kind>/<request>} as ONE commit on
   * main from the row's changes, under {@code --force-with-lease}, and refuses a branch carrying a
   * commit it did not write (exit {@link eu.wohlben.qits.maintenance.bump.BumpBase#NOT_OURS_EXIT}).
   * The head is read first, as for every own-branch kind, so the ending can tell a push from none.
   */
  private void dispatchOwnBranchBump(
      MtBump row, ReleaseRequestAutomation kind, MtRepository repository) {
    if (row.releaseRequestId == null || row.foldSha == null) {
      fail(row, "an own-branch automation runs on a release request's fold, and this row names none");
      return;
    }
    List<Change> changes = BumpService.changes(row);
    if (changes.isEmpty()) {
      store.bumpFinished(
          row.id,
          BumpStatus.NOTHING_TO_DO,
          null,
          "the plan named no changes to write onto " + row.branch,
          Instant.now());
      return;
    }
    String baseRef = baseRef(repository);
    String startHead = branchHead(repository, row.branch);
    AutomationSubject subject =
        subject(repository, row.releaseRequestId, row.foldSha, List.of(), row.workItem);
    Map<String, String> extra = new LinkedHashMap<>();
    extra.put("kind", row.automationKind);
    extra.put("requestId", row.releaseRequestId);
    extra.put("foldSha", row.foldSha);
    if (row.workItem != null) {
      extra.put("workItem", row.workItem);
    }
    extra.putAll(kind.dispatchExtras(subject, startHead));
    List<String> problems =
        BumpPayload.problems(
            kind.bumpGroup(), row.branch, baseRef, extra.get("replaceHead"), changes);
    if (!problems.isEmpty()) {
      fail(row, String.join("; ", problems));
      return;
    }
    store.bumpStartHead(row.id, startHead);
    CiClient.TriggerResult result =
        ci.trigger(
            row.id.toString(), row.repository, kind.bumpGroup(), row.branch, baseRef, changes,
            extra);
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
            extra);
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
    ReleaseRequestAutomation kind = kind(row.automationKind).orElse(null);
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
          notOurs(failure)
              ? row.branch + " carries a commit qits maintenance did not write, or moved while the"
                  + " run read it; nothing was pushed. Delete the branch to let it be rebuilt"
              : failedMessage(target == Target.OWN_BRANCH ? label : null, ciRunStatus, failure),
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

  /** The step's "this branch is not ours to rebuild" exit (one commit on its base, qits-1133). */
  private static boolean notOurs(CiClient.Failure failure) {
    return failure != null
        && failure.exitCode() != null
        && failure.exitCode() == eu.wohlben.qits.maintenance.bump.BumpBase.NOT_OURS_EXIT;
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
            : releases.join(
                repoId,
                row.releaseRequestId,
                row.branch,
                kind(row.automationKind).map(ReleaseRequestAutomation::joinPriority).orElse(null));
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
   * Every kind with a row at this fold, plus the undecided and the waiting ones, plus — for a caller
   * that accepts the word — every kind that does not apply, in kind order.
   *
   * <p>WAITING goes to a caller that does not accept it as UNKNOWN with the same sentence: that
   * caller holds on UNKNOWN and asks again, which is what WAITING asks of it.
   */
  private ReleaseRequestAutomationsDto answer(
      String requestId,
      String foldSha,
      Map<String, String> unknown,
      Map<String, String> waiting,
      Set<AutomationState> accepts) {
    Map<String, List<MtBump>> byKind = new TreeMap<>();
    for (MtBump row : store.automations(requestId, foldSha)) {
      byKind.computeIfAbsent(row.automationKind, ignored -> new ArrayList<>()).add(row);
    }
    Map<String, String> skipped =
        accepts.contains(AutomationState.NOT_APPLICABLE)
            ? store.notApplicable(requestId, foldSha)
            : Map.of();
    Set<String> listed = new java.util.TreeSet<>(byKind.keySet());
    listed.addAll(unknown.keySet());
    listed.addAll(waiting.keySet());
    listed.addAll(skipped.keySet());
    List<AutomationDto> entries = new ArrayList<>();
    for (String kind : listed) {
      String label = kind(kind).map(ReleaseRequestAutomation::label).orElse(kind);
      List<MtBump> rows = byKind.get(kind);
      if (rows != null && !rows.isEmpty()) {
        entries.add(aggregate(kind, label, rows));
      } else if (unknown.containsKey(kind)) {
        entries.add(unanswered(kind, label, AutomationState.UNKNOWN, unknown.get(kind)));
      } else if (waiting.containsKey(kind)) {
        AutomationState state =
            accepts.contains(AutomationState.WAITING)
                ? AutomationState.WAITING
                : AutomationState.UNKNOWN;
        entries.add(unanswered(kind, label, state, waiting.get(kind)));
      } else {
        entries.add(notApplicable(kind, label, skipped.get(kind)));
      }
    }
    return new ReleaseRequestAutomationsDto(requestId, foldSha, List.copyOf(entries));
  }

  /** An entry with no row behind it: UNKNOWN or WAITING, answered now. */
  private static AutomationDto unanswered(
      String kind, String label, AutomationState state, String detail) {
    return new AutomationDto(
        kind, label, state.name(), detail, null, List.of(), null, null, Instant.now(), null);
  }

  /** A kind that does not apply: its reason, and nothing else. */
  private static AutomationDto notApplicable(String kind, String label, String reason) {
    return new AutomationDto(
        kind,
        label,
        AutomationState.NOT_APPLICABLE.name(),
        reason,
        null,
        List.of(),
        null,
        null,
        null,
        null);
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
            : null);
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
  AutomationSubject subject(
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
    ReleaseRequestAutomation kind = kind(row.automationKind).orElse(null);
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
