package eu.wohlben.qits.maintenance.contracts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.bootstrap.graph.Ecosystem;
import eu.wohlben.qits.bootstrap.graph.manifest.ParsedPin;
import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.automation.AutomationService;
import eu.wohlben.qits.maintenance.automation.ReleaseRequestAutomation;
import eu.wohlben.qits.maintenance.automation.ScreenshotBaselinesAutomation;
import eu.wohlben.qits.maintenance.bump.ReleaseRequestClient;
import eu.wohlben.qits.maintenance.latest.LatestLookup;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.entity.MtReleaseRequest;
import eu.wohlben.qits.maintenance.manifest.GroupConfig;
import eu.wohlben.qits.maintenance.model.BumpMode;
import eu.wohlben.qits.maintenance.model.GroupSource;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.model.ReleaseOrigin;
import eu.wohlben.qits.maintenance.model.RepositoryArchetype;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.model.ScanStatus;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.sbom.ParsedSbom;
import eu.wohlben.qits.maintenance.sbomcheck.SbomCheckService;
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
  public static final String A_REPOSITORY_WHOSE_MANIFESTS_PIN_GOLDEN_MASTERS =
      "a repository whose manifests pin golden masters";
  public static final String A_REPOSITORY_WITH_DOWNSTREAM_COMPONENTS =
      "a repository with downstream components";
  public static final String A_RELEASE_REQUEST_WITH_AUTOMATIONS =
      "a release request with automations";
  public static final String A_RELEASE_REQUEST_WITH_A_FAILED_AUTOMATION =
      "a release request with a failed automation";
  public static final String A_RELEASE_REQUEST_WITH_AN_AUTOMATION_TO_RERUN =
      "a release request with an automation to re-run";
  public static final String A_RELEASE_REQUEST_WITH_AUTOMATIONS_AT_ITS_NEWEST_FOLD =
      "a release request with automations at its newest fold";
  public static final String A_RELEASE_REQUEST_WITH_AN_AUTOMATION_TO_RERUN_FOR_A_WORK_ITEM =
      "a release request with an automation to re-run for a work item";
  public static final String THE_AUTOMATION_KINDS_CATALOGUE = "the automation kinds catalogue";
  public static final String A_PROVIDER_WHOSE_DEPLOYED_CONSUMERS_PUBLISH_PACTS =
      "a provider whose deployed consumers publish pacts";
  public static final String BUMPS_OWED_TO_A_REPOSITORY = "bumps owed to a repository";
  public static final String A_RELEASE_REQUEST_THIS_SERVICE_OPENED =
      "a release request this service opened";
  public static final String A_STORED_SBOM_CHECK_REPORT = "a stored SBOM check report";
  public static final String A_FINISHED_SCAN = "a finished scan";
  public static final String DEPENDENCIES_OF_A_PROJECT_SOME_BEHIND =
      "dependencies of a project, some behind";

  /**
   * {@link #DEPENDENCIES_OF_A_PROJECT_SOME_BEHIND} again, under the name its {@code
   * listDependencies?kind=EXTERNAL} recording is kept by: a golden master is one answer per
   * operation per state, so the second query of one operation needs a state of its own — as {@link
   * #A_RELEASE_REQUEST_WITH_AUTOMATIONS_AT_ITS_NEWEST_FOLD} is for its read without a fold.
   */
  public static final String EXTERNAL_DEPENDENCIES_OF_A_PROJECT_SOME_BEHIND =
      "external dependencies of a project, some behind";

  public static final String NO_DEPENDENCIES = "no dependencies";

  /** {@link #NO_DEPENDENCIES} again, for its {@code kind=EXTERNAL} recording. */
  public static final String NO_EXTERNAL_DEPENDENCIES = "no external dependencies";

  /**
   * The landing app's name search (qits-2331): {@link #DEPENDENCIES_OF_A_PROJECT_SOME_BEHIND} and
   * one more repository, read through {@code listDependencies?kind=INTERNAL&name=}{@link
   * #MATCHING_GLOB}.
   */
  public static final String DEPENDENCIES_MATCHING_A_GLOB_SOME_BEHIND =
      "dependencies matching a glob, some behind";

  /**
   * {@link #DEPENDENCIES_MATCHING_A_GLOB_SOME_BEHIND} again, for its {@code kind=EXTERNAL} read.
   */
  public static final String EXTERNAL_DEPENDENCIES_MATCHING_A_GLOB_SOME_BEHIND =
      "external dependencies matching a glob, some behind";

  /**
   * {@link #DEPENDENCIES_MATCHING_A_GLOB_SOME_BEHIND}'s inventory, searched with {@link
   * #UNMATCHED_GLOB}: repositories and pins there, none of them named so — a search that matched
   * nothing, not an empty inventory ({@link #NO_DEPENDENCIES}).
   */
  public static final String NO_DEPENDENCIES_MATCHING_A_GLOB = "no dependencies matching a glob";

  /** {@link #NO_DEPENDENCIES_MATCHING_A_GLOB} again, for its {@code kind=EXTERNAL} read. */
  public static final String NO_EXTERNAL_DEPENDENCIES_MATCHING_A_GLOB =
      "no external dependencies matching a glob";

  /**
   * The glob the search states record: a whole-name match, so {@code *p*} keeps every name with a
   * {@code p} — {@code @qits/ui-components} (behind) but not {@code qits-eventstream}; {@code
   * left-pad} (failed lookup), {@code typescript} (behind) and {@code quarkus-bom} but not {@code
   * rxjs}.
   */
  static final String MATCHING_GLOB = "*p*";

  /** A glob no dependency of {@link #DEPENDENCIES_MATCHING_A_GLOB_SOME_BEHIND} is named by. */
  static final String UNMATCHED_GLOB = "nothing-matches-*";

  /**
   * The landing app's bump page (qits-2332): one FOLD automation of {@code entity-diagram} that ran
   * green and left its branch where it was — NOTHING_TO_DO, one CI run, no changes and no failure.
   * {@code getBump} answers it under the param {@code bumpId}.
   */
  public static final String A_BUMP_THAT_CHANGED_NOTHING = "a bump that changed nothing";

  /**
   * The bump page's red row (qits-2332): one FOLD automation of {@code golden-recordings-maven}
   * FAILED over two CI runs, its failing step recorded — {@code getBump} answers it, {@code
   * failure} set, under the param {@code bumpId}.
   */
  public static final String A_FAILED_BUMP = "a failed bump";

  /**
   * The bump page's unknown id (qits-2332): {@code bumpId} names no row, so {@code getBump} answers
   * 404. Nothing is seeded.
   */
  public static final String NO_SUCH_BUMP = "no such bump";

  /** The id {@link #NO_SUCH_BUMP} asks for: a well-formed uuid no state ever seeds. */
  static final String UNKNOWN_BUMP_ID = "0b0b0b0b-0000-4000-8000-00000000dead";

  /** The fold the bump-page states' automations ran at, abbreviated into their sentences. */
  static final String BUMP_FOLD_SHA = "0123456789abcdef0123456789abcdef01234567";

  /**
   * The landing app's repository page (qits-2334), read by name: {@code contract-javalib} scanned
   * OK with two INTERNAL pins (one pending), two EXTERNAL (one pending), one REACTOR and one
   * UNRESOLVED; its released jar's SBOM carrying two transitives, one behind; three bumps of its own
   * — a RUNNING group bump, a SUCCEEDED {@code dependency-bump} automation and a NOTHING_TO_DO
   * {@code entity-diagram} one; the jar embedded by one current and one behind dependent; and a
   * downstream closure two levels deep. {@code getRepository}, {@code listBumps}, {@code
   * listRepositoryDependents} and {@code getRepositoryDownstream} answer it under {@code repoName}.
   */
  public static final String A_REPOSITORY_WITH_PINS_AND_BUMPS = "a repository with pins and bumps";

  /**
   * The repository page's empty case (qits-2334): {@code repoName} scanned OK with no pin, nothing
   * released, no bump and nobody downstream — the same four reads, each answering empty.
   */
  public static final String A_REPOSITORY_WITH_NOTHING_PINNED = "a repository with nothing pinned";

  /**
   * The repository page's unknown name (qits-2334): {@code repoName} names no inventory row, so
   * {@code getRepository} answers 404; the other three reads answer 200 and empty, as they do live.
   */
  public static final String NO_SUCH_REPOSITORY = "no such repository";

  /** The repository {@link #A_REPOSITORY_WITH_PINS_AND_BUMPS} seeds and reads. */
  static final String PINNED_REPOSITORY = "contract-javalib";

  /** The name {@link #NO_SUCH_REPOSITORY} asks for: one no state ever scans. */
  static final String UNKNOWN_REPOSITORY = "contract-unknown-service";

  /**
   * The landing app's adoption page (qits-2333): {@code contract-ui-jslib} released {@code
   * @qits/contract-ui} at {@link #ADOPTION_VERSION}; {@code contract-frontend} shipped it in its own
   * release (ADOPTED, with {@code adoptedVersion} and {@code adoptedAt}), {@code
   * contract-admin-frontend} pins it and has not released (PENDING), and {@code contract-service}
   * carries both frontends as submodules — depth 2, its {@code via} naming both (PENDING, since the
   * service never released). {@code getAdoptionByRelease} answers it under the params {@code
   * repoName} and {@code version}.
   */
  public static final String A_RELEASE_SOME_CONSUMERS_ADOPTED =
      "a release some consumers adopted";

  /**
   * The adoption page's empty journey (qits-2333): {@code contract-ui-jslib} released {@code
   * @qits/contract-ui} at {@link #ADOPTION_VERSION} and nothing pins or embeds it, so {@code
   * getAdoptionByRelease} answers its one package and no adopters, under the params {@code
   * repoName} and {@code version}.
   */
  public static final String A_RELEASE_NOBODY_CONSUMES = "a release nobody consumes";

  /** The release the adoption-page states ask about. */
  static final String ADOPTION_VERSION = "2026.905.1";

  public static final String RELEASED_ARTIFACTS_SOME_BEHIND = "released artifacts, some behind";
  public static final String NO_RELEASED_ARTIFACTS = "no released artifacts";

  /** The work item the CLI's {@code qits maintenance automation run --work-item} names. */
  static final String WORK_ITEM = "qits-112";

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

  /**
   * The project the dependencies states seed: the id the landing app's harness opens, from
   * {@code @qits/projects-golden-masters}' "a project exists".
   */
  static final String PROJECT_ID = "00000000-0000-4000-8000-000000000001";

  /** Another project, whose repository a consumer filtering by {@link #PROJECT_ID} must not show. */
  static final String OTHER_PROJECT_ID = "00000000-0000-4000-8000-000000000002";

  /**
   * What a state hands back: its parameters, keys sorted, and its unique tokens (none here).
   *
   * @param params the state's parameters, keys sorted — what a pact {@code @State} method returns.
   *     A UUID's frozen number in a param — the whole value or embedded in it — is sticky to the
   *     committed {@code golden-masters/index.json} ({@link Freezer#seed(Map, Map)}): adding or
   *     removing a param never renumbers the others
   * @param uniqueTokens random tokens the state had to put into names to keep them unique
   */
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

  @Inject SbomCheckService sbomCheck;

  public ProviderStates() {
    states.put(PENDING_BUMPS, this::pendingBumps);
    states.put(NO_PENDING_BUMPS, this::noPendingBumps);
    states.put(MANIFESTS_THAT_PIN_ARTIFACTS, this::manifestsThatPinArtifacts);
    states.put(
        A_REPOSITORY_WHOSE_MANIFESTS_PIN_GOLDEN_MASTERS, this::repositoryPinningGoldenMasters);
    states.put(A_REPOSITORY_WITH_DOWNSTREAM_COMPONENTS, this::downstreamComponents);
    states.put(A_RELEASE_REQUEST_WITH_AUTOMATIONS, this::releaseRequestWithAutomations);
    states.put(A_RELEASE_REQUEST_WITH_A_FAILED_AUTOMATION, this::releaseRequestToRerun);
    states.put(A_RELEASE_REQUEST_WITH_AN_AUTOMATION_TO_RERUN, this::releaseRequestToRerun);
    states.put(
        A_RELEASE_REQUEST_WITH_AUTOMATIONS_AT_ITS_NEWEST_FOLD, this::releaseRequestWithAutomations);
    states.put(
        A_RELEASE_REQUEST_WITH_AN_AUTOMATION_TO_RERUN_FOR_A_WORK_ITEM,
        this::releaseRequestToRerunForAWorkItem);
    states.put(THE_AUTOMATION_KINDS_CATALOGUE, ProviderStates::automationKindsCatalogue);
    states.put(
        A_PROVIDER_WHOSE_DEPLOYED_CONSUMERS_PUBLISH_PACTS, this::deployedConsumersPublishingPacts);
    states.put(BUMPS_OWED_TO_A_REPOSITORY, this::bumpsOwedToARepository);
    states.put(A_RELEASE_REQUEST_THIS_SERVICE_OPENED, this::releaseRequestThisServiceOpened);
    states.put(A_STORED_SBOM_CHECK_REPORT, this::storedSbomCheckReport);
    states.put(A_FINISHED_SCAN, this::finishedScan);
    states.put(DEPENDENCIES_OF_A_PROJECT_SOME_BEHIND, this::dependenciesOfAProjectSomeBehind);
    states.put(
        EXTERNAL_DEPENDENCIES_OF_A_PROJECT_SOME_BEHIND, this::dependenciesOfAProjectSomeBehind);
    states.put(NO_DEPENDENCIES, this::noDependencies);
    states.put(NO_EXTERNAL_DEPENDENCIES, this::noDependencies);
    states.put(DEPENDENCIES_MATCHING_A_GLOB_SOME_BEHIND, this::dependenciesMatchingAGlob);
    states.put(EXTERNAL_DEPENDENCIES_MATCHING_A_GLOB_SOME_BEHIND, this::dependenciesMatchingAGlob);
    states.put(NO_DEPENDENCIES_MATCHING_A_GLOB, this::dependenciesMatchingAGlob);
    states.put(NO_EXTERNAL_DEPENDENCIES_MATCHING_A_GLOB, this::dependenciesMatchingAGlob);
    states.put(A_BUMP_THAT_CHANGED_NOTHING, this::bumpThatChangedNothing);
    states.put(A_FAILED_BUMP, this::failedBump);
    states.put(NO_SUCH_BUMP, ProviderStates::noSuchBump);
    states.put(A_REPOSITORY_WITH_PINS_AND_BUMPS, this::repositoryWithPinsAndBumps);
    states.put(A_REPOSITORY_WITH_NOTHING_PINNED, this::repositoryWithNothingPinned);
    states.put(NO_SUCH_REPOSITORY, this::noSuchRepository);
    states.put(A_RELEASE_SOME_CONSUMERS_ADOPTED, this::releaseSomeConsumersAdopted);
    states.put(A_RELEASE_NOBODY_CONSUMES, this::releaseNobodyConsumes);
    states.put(RELEASED_ARTIFACTS_SOME_BEHIND, this::releasedArtifactsSomeBehind);
    states.put(NO_RELEASED_ARTIFACTS, this::noReleasedArtifacts);
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

  /**
   * One {@code entity-diagram} automation of {@link #REQUEST_ID} at {@link #BUMP_FOLD_SHA}, run on
   * its own branch by one CI run that passed, the branch unmoved: NOTHING_TO_DO with the sentence
   * {@code AutomationService} writes for it, no changes, no failure and no release state.
   */
  private Setup bumpThatChangedNothing() {
    String kind = "entity-diagram";
    String branch = AutomationService.BRANCH_PREFIX + kind + "/" + REQUEST_ID;
    UUID id =
        automationBump(
            kind,
            branch,
            "NOTHING_TO_DO",
            "run-contract-1",
            "SUCCESS",
            "unchanged: the run passed and "
                + branch
                + " did not move, so entity diagram are fresh at "
                + BUMP_FOLD_SHA.substring(0, 12),
            false);
    return new Setup(Map.of("bumpId", id.toString()), List.of());
  }

  /**
   * One {@code golden-recordings-maven} automation of {@link #REQUEST_ID} at {@link
   * #BUMP_FOLD_SHA} that FAILED on its second CI run — both run ids on the row — with the failing
   * step, its image, exit code and a three-line surefire excerpt; no changes.
   */
  private Setup failedBump() {
    String kind = "golden-recordings-maven";
    UUID id =
        automationBump(
            kind,
            AutomationService.BRANCH_PREFIX + kind + "/" + REQUEST_ID,
            "FAILED",
            "run-contract-1,run-contract-2",
            "FAILED",
            "the golden recordings (maven) run ended FAILED at step 0 (exit 1): [ERROR] Tests run:"
                + " 42, Failures: 1, Errors: 0, Skipped: 0",
            true);
    return new Setup(Map.of("bumpId", id.toString()), List.of());
  }

  /** No row: {@link #UNKNOWN_BUMP_ID} is a uuid no state seeds, so {@code getBump} answers 404. */
  private static Setup noSuchBump() {
    return new Setup(Map.of("bumpId", UNKNOWN_BUMP_ID), List.of());
  }

  /**
   * Nothing of this state's own either: the catalogue is the registered {@code
   * ReleaseRequestAutomation} beans this build offers, read from the code rather than seeded.
   */
  private static Setup automationKindsCatalogue() {
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
   *
   * <p>And main's two EXTERNAL image pins, a mirrored one and a bare one (qits-2253): {@code
   * listImagePins} answers exactly those, and {@code listDependencyPins} — INTERNAL only — does not
   * see them. They are main's rows only, so no other operation of this state answers differently.
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
    store.replaceMainPins(
        REPOSITORY,
        HEAD_SHA,
        List.of(
            new MaintenanceStore.MainPin(
                Ecosystem.DOCKER.wireName(),
                "hub/library/postgres",
                "18.4",
                "docker/Dockerfile",
                null,
                PinKind.EXTERNAL),
            new MaintenanceStore.MainPin(
                Ecosystem.DOCKER.wireName(),
                "node",
                "24-alpine",
                "frontend/Dockerfile",
                null,
                PinKind.EXTERNAL)));
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
   * One repository whose manifests pin another repository's golden-master packages — a maven jar
   * and an npm package, both named {@code <app>-golden-masters} — which is what {@code
   * getRepository} answers for qits-projects' contract-change approval source ({@code
   * ContractChangeSource}): it reads this repository's {@code pins[]} to tell whether a consumer
   * pins the provider whose fold just touched {@code golden-masters/}.
   */
  private Setup repositoryPinningGoldenMasters() {
    emptyEstate();
    scanned(
        REPOSITORY,
        REPOSITORY_ID,
        RepositoryArchetype.SERVICE,
        ParsedPin.of(
            Ecosystem.MAVEN,
            "pom.xml",
            "eu.wohlben.qits:contract-service-golden-masters",
            "2026.1.1",
            null,
            "dependency:eu.wohlben.qits:contract-service-golden-masters"),
        ParsedPin.of(
            Ecosystem.NPM,
            "package.json",
            "@qits/contract-service-golden-masters",
            "2026.1.1",
            null,
            "dependency:@qits/contract-service-golden-masters"));
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

  /**
   * The adoption page's journey (qits-2333). The library releases {@code @qits/contract-ui} at
   * {@link #ADOPTION_VERSION}; both frontends pin it; {@code contract-frontend}'s own release
   * {@code 2026.905.2}, its bill of materials naming the library's package, is what makes it
   * ADOPTED at {@code 2026-09-06T09:00:00Z}; {@code contract-admin-frontend} never released, so it
   * is PENDING. The service gitlinks both frontends — the merge, at depth 2 via both — and is
   * PENDING. The wrapper has everything and is never on the journey.
   */
  private Setup releaseSomeConsumersAdopted() {
    emptyEstate();
    String library = "contract-ui-jslib";
    String frontend = "contract-frontend";
    String adminFrontend = "contract-admin-frontend";
    String service = REPOSITORY;
    ParsedPin libraryPin =
        ParsedPin.of(
            Ecosystem.NPM,
            "package.json",
            "@qits/contract-ui",
            ADOPTION_VERSION,
            null,
            "dependency:@qits/contract-ui");
    scanned(library, "0c0a1d1e-0000-4000-8000-00000000b001", RepositoryArchetype.LIBRARY);
    store.upsertArtifact(
        Ecosystem.NPM,
        "@qits/contract-ui",
        ADOPTION_VERSION,
        library,
        Instant.parse("2026-09-05T09:00:00Z"));
    scanned(
        frontend, "0c0a1d1e-0000-4000-8000-00000000b002", RepositoryArchetype.FRONTEND, libraryPin);
    store.replaceGraph(
        store.upsertArtifact(
            Ecosystem.NPM,
            "@qits/contract-frontend",
            "2026.905.2",
            frontend,
            Instant.parse("2026-09-06T09:00:00Z")),
        List.of(sbomComponent(Ecosystem.NPM, "@qits/contract-ui", ADOPTION_VERSION, true)),
        List.of(new ParsedSbom.Edge(-1, 0)),
        SCANNED);
    scanned(
        adminFrontend,
        "0c0a1d1e-0000-4000-8000-00000000b003",
        RepositoryArchetype.FRONTEND,
        libraryPin);
    scanned(
        service,
        REPOSITORY_ID,
        RepositoryArchetype.SERVICE,
        gitlink(frontend),
        gitlink(adminFrontend));
    scanned(
        "qits-qits",
        "0c0a1d1e-0000-4000-8000-00000000b004",
        RepositoryArchetype.PROJECT,
        gitlink(library),
        gitlink(frontend),
        gitlink(adminFrontend),
        gitlink(service));
    Map<String, String> params = new TreeMap<>();
    params.put("repoName", library);
    params.put("version", ADOPTION_VERSION);
    return new Setup(params, List.of());
  }

  /**
   * The library alone, its release of {@code @qits/contract-ui} at {@link #ADOPTION_VERSION}
   * published and nothing in the inventory pinning, embedding or submoduling it: one package, no
   * adopters.
   */
  private Setup releaseNobodyConsumes() {
    emptyEstate();
    String library = "contract-ui-jslib";
    scanned(library, "0c0a1d1e-0000-4000-8000-00000000b001", RepositoryArchetype.LIBRARY);
    store.upsertArtifact(
        Ecosystem.NPM,
        "@qits/contract-ui",
        ADOPTION_VERSION,
        library,
        Instant.parse("2026-09-05T09:00:00Z"));
    Map<String, String> params = new TreeMap<>();
    params.put("repoName", library);
    params.put("version", ADOPTION_VERSION);
    return new Setup(params, List.of());
  }

  /**
   * One consumer, {@code contract-service}, publishing its pact against {@code
   * contract-provider-service} at two sparse versions, and qits-deployments serving it at three:
   * two built after the older pact, one after the newer. {@code listDeployedPacts} answers two
   * rows, the older naming two deployed versions (qits-1765). The pact rows go in the store and the
   * deployer's answer in the scripted peers, both emptied by {@link #cleanUp()}.
   */
  private Setup deployedConsumersPublishingPacts() {
    emptyEstate();
    String provider = "contract-provider-service";
    store.upsertArtifact(
        Ecosystem.DOCKER, "qits/contract-service", "2026.1030.204903", REPOSITORY, SCANNED);
    for (String version : List.of("2026.1028.235307", "2026.1030.200000")) {
      store.upsertArtifact(
          Ecosystem.MAVEN,
          "eu.wohlben.qits:" + REPOSITORY + "-pacts-" + provider,
          version,
          REPOSITORY,
          SCANNED);
    }
    peers.answer(
        PeerTarget.DEPLOYMENTS,
        "/deployments/api/pins",
        FakePeers.Scripted.ok(
            "{\"pins\":[{\"applicationName\":\"contract-service\",\"shas\":"
                + "[\"2026.1030.204903\",\"2026.1030.192107\",\"2026.1029.90000\"]}]}"));
    Map<String, String> params = new TreeMap<>();
    params.put("provider", provider);
    return new Setup(params, List.of());
  }

  /**
   * One repository, {@code contract-service}, pinning {@code qits-eventstream} at {@code
   * 2026.811.1} while {@code 2026.821.3} is released, nothing open for it in qits-projects, and the
   * CI room empty: one target of {@link Fixture#POOL_SLOTS} slots all free and nothing queued. The
   * dispatcher's decision is DISPATCH with that repository READY and its one pick — what {@code
   * getBumpStanding} answers, sending nothing (qits-1696) — owed since the scan (qits-2094).
   */
  private Setup bumpsOwedToARepository() {
    emptyEstate();
    store.replaceInventory(
        REPOSITORY,
        "qits",
        REPOSITORY_ID,
        RepositoryArchetype.SERVICE.name(),
        "main",
        RepositoryStatus.OK,
        HEAD_SHA,
        null,
        List.of(
            ParsedPin.of(
                Ecosystem.MAVEN,
                "pom.xml",
                "eu.wohlben.qits:qits-eventstream",
                "2026.811.1",
                null,
                "property:qits.eventstream.version")),
        List.of(GroupConfig.Group.ofKind(GroupConfig.DEFAULT_GROUP, PinKind.INTERNAL)),
        GroupSource.DEFAULT,
        pin -> PinKind.INTERNAL,
        SCANNED);
    store.recordLatestIfNewer(
        Ecosystem.MAVEN, "eu.wohlben.qits:qits-eventstream", "2026.821.3", "test", SCANNED);
    // Owed since the scan found it so (qits-2094): the stamp a tick would have written.
    store.recordOwed(Set.of(REPOSITORY), Set.of(), SCANNED);
    peers.answer(
        PeerTarget.PROJECTS,
        ReleaseRequestClient.REQUESTS_PATH_PREFIX
            + REPOSITORY_ID
            + ReleaseRequestClient.REQUESTS_PATH_SUFFIX,
        FakePeers.Scripted.ok("{\"requests\":[]}"));
    Fixture.scriptCiQueueEmpty(peers);
    Map<String, String> params = new TreeMap<>();
    params.put("repoName", REPOSITORY);
    return new Setup(params, List.of());
  }

  /**
   * One main-only release request this service opened for {@code contract-service}, carrying the
   * pending set it was opened for — the {@code mt_release_request} row {@code
   * getReleaseRequestMemory} answers (qits-1954). An estate state, so {@link #cleanUp()} forgets
   * the row again.
   */
  private Setup releaseRequestThisServiceOpened() {
    emptyEstate();
    Map<String, String> change = new LinkedHashMap<>();
    change.put("ecosystem", "maven");
    change.put("name", "eu.wohlben.qits:qits-eventstream");
    change.put("from", "2026.811.1");
    change.put("to", "2026.821.3");
    store.recordOpenedRequest(
        REQUEST_ID, REPOSITORY, "main", MtReleaseRequest.MAIN_ONLY, List.of(change), SCANNED);
    Map<String, String> params = new TreeMap<>();
    params.put("repoName", REPOSITORY);
    params.put("requestId", REQUEST_ID);
    return new Setup(params, List.of());
  }

  /**
   * One released maven jar of {@code contract-service}'s, still in qits-artifacts but with its
   * SBOM MISSING, and one SBOM check run over it already stored (report-only, as the test module
   * configures it): {@code getSbomCheckReport} answers that report, and {@code runSbomCheck} runs
   * the check again and answers the same finding (qits-621).
   */
  private Setup storedSbomCheckReport() {
    emptyEstate();
    UUID id =
        store.upsertArtifact(
            Ecosystem.MAVEN,
            "eu.wohlben.qits:contract-no-sbom",
            "2026.1001.1",
            REPOSITORY,
            SCANNED,
            new ReleaseOrigin("qits", "artifacts", null));
    store.markArtifactMissing(id);
    peers.answer(
        PeerTarget.MAVEN_REGISTRY,
        "/eu/wohlben/qits/contract-no-sbom/maven-metadata.xml",
        FakePeers.Scripted.ok(
            "<metadata><groupId>eu.wohlben.qits</groupId><artifactId>contract-no-sbom</artifactId>"
                + "<versioning><versions><version>2026.1001.1</version></versions></versioning>"
                + "</metadata>"));
    sbomCheck.run(SCANNED);
    Map<String, String> params = new TreeMap<>();
    params.put("repoName", REPOSITORY);
    return new Setup(params, List.of());
  }

  /**
   * One manual INTERNAL scan of {@code contract-service} that SUCCEEDED, beside the repository's
   * inventory row: {@code getScan} answers it, and {@code requestScan} queues another one of the
   * same repository, which runs in the background against the scripted peers and is waited for by
   * {@link #cleanUp()}.
   */
  private Setup finishedScan() {
    emptyEstate();
    scanned(REPOSITORY, REPOSITORY_ID, RepositoryArchetype.SERVICE);
    UUID scan = store.openScan(ScanScope.INTERNAL, REPOSITORY, "MANUAL", SCANNED);
    store.scanStatus(scan, ScanStatus.SUCCEEDED, null, SCANNED.plusSeconds(30));
    Map<String, String> params = new TreeMap<>();
    params.put("repoName", REPOSITORY);
    params.put("scanId", scan.toString());
    return new Setup(params, List.of());
  }

  /**
   * Two repositories of {@link #PROJECT_ID} and one of {@link #OTHER_PROJECT_ID}, each pinning
   * INTERNAL ({@code eu.wohlben.qits:*}, {@code @qits/*}) and EXTERNAL dependencies, every latest
   * looked up at {@link #SCANNED}:
   *
   * <ul>
   *   <li>{@code contract-frontend} (this project): {@code @qits/ui-components} 2026.8.1, behind
   *       2026.9.1; {@code rxjs} 7.8.1, behind 7.8.2; {@code left-pad} 1.3.0, whose lookup FAILED —
   *       no latest, and the registry's reason as the error.
   *   <li>{@code contract-service} (this project): {@code eu.wohlben.qits:qits-eventstream}
   *       2026.811.1 and {@code io.quarkus.platform:quarkus-bom} 3.40.1, both current.
   *   <li>{@code other-project-service} (the other project): the same eventstream, current, and
   *       quarkus-bom 3.30.1, behind — the pin a consumer filtering by project leaves out.
   * </ul>
   *
   * <p>{@code listRepositories} answers the three; {@code listDependencies?kind=INTERNAL} the two
   * internal dependencies and {@code ?kind=EXTERNAL} the three external ones, every pin of each.
   */
  private Setup dependenciesOfAProjectSomeBehind() {
    emptyEstate();
    scannedIn(
        PROJECT_ID,
        "contract-frontend",
        "0c0a1d1e-0000-4000-8000-00000000d001",
        RepositoryArchetype.FRONTEND,
        ParsedPin.of(
            Ecosystem.NPM,
            "package.json",
            "@qits/ui-components",
            "2026.8.1",
            null,
            "dependency:@qits/ui-components"),
        ParsedPin.of(Ecosystem.NPM, "package.json", "rxjs", "7.8.1", null, "dependency:rxjs"),
        ParsedPin.of(
            Ecosystem.NPM, "package.json", "left-pad", "1.3.0", null, "dependency:left-pad"));
    scannedIn(
        PROJECT_ID,
        REPOSITORY,
        REPOSITORY_ID,
        RepositoryArchetype.SERVICE,
        eventstream(),
        quarkusBom("3.40.1"));
    scannedIn(
        OTHER_PROJECT_ID,
        "other-project-service",
        "0c0a1d1e-0000-4000-8000-00000000d002",
        RepositoryArchetype.SERVICE,
        eventstream(),
        quarkusBom("3.30.1"));
    latest(Ecosystem.NPM, "@qits/ui-components", LatestLookup.found("2026.9.1", null));
    latest(Ecosystem.NPM, "rxjs", LatestLookup.found("7.8.2", null));
    latest(Ecosystem.NPM, "left-pad", LatestLookup.failed(null, "the packument lists no versions"));
    latest(
        Ecosystem.MAVEN,
        "eu.wohlben.qits:qits-eventstream",
        LatestLookup.found("2026.811.1", null));
    latest(Ecosystem.MAVEN, "io.quarkus.platform:quarkus-bom", LatestLookup.found("3.40.1", null));
    Map<String, String> params = new TreeMap<>();
    params.put("projectId", PROJECT_ID);
    return new Setup(params, List.of());
  }

  /**
   * {@link #dependenciesOfAProjectSomeBehind()} and {@code contract-tooling}, a third repository of
   * {@link #PROJECT_ID} pinning {@code typescript} 5.9.2, behind 5.9.3: an EXTERNAL pin of the open
   * project that is behind and named by {@link #MATCHING_GLOB}, which {@code rxjs} is not. The
   * extra repository keeps the four unfiltered recordings as they were.
   */
  private Setup dependenciesMatchingAGlob() {
    Setup setup = dependenciesOfAProjectSomeBehind();
    scannedIn(
        PROJECT_ID,
        "contract-tooling",
        "0c0a1d1e-0000-4000-8000-00000000d003",
        RepositoryArchetype.FRONTEND,
        ParsedPin.of(
            Ecosystem.NPM, "package.json", "typescript", "5.9.2", null, "dependency:typescript"));
    latest(Ecosystem.NPM, "typescript", LatestLookup.found("5.9.3", null));
    return setup;
  }

  /** An empty inventory: no repository, so no pin of either kind. */
  private Setup noDependencies() {
    emptyEstate();
    return new Setup(Map.of(), List.of());
  }

  /**
   * Four repositories of {@link #PROJECT_ID}, what each released, and the bills of materials behind
   * those releases — the landing app's artifacts page (qits-2330):
   *
   * <ul>
   *   <li>{@code contract-javalib} released the maven jar {@code eu.wohlben.qits:contract-core}
   *       2026.1101.2, INGESTED, whose latest is looked up as that same 2026.1101.2.
   *   <li>{@code contract-service} pins it and released the image {@code qits/contract-service},
   *       which embeds it DIRECTLY at 2026.1101.2 — current — and the maven jar {@code
   *       eu.wohlben.qits:contract-service-client}, which embeds it TRANSITIVELY at 2026.1101.2,
   *       through {@code eu.wohlben.qits:contract-api}.
   *   <li>{@code contract-daemon} pins it a release back and released the daemon binary {@code
   *       contract-daemon}, which embeds it DIRECTLY at 2026.1101.1 — behind.
   *   <li>{@code contract-frontend} released the npm package {@code @qits/contract-ui}, whose
   *       document qits-artifacts does not hold: MISSING, so nothing is known of what it embeds.
   * </ul>
   *
   * <p>{@code listArtifacts} answers the five, contract-core with three dependents of which one is
   * behind; {@code listDependencyDependents} for contract-core answers those three; and {@code
   * listRepositories} the four.
   */
  private Setup releasedArtifactsSomeBehind() {
    emptyEstate();
    String core = "eu.wohlben.qits:contract-core";
    scannedIn(
        PROJECT_ID,
        "contract-javalib",
        "0c0a1d1e-0000-4000-8000-00000000e001",
        RepositoryArchetype.LIBRARY);
    scannedIn(
        PROJECT_ID,
        REPOSITORY,
        REPOSITORY_ID,
        RepositoryArchetype.SERVICE,
        ParsedPin.of(Ecosystem.MAVEN, "pom.xml", core, "2026.1101.2", null, "dependency:" + core));
    scannedIn(
        PROJECT_ID,
        "contract-daemon",
        "0c0a1d1e-0000-4000-8000-00000000e002",
        RepositoryArchetype.DAEMON,
        ParsedPin.of(Ecosystem.MAVEN, "pom.xml", core, "2026.1101.1", null, "dependency:" + core));
    scannedIn(
        PROJECT_ID,
        "contract-frontend",
        "0c0a1d1e-0000-4000-8000-00000000e003",
        RepositoryArchetype.FRONTEND);
    store.replaceGraph(
        store.upsertArtifact(
            Ecosystem.MAVEN,
            core,
            "2026.1101.2",
            "contract-javalib",
            Instant.parse("2026-11-01T10:00:00Z")),
        List.of(
            sbomComponent(
                Ecosystem.MAVEN, "com.fasterxml.jackson.core:jackson-databind", "2.18.2", true)),
        List.of(new ParsedSbom.Edge(-1, 0)),
        SCANNED);
    store.replaceGraph(
        store.upsertArtifact(
            Ecosystem.DOCKER,
            "qits/contract-service",
            "2026.1102.1",
            REPOSITORY,
            Instant.parse("2026-11-02T10:00:00Z")),
        List.of(sbomComponent(Ecosystem.MAVEN, core, "2026.1101.2", true)),
        List.of(new ParsedSbom.Edge(-1, 0)),
        SCANNED);
    store.replaceGraph(
        store.upsertArtifact(
            Ecosystem.MAVEN,
            "eu.wohlben.qits:contract-service-client",
            "2026.1102.1",
            REPOSITORY,
            Instant.parse("2026-11-02T10:00:00Z")),
        List.of(
            sbomComponent(Ecosystem.MAVEN, "eu.wohlben.qits:contract-api", "2026.1102.1", true),
            sbomComponent(Ecosystem.MAVEN, core, "2026.1101.2", false)),
        List.of(new ParsedSbom.Edge(-1, 0), new ParsedSbom.Edge(0, 1)),
        SCANNED);
    store.replaceGraph(
        store.upsertDaemonArtifact(
            "contract-daemon",
            "2026.1104.1",
            "contract-daemon",
            Instant.parse("2026-11-04T10:00:00Z")),
        List.of(sbomComponent(Ecosystem.MAVEN, core, "2026.1101.1", true)),
        List.of(new ParsedSbom.Edge(-1, 0)),
        SCANNED);
    store.markArtifactMissing(
        store.upsertArtifact(
            Ecosystem.NPM,
            "@qits/contract-ui",
            "2026.1103.1",
            "contract-frontend",
            Instant.parse("2026-11-03T10:00:00Z")));
    latest(Ecosystem.MAVEN, core, LatestLookup.found("2026.1101.2", null));
    Map<String, String> params = new TreeMap<>();
    params.put("projectId", PROJECT_ID);
    return new Setup(params, List.of());
  }

  /**
   * {@link #PINNED_REPOSITORY}, a library of {@link #PROJECT_ID}, and the estate around it:
   *
   * <ul>
   *   <li>Its pins: INTERNAL {@code eu.wohlben.qits:qits-eventstream} 2026.811.1, pending
   *       2026.821.3, and {@code eu.wohlben.qits:qits-arch-rules} 2026.1101.1, current; EXTERNAL
   *       {@code io.quarkus.platform:quarkus-bom} 3.30.1, pending 3.40.1, and {@code
   *       org.junit:junit-bom} 6.0.1, current; REACTOR {@code eu.wohlben.qits:contract-api} at
   *       {@code ${project.version}}; UNRESOLVED {@code org.example:mystery} at {@code
   *       ${mystery.version}}.
   *   <li>It released {@code eu.wohlben.qits:contract-core} 2026.1101.2 (latest that same version),
   *       whose SBOM names eventstream direct and, through it, {@code jackson-databind} 2.18.2 —
   *       behind 2.18.3 — and {@code slf4j-api} 2.0.16, current: the two transitives.
   *   <li>{@code contract-service} pins contract-core 2026.1101.2 and released the image {@code
   *       qits/contract-service} embedding it at that version (current) and the jar {@code
   *       eu.wohlben.qits:contract-service-client}; {@code contract-daemon} pins it at 2026.1101.1
   *       and released the daemon binary {@code contract-daemon} embedding that (behind). Both are
   *       downstream at depth 1, and {@code contract-cli}, pinning contract-service-client, at depth
   *       2 through contract-service.
   *   <li>Its bumps, newest first: a RUNNING group bump of quarkus-bom, a SUCCEEDED {@code
   *       dependency-bump} automation of {@link #REQUEST_ID} that pushed a commit, and a
   *       NOTHING_TO_DO {@code entity-diagram} one.
   * </ul>
   */
  private Setup repositoryWithPinsAndBumps() {
    emptyEstate();
    String core = "eu.wohlben.qits:contract-core";
    String client = "eu.wohlben.qits:contract-service-client";
    Map<String, PinKind> kinds =
        Map.of(
            "eu.wohlben.qits:contract-api",
            PinKind.REACTOR,
            "org.example:mystery",
            PinKind.UNRESOLVED);
    store.replaceInventory(
        PINNED_REPOSITORY,
        PROJECT_ID,
        "0c0a1d1e-0000-4000-8000-00000000f001",
        RepositoryArchetype.LIBRARY.name(),
        "main",
        RepositoryStatus.OK,
        HEAD_SHA,
        null,
        List.of(
            eventstream(),
            ParsedPin.of(
                Ecosystem.MAVEN,
                "pom.xml",
                "eu.wohlben.qits:qits-arch-rules",
                "2026.1101.1",
                null,
                "dependency:eu.wohlben.qits:qits-arch-rules"),
            quarkusBom("3.30.1"),
            ParsedPin.of(
                Ecosystem.MAVEN,
                "pom.xml",
                "org.junit:junit-bom",
                "6.0.1",
                null,
                "dependency:org.junit:junit-bom"),
            ParsedPin.of(
                Ecosystem.MAVEN,
                "pom.xml",
                "eu.wohlben.qits:contract-api",
                "${project.version}",
                null,
                "dependency:eu.wohlben.qits:contract-api"),
            ParsedPin.of(
                Ecosystem.MAVEN,
                "pom.xml",
                "org.example:mystery",
                "${mystery.version}",
                null,
                "dependency:org.example:mystery")),
        List.of(
            GroupConfig.Group.ofKind(GroupConfig.DEFAULT_GROUP, PinKind.INTERNAL),
            GroupConfig.Group.ofKind(GroupConfig.EXTERNAL_GROUP, PinKind.EXTERNAL)),
        GroupSource.DEFAULT,
        pin ->
            kinds.getOrDefault(
                pin.name(),
                pin.name().startsWith("eu.wohlben.qits:") ? PinKind.INTERNAL : PinKind.EXTERNAL),
        SCANNED);
    scannedIn(
        PROJECT_ID,
        REPOSITORY,
        REPOSITORY_ID,
        RepositoryArchetype.SERVICE,
        ParsedPin.of(Ecosystem.MAVEN, "pom.xml", core, "2026.1101.2", null, "dependency:" + core));
    scannedIn(
        PROJECT_ID,
        "contract-daemon",
        "0c0a1d1e-0000-4000-8000-00000000f002",
        RepositoryArchetype.DAEMON,
        ParsedPin.of(Ecosystem.MAVEN, "pom.xml", core, "2026.1101.1", null, "dependency:" + core));
    scannedIn(
        PROJECT_ID,
        "contract-cli",
        "0c0a1d1e-0000-4000-8000-00000000f003",
        RepositoryArchetype.CLI,
        ParsedPin.of(
            Ecosystem.MAVEN, "pom.xml", client, "2026.1102.1", null, "dependency:" + client));
    store.replaceGraph(
        store.upsertArtifact(
            Ecosystem.MAVEN,
            core,
            "2026.1101.2",
            PINNED_REPOSITORY,
            Instant.parse("2026-11-01T10:00:00Z")),
        List.of(
            sbomComponent(Ecosystem.MAVEN, "eu.wohlben.qits:qits-eventstream", "2026.811.1", true),
            sbomComponent(
                Ecosystem.MAVEN, "com.fasterxml.jackson.core:jackson-databind", "2.18.2", false),
            sbomComponent(Ecosystem.MAVEN, "org.slf4j:slf4j-api", "2.0.16", false)),
        List.of(
            new ParsedSbom.Edge(-1, 0), new ParsedSbom.Edge(0, 1), new ParsedSbom.Edge(0, 2)),
        SCANNED);
    store.replaceGraph(
        store.upsertArtifact(
            Ecosystem.DOCKER,
            "qits/contract-service",
            "2026.1102.1",
            REPOSITORY,
            Instant.parse("2026-11-02T10:00:00Z")),
        List.of(sbomComponent(Ecosystem.MAVEN, core, "2026.1101.2", true)),
        List.of(new ParsedSbom.Edge(-1, 0)),
        SCANNED);
    store.upsertArtifact(
        Ecosystem.MAVEN, client, "2026.1102.1", REPOSITORY, Instant.parse("2026-11-02T10:00:00Z"));
    store.replaceGraph(
        store.upsertDaemonArtifact(
            "contract-daemon",
            "2026.1104.1",
            "contract-daemon",
            Instant.parse("2026-11-04T10:00:00Z")),
        List.of(sbomComponent(Ecosystem.MAVEN, core, "2026.1101.1", true)),
        List.of(new ParsedSbom.Edge(-1, 0)),
        SCANNED);
    latest(Ecosystem.MAVEN, core, LatestLookup.found("2026.1101.2", null));
    latest(
        Ecosystem.MAVEN,
        "eu.wohlben.qits:qits-eventstream",
        LatestLookup.found("2026.821.3", null));
    latest(
        Ecosystem.MAVEN, "eu.wohlben.qits:qits-arch-rules", LatestLookup.found("2026.1101.1", null));
    latest(Ecosystem.MAVEN, "io.quarkus.platform:quarkus-bom", LatestLookup.found("3.40.1", null));
    latest(Ecosystem.MAVEN, "org.junit:junit-bom", LatestLookup.found("6.0.1", null));
    latest(
        Ecosystem.MAVEN,
        "com.fasterxml.jackson.core:jackson-databind",
        LatestLookup.found("2.18.3", null));
    latest(Ecosystem.MAVEN, "org.slf4j:slf4j-api", LatestLookup.found("2.0.16", null));

    bump(
        PINNED_REPOSITORY,
        "GROUP",
        "maintenance/dependencies",
        "RUNNING",
        FUTURE.plusSeconds(3),
        change("MAVEN", "pom.xml", "io.quarkus.platform:quarkus-bom", "3.30.1", "3.40.1"),
        null,
        null);
    repositoryAutomation(
        "dependency-bump",
        "SUCCEEDED",
        FUTURE.plusSeconds(2),
        "run-contract-2",
        "SUCCESS",
        "committed the dependency bump",
        "0123456789abcdef0123456789abcdef01234567");
    String diagramBranch = AutomationService.BRANCH_PREFIX + "entity-diagram/" + REQUEST_ID;
    repositoryAutomation(
        "entity-diagram",
        "NOTHING_TO_DO",
        FUTURE.plusSeconds(1),
        "run-contract-1",
        "SUCCESS",
        "unchanged: the run passed and "
            + diagramBranch
            + " did not move, so entity diagram are fresh at "
            + BUMP_FOLD_SHA.substring(0, 12),
        null);
    Map<String, String> params = new TreeMap<>();
    params.put("repoName", PINNED_REPOSITORY);
    return new Setup(params, List.of());
  }

  /** {@link #REPOSITORY} scanned OK with no pin, and nothing else in the estate. */
  private Setup repositoryWithNothingPinned() {
    emptyEstate();
    scannedIn(PROJECT_ID, REPOSITORY, REPOSITORY_ID, RepositoryArchetype.SERVICE);
    Map<String, String> params = new TreeMap<>();
    params.put("repoName", REPOSITORY);
    return new Setup(params, List.of());
  }

  /** An empty estate: {@link #UNKNOWN_REPOSITORY} is a name nothing scans. */
  private Setup noSuchRepository() {
    emptyEstate();
    Map<String, String> params = new TreeMap<>();
    params.put("repoName", UNKNOWN_REPOSITORY);
    return new Setup(params, List.of());
  }

  /** Nothing released and nothing scanned: no artifact, so nobody embeds anything. */
  private Setup noReleasedArtifacts() {
    emptyEstate();
    return new Setup(Map.of(), List.of());
  }

  /** One component of a released artifact's bill of materials; its bom-ref is its purl. */
  private static ParsedSbom.Component sbomComponent(
      Ecosystem ecosystem, String name, String version, boolean direct) {
    String purl = "pkg:" + ecosystem.wireName() + "/" + name.replace(':', '/') + "@" + version;
    return new ParsedSbom.Component(purl, purl, ecosystem, name, version, direct);
  }

  /** One repository of {@code project}, its pins INTERNAL when they are ours and EXTERNAL else. */
  private void scannedIn(
      String project,
      String repository,
      String catalogId,
      RepositoryArchetype archetype,
      ParsedPin... pins) {
    store.replaceInventory(
        repository,
        project,
        catalogId,
        archetype.name(),
        "main",
        RepositoryStatus.OK,
        HEAD_SHA,
        null,
        List.of(pins),
        List.of(
            GroupConfig.Group.ofKind(GroupConfig.DEFAULT_GROUP, PinKind.INTERNAL),
            GroupConfig.Group.ofKind(GroupConfig.EXTERNAL_GROUP, PinKind.EXTERNAL)),
        GroupSource.DEFAULT,
        pin ->
            pin.name().startsWith("eu.wohlben.qits:") || pin.name().startsWith("@qits/")
                ? PinKind.INTERNAL
                : PinKind.EXTERNAL,
        SCANNED);
  }

  private void latest(Ecosystem ecosystem, String name, LatestLookup lookup) {
    store.recordLatest(ecosystem, name, lookup, SCANNED);
  }

  private static ParsedPin eventstream() {
    return ParsedPin.of(
        Ecosystem.MAVEN,
        "pom.xml",
        "eu.wohlben.qits:qits-eventstream",
        "2026.811.1",
        null,
        "dependency:eu.wohlben.qits:qits-eventstream");
  }

  private static ParsedPin quarkusBom(String version) {
    return ParsedPin.of(
        Ecosystem.MAVEN,
        "pom.xml",
        "io.quarkus.platform:quarkus-bom",
        version,
        null,
        "dependency:io.quarkus.platform:quarkus-bom");
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
   * The kinds {@link #releaseRequestWithAutomations} seeds a row for: the four registered before
   * qits-1516, and deliberately not every kind this build offers. qits-projects-service's and
   * qits-platform-access-cli's pacts of this state pin {@code $.automations} at these four entries
   * (a max length of 4), so a row for each of the {@code golden-recordings-*} kinds would fail their
   * verification here although neither consumer cares which kinds it is told about. The catalogue
   * state is where every registered kind is answered.
   */
  private static final Set<String> SEEDED_KINDS =
      Set.of("dependency-bump", "entity-diagram", "estate-pins", "screenshot-baselines");

  /**
   * One release request at one fold, with a row for each of the {@link #SEEDED_KINDS}: screenshot
   * baselines FAILED (with its failing step and two CI runs), every other kind alternately FRESH and
   * COMMITTED. The trigger at that fold answers from those rows and asks no peer for them; the
   * kinds with no row are asked about a fold whose root holds neither a pom nor a package.json, so
   * they answer NOT_APPLICABLE and are left out of the answer.
   */
  private Setup releaseRequestWithAutomations() {
    emptyEstate();
    scanned(REPOSITORY, REPOSITORY_ID, RepositoryArchetype.SERVICE);
    peers.answer(
        PeerTarget.GITHOST,
        "/git/qits/" + REPOSITORY + "/tree/" + FOLD_SHA,
        FakePeers.Scripted.ok("{\"entries\":[]}", Map.of("Git-Commit-Sha", FOLD_SHA)));
    int other = 0;
    int second = 0;
    for (ReleaseRequestAutomation kind : automations.kinds()) {
      if (!SEEDED_KINDS.contains(kind.kind())) {
        continue;
      }
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

  /**
   * {@link #releaseRequestToRerun}, with the work item the re-run's commit names as a param — the
   * CLI's {@code --work-item}, sent as the body's only key.
   */
  private Setup releaseRequestToRerunForAWorkItem() {
    Setup setup = releaseRequestToRerun();
    Map<String, String> params = new TreeMap<>(setup.params());
    params.put("workItem", WORK_ITEM);
    return new Setup(params, setup.uniqueTokens());
  }

  private static Map<String, String> automationParams() {
    Map<String, String> params = new TreeMap<>();
    // What qits-projects names the request with since qits-1158.
    params.put("backingBranch", "release/" + REPOSITORY + "-rr-1");
    params.put("branch", BRANCH);
    params.put("foldSha", FOLD_SHA);
    params.put("kind", ScreenshotBaselinesAutomation.KIND);
    params.put("qualifiedId", REPOSITORY + "-rr-1");
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

  /** One FOLD automation row of {@link #REPOSITORY}, ended at {@link #FUTURE} plus a minute. */
  private UUID automationBump(
      String kind,
      String branch,
      String status,
      String runIds,
      String ciRunStatus,
      String message,
      boolean failedStep) {
    UUID id = UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              MtBump row = new MtBump();
              row.id = id;
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
              row.foldSha = BUMP_FOLD_SHA;
              row.ciRunId = runIds;
              row.ciRunStatus = ciRunStatus;
              row.message = message;
              row.startedAt = FUTURE;
              row.finishedAt = FUTURE.plusSeconds(60);
              if (failedStep) {
                row.failedStepIndex = 0;
                row.failedStepImage = "qits/build-images/ci-slot:latest";
                row.failedStepExit = 1;
                row.failureExcerpt =
                    "[ERROR] Tests run: 42, Failures: 1, Errors: 0, Skipped: 0\n"
                        + "[ERROR] GoldenMasterRecordingTest.goldenMastersMatchTheProvider:118"
                        + " golden-masters/pending-bumps/getBump.json differs\n"
                        + "[ERROR] BUILD FAILURE";
              }
              row.persist();
            });
    created.add(id);
    return id;
  }

  /**
   * One FOLD automation row of {@link #PINNED_REPOSITORY} for {@link #REQUEST_ID} at {@link
   * #BUMP_FOLD_SHA}, on its own branch, ended a minute after it started.
   */
  private void repositoryAutomation(
      String kind,
      String status,
      Instant startedAt,
      String runIds,
      String ciRunStatus,
      String message,
      String resultSha) {
    UUID id = UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              MtBump row = new MtBump();
              row.id = id;
              row.repository = PINNED_REPOSITORY;
              row.groupName = kind;
              row.mode = BumpMode.AUTOMATION.name();
              row.automationKind = kind;
              row.branch = AutomationService.BRANCH_PREFIX + kind + "/" + REQUEST_ID;
              row.environment = "dev";
              row.trigger = "FOLD";
              row.status = status;
              row.changes = "[]";
              row.releaseRequestId = REQUEST_ID;
              row.foldSha = BUMP_FOLD_SHA;
              row.ciRunId = runIds;
              row.ciRunStatus = ciRunStatus;
              row.message = message;
              row.resultSha = resultSha;
              row.startedAt = startedAt;
              row.finishedAt = startedAt.plusSeconds(60);
              row.persist();
            });
    created.add(id);
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
