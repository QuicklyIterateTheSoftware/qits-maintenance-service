package eu.wohlben.qits.maintenance.automation;

import eu.wohlben.qits.bootstrap.graph.Ecosystem;
import eu.wohlben.qits.bootstrap.graph.manifest.ParsedPin;
import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.control.ArtifactGraph;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.githost.FileLookup;
import eu.wohlben.qits.maintenance.githost.GitHostReader;
import eu.wohlben.qits.maintenance.githost.TreeLookup;
import eu.wohlben.qits.maintenance.manifest.ManifestScanner;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jboss.logging.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * <b>Golden recordings</b> (qits-1516): re-record a CONSUMER's pacts — {@code pacts/*.json}, flat
 * at the repository root on this estate — after the fold moved one of its {@code *-golden-masters}
 * pins. The recorded pacts were produced against the provider's previous golden masters, so a fold
 * whose {@code dependency-bump} commit moved such a pin fails its gate with "differs from what this
 * run produced" until somebody re-records them by hand ({@code bf5cfb7} moved {@code
 * qits.githost-golden-masters.version} in qits-maintenance-service, {@code 66eb409} re-recorded its
 * githost pact). This kind is that second commit, written by the platform.
 *
 * <h2>Two sources of a recorded pact</h2>
 *
 * <p>A two-part {@code pacts/<consumer>_<provider>.json} is recorded against a provider's golden
 * masters. A THREE-part {@code pacts/<consumer>_<library>_<provider>.json} is not (qits-1782): it is
 * written by a LIBRARY's pact kit — {@code EventstreamPactTest} from {@code
 * eu.wohlben.qits:qits-eventstream-contracts} records {@code
 * qits-githost-service_qits-eventstream-javalib_qits-events-service.json}, and the frontends record
 * {@code *_qits-ui-components-jslib_<provider>.json} from {@code @qits/ui-components} — so it moves
 * when the library's pin moves, and a bump moving only {@code qits.eventstream.version} used to
 * plan FRESH here and leave the gate red. The middle part names the library repository, so both
 * halves of this kind read the fold's {@code pacts/} listing as well as its manifests: a pin is a
 * <b>recording input</b> when it is a golden-masters pin OR the library a committed three-part pact
 * names produced it. "Produced" is {@link ArtifactGraph#producers()} — the released artifact's own
 * claim, keyed {@code "<ecosystem wire name> <pin name>"} exactly as the bump dispatcher reads it —
 * and, for an artifact no release has announced yet, the library's own declaration: the
 * coordinate is listed in the {@code artifacts:} of the library repository's {@code
 * .config/qits/release.yml} at its {@code main} (qits-1834). Never its name: a repository's role
 * suffix decides nothing (owner ruling 2026-11-03). The producers are one query, read once per plan
 * and not at all for a fold with no three-part pact; each library's {@code release.yml} is read at
 * most once per plan, and only for a pin the producers do not know.
 *
 * <h2>Two kinds, split by build shape</h2>
 *
 * <p>The consumer tests that record a pact are run by the repository's own build, and qits-ci's
 * kind file differs per build — so there are two kinds, {@link GoldenRecordingsMavenAutomation} and
 * {@link GoldenRecordingsNpmAutomation}, and they are <b>mutually exclusive per repository</b>: the
 * maven kind owns a fold with a root {@code pom.xml}, the npm kind one with a root {@code
 * package.json} and NO root {@code pom.xml}. No repository on the estate carries both at its root
 * (a maven service's webui is a submodule of its own, with no pacts), and the split is what keeps
 * the two kinds' per-subject paths disjoint: they name the same {@code pacts/*.json}, and at most
 * one of them names it for any one fold.
 *
 * <h2>Applies, plans, writes</h2>
 *
 * <ul>
 *   <li><b>Applies</b> when the fold has this kind's build shape AND either pins a golden masters
 *       of its ecosystem — read from the fold's root manifest, which is also the file the shape is
 *       decided from, so the common case is one blob read the other kinds of the settle already
 *       made — or commits a three-part library-kit pact, which is one listing of {@code pacts/}
 *       read only when the golden-masters answer was not already APPLIES (qits-edge-service pins no
 *       golden masters and records an eventstream pact). An unreadable fold is UNKNOWN, and so is
 *       an unreadable {@code pacts/} listing; a fold with no {@code pacts/} at all has no
 *       library-kit pact.
 *   <li><b>Plans</b> by comparing this ecosystem's recording inputs — its golden-masters pins and
 *       its library-kit pins — at the fold against the target's {@code main} head, both read
 *       through the scan's own discovery ({@link ManifestScanner#pinsAt}), and then, once the pins
 *       agree, the consumer's own recording SOURCES (qits-1957): every blob under {@link
 *       #sourceInputs()}, path to sha, in the fold's whole tree ({@link FoldReader#recursiveTree})
 *       against {@code main}'s. A pact is not a function of the pins alone — an npm app's {@code
 *       src/testing/golden-masters.ts} stamps each route of {@code src/testing/browser/routes.json}
 *       into the pacts its {@code *.store.pact.spec.ts} record, and a maven consumer's pacts are its
 *       REST clients' requests — and a fold that moved only those shipped stale pacts in
 *       qits-landing-app-rr-182. All equal is FRESH with no run: the recorded pacts were recorded
 *       from exactly these golden masters, library kits and sources, because {@code main}'s gate
 *       held them to it. Any difference — a moved pin, a new one, a removed one, a changed, added or
 *       removed source — is a run, and a moved pin answers before either tree is read. A {@code
 *       main} whose pins cannot be read is a run too, and so is a fold whose {@code pacts/} cannot
 *       be listed, and either tree unread or answered non-recursively by an older git host:
 *       re-recording when nothing moved writes nothing, while not re-recording when something did
 *       is a red gate.
 *   <li><b>DERIVED</b>: the pin it reads is what {@code dependency-bump} writes, so it is planned
 *       only once the bump is FRESH at the fold, and runs against the pins the request will ship.
 *   <li><b>Writes</b> through the shared core's {@code automations/<kind>.yml} with {@code
 *       commitPaths} {@code [":(glob)pacts/*.json"]} — never the provider's own {@code
 *       golden-masters/}, which is a different repository's output and no consumer's to write.
 * </ul>
 *
 * <h2>What it reads, and what it writes</h2>
 *
 * <p>It reads the manifests ({@link DependencyBumpAutomation#MANIFESTS}), where the pins live, and
 * its build shape's {@link #sourceInputs() sources} (qits-1957) — an npm app's {@code src/}, {@code
 * angular.json}, {@code tsconfig*.json} and {@code vitest*}, a maven consumer's {@code
 * **}{@code /src/main/**} and {@code **}{@code /src/test/**} — and declares both as its {@link
 * #inputPaths(AutomationSubject) inputs}, so a fold that touched any of them is planned again
 * rather than carried. That includes {@code screenshot-baselines}' route ledger: its commit of a
 * new {@code routes.json} re-plans this kind, which then re-records against it. The screenshot
 * references and the renderer record are excluded ({@link #SCREENSHOT_OUTPUT}): no pact spec reads
 * them, so a screenshot join that moved only those still carries. The pacts are its output and
 * not its input — {@code pacts/} lies under no source pathspec — so the re-fold its own commit
 * causes touches none of its inputs and carries over with no second run; and {@code
 * screenshot-baselines}, which declares no inputs, carries over a fold that changed only
 * automation output such as these pacts, so the two never chase each other. The {@code pacts/} LISTING it also reads (qits-1782) only says which
 * libraries' pins count; a re-record rewrites the files that listing names, it does not rename
 * them, so that stays true. Neither does a library's {@code release.yml} (qits-1834): it sits in
 * the library's own repository, read at that repository's {@code main}, never at the fold.
 *
 * <p><b>Its static {@link #committablePaths()} is empty</b>, as {@code estate-pins}' is: both kinds
 * write {@code pacts/*.json}, so the all-kind disjointness the registry holds can only stand with
 * the paths answered per subject, where the build shape keeps them apart.
 *
 * <p><b>No approval is required</b> ({@link #approvalRoles()} is empty): the re-recorded pacts are
 * checked by the gate itself — it re-runs the same consumer tests without the update switch, and the
 * providers verify the published pacts — the same reasoning {@code dependency-bump} gives for the
 * pins it moves. With no static paths the catalogue would open no approval requirement anyway.
 */
public abstract class GoldenRecordingsAutomation implements ReleaseRequestAutomation {

  /** What every run of either kind may stage: the consumer's recorded pacts at the root. */
  static final List<String> PACTS = List.of(":(glob)pacts/*.json");

  /**
   * What {@code screenshot-baselines} writes under an app's sources that no pact spec reads: the
   * references, the renderer record and the frozen pages (qits-1947). Its route ledger, {@code routes.json}, is NOT here — the
   * app's golden masters stamp its routes into the pacts (qits-1957).
   */
  static final List<String> SCREENSHOT_OUTPUT =
      List.of(
          ":(exclude,glob)**/__screenshots__/**",
          ":(exclude,glob)**/testing/browser/renderer.txt",
          ":(exclude,glob)**/testing/browser/freezes/**");

  /**
   * An npm app's recording sources (qits-1957): the pact specs and the golden masters under {@code
   * src/}, the route ledger they read, and the build and test configuration that decides what runs.
   */
  static final List<String> NPM_SOURCES =
      concat(
          List.of(":(glob)src/**", "angular.json", ":(glob)tsconfig*.json", ":(glob)vitest*"),
          SCREENSHOT_OUTPUT);

  /** A maven consumer's recording sources (qits-1957): its REST clients and its contract tests. */
  static final List<String> MAVEN_SOURCES =
      List.of(":(glob)**/src/main/**", ":(glob)**/src/test/**");

  /**
   * What either kind reads, for the static {@link #inputPaths()}: the manifests and both build
   * shapes' sources. The npm exclusions apply to the whole list, which takes nothing a maven
   * recording reads.
   */
  static final List<String> INPUTS =
      concat(DependencyBumpAutomation.MANIFESTS, concat(MAVEN_SOURCES, NPM_SOURCES));

  static final String POM = "pom.xml";

  static final String PACKAGE_JSON = "package.json";

  /** The directory the recorded pacts sit in, flat at the root. */
  static final String PACTS_DIRECTORY = "pacts";

  private static final Logger LOG = Logger.getLogger(GoldenRecordingsAutomation.class);

  /** The {@code artifacts:} types a library-kit pin can be: this service's two ecosystems. */
  private static final Set<String> KIT_TYPES =
      Set.of(Ecosystem.MAVEN.wireName(), Ecosystem.NPM.wireName());

  @Inject ManifestScanner scanner;

  /** Who released which coordinate: what makes a pin a library kit's (qits-1782). */
  @Inject ArtifactGraph graph;

  /** Where a library repository lives and what its {@code main} is called. */
  @Inject MaintenanceStore store;

  /**
   * Reads a library's {@code release.yml}, for a coordinate the producers do not know, and the
   * target's {@code main} tree the fold's sources are compared with (qits-1957).
   */
  @Inject GitHostReader gitHost;

  /** The ecosystem whose golden-masters pins this kind follows. */
  abstract Ecosystem ecosystem();

  /** Whether the fold has this kind's build shape: APPLIES, or why not, or UNKNOWN. */
  abstract Applicability shape(FoldReader fold);

  /** Whether a fold of this kind's shape pins a golden masters of its ecosystem. */
  abstract Applicability pinsGoldenMasters(FoldReader fold);

  /** Whether one parsed pin is a golden-masters pin of this kind's ecosystem. */
  abstract boolean goldenMasters(ParsedPin pin);

  /**
   * The pathspecs, exclusions included, of the sources this build shape records its pacts from
   * (qits-1957): what the plan compares besides the pins, and what a fold must not touch to carry.
   */
  abstract List<String> sourceInputs();

  /**
   * The shape first; then golden masters, then a library-kit pact (qits-1782). A golden-masters
   * APPLIES answers without listing {@code pacts/}; a three-part pact applies even beside a module
   * pom that could not be read, because the pact alone is enough; only then does an unread listing
   * or an unread module pom leave it UNKNOWN.
   */
  @Override
  public Applicability applicability(AutomationSubject subject) {
    FoldReader fold = subject.fold();
    Applicability shape = shape(fold);
    if (!shape.applied()) {
      return shape;
    }
    Applicability golden = pinsGoldenMasters(fold);
    if (golden.applied()) {
      return golden;
    }
    TreeLookup pacts = fold.tree(PACTS_DIRECTORY);
    switch (pacts.status()) {
      case FOUND -> {
        if (!libraries(pacts).isEmpty()) {
          return Applicability.applies();
        }
      }
      case ABSENT -> {
        // No pacts/ at all: no library-kit pact to re-record.
      }
      default -> {
        return Applicability.unknown(unlisted(pacts));
      }
    }
    if (golden.state() == Applicability.State.UNKNOWN) {
      return golden;
    }
    return Applicability.notApplicable(
        golden.reason()
            + ", and it commits no library-kit pact (pacts/<consumer>_<library>_<provider>.json)");
  }

  /** The shared core, and qits-ci's {@code automations/<kind>.yml}. */
  @Override
  public String pipeline() {
    return CiClient.AUTOMATION_EVENT_NAME;
  }

  /** None that holds for every repository: both kinds write the pacts, see the class javadoc. */
  @Override
  public List<String> committablePaths() {
    return List.of();
  }

  /**
   * The pacts, for a fold of this kind's build shape; nothing for any other fold — including one
   * whose shape could not be read, which is the conservative answer: nothing carries on it.
   */
  @Override
  public List<String> committablePaths(AutomationSubject subject) {
    return shape(subject.fold()).applied() ? PACTS : List.of();
  }

  /**
   * The manifests the pins live in and both build shapes' sources (qits-1957); the pacts are
   * output, not input.
   */
  @Override
  public List<String> inputPaths() {
    return INPUTS;
  }

  /** The manifests and this kind's own sources: see the class javadoc. */
  @Override
  public List<String> inputPaths(AutomationSubject subject) {
    return concat(DependencyBumpAutomation.MANIFESTS, sourceInputs());
  }

  @Override
  public Target target() {
    return Target.OWN_BRANCH;
  }

  /** Recorded against the golden masters the SOURCE kind leaves the manifests pinning. */
  @Override
  public Stage stage() {
    return Stage.DERIVED;
  }

  /** Checked by the gate itself (see the class javadoc): no approval is required. */
  @Override
  public Set<String> approvalRoles() {
    return Set.of();
  }

  /**
   * The fold's recording inputs against {@code main}'s: the pins first, then the sources, whose two
   * tree reads are made only once the pins agree. See the class javadoc.
   */
  @Override
  public Plan plan(AutomationSubject subject) {
    String project = subject.repository().project;
    String name = subject.repository().name;
    ManifestScanner.Pins atFold = scanner.pinsAt(project, name, subject.fold().sha());
    if (atFold.status() != FileLookup.Status.FOUND) {
      return Plan.unknown(
          "the manifests at the fold could not be read: "
              + (atFold.message() == null ? atFold.status().name() : atFold.message()));
    }
    String main = subject.repository().mainBranchOrDefault();
    ManifestScanner.Pins atMain = scanner.pinsAt(project, name, main);
    if (atMain.status() != FileLookup.Status.FOUND) {
      // Unknown is not "unchanged": a re-record that finds nothing moved writes nothing.
      return Plan.run(Map.of());
    }
    TreeLookup pacts = subject.fold().tree(PACTS_DIRECTORY);
    if (!pacts.found() && pacts.status() != FileLookup.Status.ABSENT) {
      // Which pins feed a library kit is unknown, and so, for the same reason, a run.
      return Plan.run(Map.of());
    }
    Set<String> libraries = pacts.found() ? libraries(pacts) : Set.of();
    Map<String, String> producers = libraries.isEmpty() ? Map.of() : graph.producers();
    Map<String, Set<String>> declared = new HashMap<>();
    if (!inputs(atFold.pins(), libraries, producers, declared)
        .equals(inputs(atMain.pins(), libraries, producers, declared))) {
      return Plan.run(Map.of());
    }
    // The pins agree; the sources the pacts are recorded from must too (qits-1957). A tree that
    // cannot be compared is a run, for the same reason an unread main's pins are.
    Map<String, String> atFoldSources = sources(subject.fold().recursiveTree());
    if (atFoldSources == null) {
      return Plan.run(Map.of());
    }
    Map<String, String> atMainSources = sources(gitHost.recursiveTree(project, name, main));
    if (atMainSources == null || !atFoldSources.equals(atMainSources)) {
      return Plan.run(Map.of());
    }
    return Plan.fresh(
        "every " + ecosystem().wireName() + " golden-masters and library-kit pin and every source"
            + " the recording reads at the fold is " + main + "'s, so the recorded pacts stand");
  }

  /**
   * Every blob of a whole tree under {@link #sourceInputs()}, by path to its object name — or null
   * when the tree is not a FOUND recursive listing, or names a source without its sha, since then
   * an equal answer would not mean equal sources.
   */
  private Map<String, String> sources(TreeLookup tree) {
    if (!tree.found() || !tree.recursive()) {
      return null;
    }
    List<String> pathspecs = sourceInputs();
    Map<String, String> out = new TreeMap<>();
    for (TreeLookup.TreeEntry entry : tree.entries()) {
      if (entry.isBlob() && Pathspecs.matchesAny(pathspecs, entry.relativePath())) {
        if (entry.sha() == null) {
          return null;
        }
        out.put(entry.relativePath(), entry.sha());
      }
    }
    return out;
  }

  /**
   * This ecosystem's recording inputs — its golden-masters pins and the pins a committed
   * library-kit pact's library produced — by the line that sets them, to the version they name.
   */
  private Map<String, String> inputs(
      List<ParsedPin> pins,
      Set<String> libraries,
      Map<String, String> producers,
      Map<String, Set<String>> declared) {
    Map<String, String> out = new TreeMap<>();
    for (ParsedPin pin : pins) {
      if (pin.ecosystem() == ecosystem()
          && (goldenMasters(pin) || libraryKit(pin, libraries, producers, declared))) {
        out.put(pin.manifestPath() + " " + pin.location() + " " + pin.name(), pin.version());
      }
    }
    return out;
  }

  /**
   * Whether a pin is one of those libraries' releases: the producers say so, or — for a coordinate
   * no release has announced, so absent from them — a library's {@code release.yml} lists it among
   * its {@code artifacts:}. {@code declared} is this plan's memory of what each library declares,
   * so each file is read at most once however many pins ask.
   */
  private boolean libraryKit(
      ParsedPin pin,
      Set<String> libraries,
      Map<String, String> producers,
      Map<String, Set<String>> declared) {
    if (libraries.isEmpty() || pin.name() == null) {
      return false;
    }
    String key = ArtifactGraph.producerKey(ecosystem().wireName(), pin.name());
    String producer = producers.get(key);
    if (producer != null) {
      return libraries.contains(producer);
    }
    for (String library : libraries) {
      if (declared.computeIfAbsent(library, this::declaredArtifacts).contains(key)) {
        return true;
      }
    }
    return false;
  }

  /**
   * What a library repository says it publishes: every maven and npm entry of the {@code
   * artifacts:} list in its {@code .config/qits/release.yml} at its {@code main}, keyed as {@link
   * ArtifactGraph#producerKey} keys a pin — a maven {@code name} is {@code groupId:artifactId}, the
   * spelling a maven pin carries, and an npm {@code name} is the package's. Other types ({@code
   * docker}, {@code docs}, ...) are no pin of a library kit. A repository the inventory does not
   * hold, a file that cannot be read or parsed, or one listing no artifacts declares nothing, so it
   * claims no pin.
   */
  private Set<String> declaredArtifacts(String library) {
    Optional<MtRepository> row = store.repository(library);
    if (row.isEmpty()) {
      LOG.debugf("%s is not in the inventory; it claims no library-kit pin", library);
      return Set.of();
    }
    String path = QaChecks.RELEASE_YML;
    FileLookup file =
        gitHost.blob(row.get().project, library, row.get().mainBranchOrDefault(), path);
    if (file.status() != FileLookup.Status.FOUND) {
      LOG.debugf("%s: %s could not be read (%s); it claims no library-kit pin", library, path,
          file.status());
      return Set.of();
    }
    Object document;
    try {
      LoaderOptions options = new LoaderOptions();
      options.setAllowDuplicateKeys(false);
      document = new Yaml(new SafeConstructor(options)).load(file.content());
    } catch (RuntimeException e) {
      LOG.debugf("%s: %s is not valid yaml; it claims no library-kit pin", library, path);
      return Set.of();
    }
    Set<String> out = new HashSet<>();
    if (document instanceof Map<?, ?> slots && slots.get("artifacts") instanceof List<?> artifacts) {
      for (Object artifact : artifacts) {
        if (artifact instanceof Map<?, ?> fields
            && fields.get("type") instanceof String type
            && KIT_TYPES.contains(type.trim())
            && fields.get("name") instanceof String name
            && !name.isBlank()) {
          out.add(ArtifactGraph.producerKey(type.trim(), name.trim()));
        }
      }
    }
    if (out.isEmpty()) {
      LOG.debugf("%s: %s lists no maven or npm artifacts; it claims no library-kit pin", library,
          path);
    }
    return out;
  }

  /**
   * The library repositories the listed pacts were recorded from: the middle part of every {@code
   * <consumer>_<library>_<provider>.json} blob, three non-empty parts and no more.
   */
  static Set<String> libraries(TreeLookup pacts) {
    Set<String> out = new TreeSet<>();
    for (TreeLookup.TreeEntry entry : pacts.entries()) {
      String file = entry.name();
      if (!entry.isBlob() || file == null || !file.endsWith(".json")) {
        continue;
      }
      String[] parts = file.substring(0, file.length() - ".json".length()).split("_", -1);
      if (parts.length == 3 && !parts[0].isEmpty() && !parts[1].isEmpty() && !parts[2].isEmpty()) {
        out.add(parts[1]);
      }
    }
    return out;
  }

  private static String unlisted(TreeLookup pacts) {
    return pacts.message() == null
        ? PACTS_DIRECTORY + "/ could not be listed at the fold (" + pacts.status() + ")"
        : pacts.message();
  }

  private static List<String> concat(List<String> first, List<String> second) {
    List<String> out = new ArrayList<>(first);
    out.addAll(second);
    return List.copyOf(out);
  }

  static String unread(String path, FileLookup lookup) {
    return lookup.message() == null
        ? path + " could not be read at the fold (" + lookup.status() + ")"
        : lookup.message();
  }
}
