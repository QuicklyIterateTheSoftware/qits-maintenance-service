package eu.wohlben.qits.maintenance.automation;

import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.control.ArtifactGraph;
import eu.wohlben.qits.maintenance.githost.FileLookup;
import eu.wohlben.qits.maintenance.githost.TreeLookup;
import eu.wohlben.qits.maintenance.manifest.ManifestScanner;
import eu.wohlben.qits.maintenance.manifest.ParsedPin;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

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
 * and, for an artifact no release has announced yet, the name rule {@link #fromComponent}: the
 * artifact is the library's component ({@code qits-eventstream} for {@code
 * qits-eventstream-javalib}) or starts with it and a dash. The producers are one query, read once
 * per plan and not at all for a fold with no three-part pact.
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
 *       through the scan's own discovery ({@link ManifestScanner#pinsAt}). All equal is FRESH with
 *       no run: the recorded pacts were recorded against exactly these golden masters and library
 *       kits, because {@code main}'s gate held them to it. Any difference — a moved pin, a new one,
 *       a removed one — is a run. A {@code main} whose pins cannot be read is a run too, and so is a
 *       fold whose {@code pacts/} cannot be listed: re-recording when nothing moved writes nothing,
 *       while not re-recording when something did is a red gate.
 *   <li><b>DERIVED</b>: the pin it reads is what {@code dependency-bump} writes, so it is planned
 *       only once the bump is FRESH at the fold, and runs against the pins the request will ship.
 *   <li><b>Writes</b> through the shared core's {@code automations/<kind>.yml} with {@code
 *       commitPaths} {@code [":(glob)pacts/*.json"]} — never the provider's own {@code
 *       golden-masters/}, which is a different repository's output and no consumer's to write.
 * </ul>
 *
 * <h2>What it reads, and what it writes</h2>
 *
 * <p>It reads the manifests ({@link DependencyBumpAutomation#MANIFESTS}), where the pins live; the
 * pacts are its output and not its input, so the re-fold its own commit causes touches none of its
 * inputs and carries over with no second run. The {@code pacts/} LISTING it also reads (qits-1782)
 * only says which libraries' pins count; a re-record rewrites the files that listing names, it does
 * not rename them, so that stays true.
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

  static final String POM = "pom.xml";

  static final String PACKAGE_JSON = "package.json";

  /** The directory the recorded pacts sit in, flat at the root. */
  static final String PACTS_DIRECTORY = "pacts";

  /** The role suffixes a library repository's name ends in; what is left is its component. */
  static final List<String> LIBRARY_ROLES = List.of("-javalib", "-jslib");

  @Inject ManifestScanner scanner;

  /** Who released which coordinate: what makes a pin a library kit's (qits-1782). */
  @Inject ArtifactGraph graph;

  /** The ecosystem whose golden-masters pins this kind follows. */
  abstract Ecosystem ecosystem();

  /** Whether the fold has this kind's build shape: APPLIES, or why not, or UNKNOWN. */
  abstract Applicability shape(FoldReader fold);

  /** Whether a fold of this kind's shape pins a golden masters of its ecosystem. */
  abstract Applicability pinsGoldenMasters(FoldReader fold);

  /** Whether one parsed pin is a golden-masters pin of this kind's ecosystem. */
  abstract boolean goldenMasters(ParsedPin pin);

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

  /** The manifests the pins live in; the pacts are output, not input. */
  @Override
  public List<String> inputPaths() {
    return DependencyBumpAutomation.MANIFESTS;
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

  /** The fold's recording inputs against {@code main}'s: see the class javadoc. */
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
    if (inputs(atFold.pins(), libraries, producers)
        .equals(inputs(atMain.pins(), libraries, producers))) {
      return Plan.fresh(
          "every " + ecosystem().wireName() + " golden-masters and library-kit pin at the fold is "
              + main + "'s, so the recorded pacts stand");
    }
    return Plan.run(Map.of());
  }

  /**
   * This ecosystem's recording inputs — its golden-masters pins and the pins a committed
   * library-kit pact's library produced — by the line that sets them, to the version they name.
   */
  private Map<String, String> inputs(
      List<ParsedPin> pins, Set<String> libraries, Map<String, String> producers) {
    Map<String, String> out = new TreeMap<>();
    for (ParsedPin pin : pins) {
      if (pin.ecosystem() == ecosystem()
          && (goldenMasters(pin) || libraryKit(pin, libraries, producers))) {
        out.put(pin.manifestPath() + " " + pin.location() + " " + pin.name(), pin.version());
      }
    }
    return out;
  }

  /**
   * Whether a pin is one of those libraries' releases: the producers say so, or — for a coordinate
   * no release has announced, so absent from them — its name is the library's component's.
   */
  private boolean libraryKit(ParsedPin pin, Set<String> libraries, Map<String, String> producers) {
    if (libraries.isEmpty() || pin.name() == null) {
      return false;
    }
    String producer = producers.get(ArtifactGraph.producerKey(ecosystem().wireName(), pin.name()));
    if (producer != null) {
      return libraries.contains(producer);
    }
    for (String library : libraries) {
      if (fromComponent(pin.name(), library)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The name rule: the pin's artifact — a maven pin's {@code artifactId}, an npm pin's name without
   * its scope — is the library repository's component (its name less {@link #LIBRARY_ROLES}) or
   * starts with it and a dash. An {@code @qits/} package drops the {@code qits-} its repository
   * carries ({@code @qits/ui-components} is {@code qits-ui-components-jslib}'s), so it is compared
   * with that prefix restored. Equal-or-dash, never a bare prefix: {@code qits-eventstream} must not
   * claim a {@code qits-eventstreams}.
   */
  static boolean fromComponent(String pinName, String library) {
    String component = library;
    for (String role : LIBRARY_ROLES) {
      if (component.endsWith(role)) {
        component = component.substring(0, component.length() - role.length());
        break;
      }
    }
    if (component.isEmpty()) {
      return false;
    }
    String artifact = pinName.substring(pinName.lastIndexOf(':') + 1);
    String scope = "";
    int slash = artifact.indexOf('/');
    if (artifact.startsWith("@") && slash > 0) {
      scope = artifact.substring(1, slash);
      artifact = artifact.substring(slash + 1);
    }
    return names(artifact, component)
        || ("qits".equals(scope) && names("qits-" + artifact, component));
  }

  private static boolean names(String artifact, String component) {
    return artifact.equals(component) || artifact.startsWith(component + "-");
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

  static String unread(String path, FileLookup lookup) {
    return lookup.message() == null
        ? path + " could not be read at the fold (" + lookup.status() + ")"
        : lookup.message();
  }
}
