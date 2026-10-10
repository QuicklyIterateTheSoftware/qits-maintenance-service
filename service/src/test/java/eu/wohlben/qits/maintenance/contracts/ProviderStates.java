package eu.wohlben.qits.maintenance.contracts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.automation.AutomationService;
import eu.wohlben.qits.maintenance.automation.ReleaseRequestAutomation;
import eu.wohlben.qits.maintenance.automation.ScreenshotBaselinesAutomation;
import eu.wohlben.qits.maintenance.bump.ReleaseRequestClient;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.manifest.ParsedPin;
import eu.wohlben.qits.maintenance.model.BumpMode;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.GroupSource;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.model.RepositoryArchetype;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * <b>qits-maintenance's provider states</b> (epic qits-112): each seeds the bump rows one consumer
 * situation needs and hands back their ids as parameters.
 *
 * <p>Two callers. {@link GoldenMasterRecordingTest} runs a state before recording each operation
 * that names it, and {@link ConsumerPactVerificationTest}'s {@code @State} methods delegate here.
 * Both call {@link #cleanUp()} afterwards: the bump table is shared by every test in the run, and
 * the dispatcher must never see these rows.
 *
 * <p><b>The bump rows start in the year 2100</b>, so they are the newest bumps whatever else the
 * test database holds, and a list of the newest 20 always contains them. The recorder freezes every
 * id and instant, so neither reaches a golden master as it was seeded.
 *
 * <p><b>The estate states</b> (pins, downstream, automations) empty the inventory and seed their own
 * through the store, as {@code AdoptionApiTest} does: those routes answer the whole inventory, so a
 * row another test left would reach the recording. {@link #cleanUp()} drains the work queue and
 * empties the inventory again afterwards.
 */
@ApplicationScoped
public class ProviderStates {

  public static final String PENDING_BUMPS = "pending bumps";
  public static final String NO_PENDING_BUMPS = "no pending bumps";
  public static final String MANIFESTS_THAT_PIN_ARTIFACTS = "manifests that pin artifacts";
  public static final String A_REPOSITORY_WITH_DOWNSTREAM_COMPONENTS =
      "a repository with downstream components";
  public static final String A_RELEASE_REQUEST_WITH_AUTOMATIONS =
      "a release request with automations";
  public static final String A_RELEASE_REQUEST_WITH_A_FAILED_AUTOMATION =
      "a release request with a failed automation";
  public static final String A_RELEASE_REQUEST_WITH_AN_AUTOMATION_TO_RERUN =
      "a release request with an automation to re-run";

  /** The repository the estate states seed, and its catalog id (qits-projects' row id). */
  static final String REPOSITORY = "contract-service";

  static final String REPOSITORY_ID = "0c0a1d1e-0000-4000-8000-00000000c0de";

  /** The release request the automation states seed, and its fold. */
  static final String REQUEST_ID = "5e1f0c3a-2b4d-4e6f-8a9b-0c1d2e3f4a5b";

  static final String FOLD_SHA = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1";

  static final String HEAD_SHA = "3f1a9c0b7d2e4f5a6b8c9d0e1f2a3b4c5d6e7f80";

  /** The request's named branch, beside main. */
  static final String BRANCH = "feature/export";

  private static final Instant SCANNED = Instant.parse("2026-01-01T00:00:00Z");

  /** What a state hands back: its parameters, keys sorted, and its unique tokens (none here). */
  public record Setup(Map<String, String> params, List<String> uniqueTokens) {}

  private static final Instant FUTURE = Instant.parse("2100-01-01T00:00:00Z");
  private static final ObjectMapper JSON = new ObjectMapper();

  private final Map<String, Supplier<Setup>> states = new LinkedHashMap<>();
  private final List<UUID> created = Collections.synchronizedList(new ArrayList<>());
  private volatile boolean estateSeeded;

  @Inject MaintenanceStore store;

  @Inject InventoryReset inventory;

  @Inject WorkQueue queue;

  @Inject FakePeers peers;

  @Inject AutomationService automations;

  public ProviderStates() {
    states.put(PENDING_BUMPS, this::pendingBumps);
    states.put(NO_PENDING_BUMPS, this::noPendingBumps);
    states.put(MANIFESTS_THAT_PIN_ARTIFACTS, this::manifestsThatPinArtifacts);
    states.put(A_REPOSITORY_WITH_DOWNSTREAM_COMPONENTS, this::downstreamComponents);
    states.put(A_RELEASE_REQUEST_WITH_AUTOMATIONS, this::releaseRequestWithAutomations);
    states.put(A_RELEASE_REQUEST_WITH_A_FAILED_AUTOMATION, this::releaseRequestToRerun);
    states.put(A_RELEASE_REQUEST_WITH_AN_AUTOMATION_TO_RERUN, this::releaseRequestToRerun);
  }

  /** Every state name this provider answers for. */
  public Set<String> names() {
    return Collections.unmodifiableSet(states.keySet());
  }

  /** Runs the named state; an unknown name is a programming error, not an empty state. */
  public Setup setUp(String state) {
    Supplier<Setup> setup = states.get(state);
    if (setup == null) {
      throw new IllegalArgumentException(
          "No provider state '" + state + "' — this provider answers for " + states.keySet());
    }
    return setup.get();
  }

  /** {@link #setUp} for a pact {@code @State} method, which returns only the params. */
  public Map<String, String> params(String state) {
    return setUp(state).params();
  }

  /**
   * Deletes every bump row a state created since the last clean-up — and, after an estate state,
   * drains the work queue and empties the inventory and the scripted peers.
   */
  public void cleanUp() {
    if (estateSeeded) {
      estateSeeded = false;
      queue.awaitIdle(Duration.ofSeconds(30));
      inventory.clear();
      peers.reset();
    }
    List<UUID> ids;
    synchronized (created) {
      ids = List.copyOf(created);
      created.clear();
    }
    if (!ids.isEmpty()) {
      QuarkusTransaction.requiringNew().run(() -> MtBump.delete("id in ?1", ids));
    }
  }

  /** The state's slug: lower-cased, every run of non-alphanumerics replaced by {@code -}. */
  public static String slug(String state) {
    return state.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
  }

  // --- the states ------------------------------------------------------------------------------

  /**
   * Seven bumps, newest first. Pending: a targeted bump asked for and not yet running, a group bump
   * running, one green whose release is still owed, and one green with its release request open.
   * Over, which {@code listPendingBumps} leaves out: one that converged, one released, one failed.
   */
  private Setup pendingBumps() {
    UUID requested =
        bump(
            "contract-service",
            "TARGETED",
            "maintenance/targeted",
            "REQUESTED",
            FUTURE.plusSeconds(6),
            change("DOCKER", "docker/Dockerfile", "qits/build-images/ci-base", "2026.1.1", "2026.2.1"),
            null,
            null);
    UUID running =
        bump(
            "contract-service",
            "GROUP",
            "maintenance/dependencies",
            "RUNNING",
            FUTURE.plusSeconds(5),
            change("MAVEN", "pom.xml", "eu.wohlben.qits:qits-arch-rules", "2026.1.1", "2026.2.1"),
            null,
            null);
    UUID releaseOwed =
        bump(
            "contract-javalib",
            "GROUP",
            "maintenance/dependencies",
            "SUCCEEDED",
            FUTURE.plusSeconds(4),
            change("MAVEN", "pom.xml", "io.quarkus.platform:quarkus-bom", "3.30.1", "3.31.0"),
            null,
            null);
    UUID awaitingRelease =
        bump(
            "contract-frontend",
            "GROUP",
            "maintenance/dependencies",
            "SUCCEEDED",
            FUTURE.plusSeconds(3),
            change("NPM", "package.json", "@qits/angular", "2026.1.1", "2026.2.1"),
            UUID.randomUUID().toString(),
            "PENDING");
    UUID converged =
        bump(
            "contract-javalib",
            "GROUP",
            "maintenance/dependencies",
            "SUCCEEDED",
            FUTURE.plusSeconds(2),
            change("MAVEN", "pom.xml", "org.junit:junit-bom", "6.0.0", "6.0.1"),
            "converged",
            null);
    UUID released =
        bump(
            "contract-frontend",
            "GROUP",
            "maintenance/dependencies",
            "SUCCEEDED",
            FUTURE.plusSeconds(1),
            change("NPM", "package.json", "@qits/ui-components", "2026.1.1", "2026.2.1"),
            UUID.randomUUID().toString(),
            "RELEASED");
    UUID failed =
        bump(
            "contract-service",
            "GROUP",
            "maintenance/dependencies",
            "FAILED",
            FUTURE,
            change("MAVEN", "pom.xml", "eu.wohlben.qits:qits-db-core", "2026.1.1", "2026.2.1"),
            null,
            null);
    Map<String, String> params = new TreeMap<>();
    params.put("awaitingReleaseBumpId", awaitingRelease.toString());
    params.put("convergedBumpId", converged.toString());
    params.put("failedBumpId", failed.toString());
    params.put("releaseOwedBumpId", releaseOwed.toString());
    params.put("releasedBumpId", released.toString());
    params.put("requestedBumpId", requested.toString());
    params.put("runningBumpId", running.toString());
    return new Setup(params, List.of());
  }

  /** Nothing of this state's own: the list it answers holds no bump a state created. */
  private Setup noPendingBumps() {
    return new Setup(Map.of(), List.of());
  }

  // --- the estate states -----------------------------------------------------------------------

  /** An empty inventory, the queue drained first so no task writes into it afterwards. */
  private void emptyEstate() {
    estateSeeded = true;
    queue.awaitIdle(Duration.ofSeconds(30));
    inventory.clear();
    peers.reset();
  }

  private void scanned(
      String repository, String catalogId, RepositoryArchetype archetype, ParsedPin... pins) {
    store.replaceInventory(
        repository,
        "qits",
        catalogId,
        archetype.name(),
        "main",
        RepositoryStatus.OK,
        HEAD_SHA,
        null,
        List.of(pins),
        List.of(),
        GroupSource.DEFAULT,
        pin -> PinKind.INTERNAL,
        SCANNED);
  }

  /**
   * One repository whose manifests pin three internal artifacts — a maven jar, an npm package and
   * an image — and the jar's release, which also published an image and a daemon binary of the
   * same version. {@code listDependencyPins} answers the three stored pins and the two carried ones.
   */
  private Setup manifestsThatPinArtifacts() {
    emptyEstate();
    scanned(
        REPOSITORY,
        REPOSITORY_ID,
        RepositoryArchetype.SERVICE,
        ParsedPin.of(
            Ecosystem.MAVEN,
            "pom.xml",
            "eu.wohlben.qits:qits-eventstream",
            "2026.811.1",
            null,
            "property:qits.eventstream.version"),
        ParsedPin.of(
            Ecosystem.NPM,
            "package.json",
            "@qits/ui-components",
            "2026.8.1",
            null,
            "dependency:@qits/ui-components"),
        ParsedPin.of(
            Ecosystem.DOCKER,
            "docker/Dockerfile",
            "qits/build-images/maven-base",
            "2026.813.1",
            null,
            "from:1"));
    Instant released = Instant.parse("2026-08-11T10:00:00Z");
    store.upsertArtifact(
        Ecosystem.MAVEN,
        "eu.wohlben.qits:qits-eventstream",
        "2026.811.1",
        "qits-eventstream-javalib",
        released);
    store.upsertArtifact(
        Ecosystem.DOCKER,
        "qits/eventstream-daemon",
        "2026.811.1",
        "qits-eventstream-javalib",
        released);
    store.upsertDaemonArtifact(
        "qits-eventstream-daemon", "2026.811.1", "qits-eventstream-javalib", released);
    Map<String, String> params = new TreeMap<>();
    params.put("repoName", REPOSITORY);
    return new Setup(params, List.of());
  }

  /**
   * A library, the frontend that pins its npm package, the service that has the frontend as a
   * submodule, and the wrapper that has all three: the library's downstream is the frontend at
   * depth 1 and the service at depth 2. The wrapper is never downstream.
   */
  private Setup downstreamComponents() {
    emptyEstate();
    String library = "contract-ui-jslib";
    String libraryId = "0c0a1d1e-0000-4000-8000-00000000a001";
    String frontend = "contract-frontend";
    String service = REPOSITORY;
    scanned(library, libraryId, RepositoryArchetype.LIBRARY);
    store.upsertArtifact(Ecosystem.NPM, "@qits/contract-ui", "2026.905.1", library, SCANNED);
    scanned(
        frontend,
        "0c0a1d1e-0000-4000-8000-00000000a002",
        RepositoryArchetype.FRONTEND,
        ParsedPin.of(
            Ecosystem.NPM,
            "package.json",
            "@qits/contract-ui",
            "2026.905.1",
            null,
            "dependency:@qits/contract-ui"));
    scanned(service, REPOSITORY_ID, RepositoryArchetype.SERVICE, gitlink(frontend));
    scanned(
        "qits-qits",
        "0c0a1d1e-0000-4000-8000-00000000a003",
        RepositoryArchetype.PROJECT,
        gitlink(library),
        gitlink(frontend),
        gitlink(service));
    Map<String, String> params = new TreeMap<>();
    params.put("repoName", library);
    params.put("repositoryId", libraryId);
    return new Setup(params, List.of());
  }

  private static ParsedPin gitlink(String repository) {
    return ParsedPin.of(
        Ecosystem.GITLINK,
        ".gitmodules",
        repository,
        "c0ffee11d00d2233445566778899aabbccddeeff",
        null,
        "gitlink:service/src/main/webui");
  }

  /**
   * One release request at one fold, with a row for every kind this build offers: screenshot
   * baselines FAILED (with its failing step and two CI runs), every other kind alternately FRESH and
   * COMMITTED. The trigger at that fold answers from those rows and asks no peer.
   */
  private Setup releaseRequestWithAutomations() {
    emptyEstate();
    scanned(REPOSITORY, REPOSITORY_ID, RepositoryArchetype.SERVICE);
    int other = 0;
    int second = 0;
    for (ReleaseRequestAutomation kind : automations.kinds()) {
      String branch = AutomationService.BRANCH_PREFIX + kind.kind() + "/" + REQUEST_ID;
      Instant at = FUTURE.plusSeconds(++second);
      if (ScreenshotBaselinesAutomation.KIND.equals(kind.kind())) {
        automation(kind.kind(), branch, "FAILED", at, "run-contract-1,run-contract-2",
            "the screenshot baselines run failed at step 2 (exit 1): 3 screenshots differ", null,
            true);
      } else if (other++ % 2 == 0) {
        automation(kind.kind(), branch, "NOTHING_TO_DO", at, null,
            "fresh: nothing to regenerate at this fold", null, false);
      } else {
        automation(kind.kind(), branch, "SUCCEEDED", at, "run-contract-3",
            "committed the regenerated output", "0123456789abcdef0123456789abcdef01234567", false);
      }
    }
    return new Setup(automationParams(), List.of());
  }

  /**
   * {@link #releaseRequestWithAutomations}, and qits-projects answering the request open at that
   * fold — what the re-run door asks before it opens a row. The run it opens is dispatched in the
   * background against the scripted peers; {@link #cleanUp()} waits for it.
   */
  private Setup releaseRequestToRerun() {
    Setup setup = releaseRequestWithAutomations();
    peers.answer(
        PeerTarget.PROJECTS,
        ReleaseRequestClient.REQUESTS_PATH_PREFIX
            + REPOSITORY_ID
            + ReleaseRequestClient.REQUESTS_PATH_SUFFIX
            + "/"
            + REQUEST_ID,
        FakePeers.Scripted.ok(
            "{\"request\":{\"id\":\"" + REQUEST_ID + "\",\"repoId\":\"" + REPOSITORY_ID
                + "\",\"state\":\"PENDING\",\"mergedSha\":\"" + FOLD_SHA + "\","
                + "\"sources\":[{\"kind\":\"BRANCH\",\"name\":\"main\"},"
                + "{\"kind\":\"BRANCH\",\"name\":\"" + BRANCH + "\"}]}}"));
    return setup;
  }

  private static Map<String, String> automationParams() {
    Map<String, String> params = new TreeMap<>();
    params.put("branch", BRANCH);
    params.put("foldSha", FOLD_SHA);
    params.put("kind", ScreenshotBaselinesAutomation.KIND);
    params.put("repoName", REPOSITORY);
    params.put("repositoryId", REPOSITORY_ID);
    params.put("requestId", REQUEST_ID);
    return params;
  }

  private void automation(
      String kind,
      String branch,
      String status,
      Instant startedAt,
      String runIds,
      String message,
      String resultSha,
      boolean failedStep) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              MtBump row = new MtBump();
              row.id = UUID.randomUUID();
              row.repository = REPOSITORY;
              row.groupName = kind;
              row.mode = BumpMode.AUTOMATION.name();
              row.automationKind = kind;
              row.branch = branch;
              row.environment = "dev";
              row.trigger = "FOLD";
              row.status = status;
              row.changes = "[]";
              row.releaseRequestId = REQUEST_ID;
              row.foldSha = FOLD_SHA;
              row.ciRunId = runIds;
              row.message = message;
              row.resultSha = resultSha;
              row.startedAt = startedAt;
              row.finishedAt = startedAt.plusSeconds(60);
              if (failedStep) {
                row.ciRunStatus = "FAILED";
                row.failedStepIndex = 2;
                row.failedStepImage = "qits/build-images/node-docker-base:2026.1.1";
                row.failedStepExit = 1;
                row.failureExcerpt = "3 screenshots differ from their baselines";
              }
              row.persist();
            });
  }

  private static Map<String, String> change(
      String ecosystem, String manifestPath, String name, String from, String to) {
    Map<String, String> change = new LinkedHashMap<>();
    change.put("ecosystem", ecosystem);
    change.put("manifestPath", manifestPath);
    change.put("name", name);
    change.put("from", from);
    change.put("to", to);
    change.put("location", null);
    return change;
  }

  private UUID bump(
      String repository,
      String mode,
      String branch,
      String status,
      Instant startedAt,
      Map<String, String> change,
      String releaseRequestId,
      String releaseState) {
    UUID id = UUID.randomUUID();
    String changes;
    try {
      changes = JSON.writeValueAsString(List.of(change));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
    boolean ended = !status.equals("REQUESTED") && !status.equals("RUNNING");
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              MtBump row = new MtBump();
              row.id = id;
              row.repository = repository;
              row.groupName = mode.equals("TARGETED") ? "targeted" : "dependencies";
              row.mode = mode;
              row.branch = branch;
              row.environment = "dev";
              row.trigger = "SCHEDULED";
              row.status = status;
              row.changes = changes;
              row.startedAt = startedAt;
              row.finishedAt = ended ? startedAt.plusSeconds(60) : null;
              row.releaseRequestId = releaseRequestId;
              row.releaseState = releaseState;
              row.releaseStateAt = releaseState == null ? null : startedAt.plusSeconds(120);
              // A green bump pushed a commit, except one that converged: there was nothing to hold.
              row.resultSha =
                  status.equals("SUCCEEDED") && !"converged".equals(releaseRequestId)
                      ? "0123456789abcdef0123456789abcdef01234567"
                      : null;
              row.persist();
            });
    created.add(id);
    return id;
  }
}
