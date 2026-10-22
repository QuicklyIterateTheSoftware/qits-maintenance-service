package eu.wohlben.qits.maintenance.automation;

import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.githost.FileLookup;
import eu.wohlben.qits.maintenance.manifest.ManifestScanner;
import eu.wohlben.qits.maintenance.manifest.ParsedPin;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * <b>Golden recordings</b> (qits-1516): re-record a CONSUMER's pacts — {@code pacts/*.json}, flat
 * at the repository root on this estate — after the fold moved one of its {@code *-golden-masters}
 * pins. The recorded pacts were produced against the provider's previous golden masters, so a fold
 * whose {@code dependency-bump} commit moved such a pin fails its gate with "differs from what this
 * run produced" until somebody re-records them by hand ({@code bf5cfb7} moved {@code
 * qits.githost-golden-masters.version} in qits-maintenance-service, {@code 66eb409} re-recorded its
 * githost pact). This kind is that second commit, written by the platform.
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
 *   <li><b>Applies</b> when the fold has this kind's build shape AND pins a golden masters of its
 *       ecosystem — read from the fold's root manifest, which is also the file the shape is decided
 *       from, so the common case is one blob read the other kinds of the settle already made. An
 *       unreadable fold is UNKNOWN.
 *   <li><b>Plans</b> by comparing this ecosystem's golden-masters pins at the fold against the
 *       target's {@code main} head, both read through the scan's own discovery ({@link
 *       ManifestScanner#pinsAt}). All equal is FRESH with no run: the recorded pacts were recorded
 *       against exactly these golden masters, because {@code main}'s gate held them to it. Any
 *       difference — a moved pin, a new one, a removed one — is a run. A {@code main} whose pins
 *       cannot be read is a run too: re-recording when nothing moved writes nothing, while not
 *       re-recording when something did is a red gate.
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
 * inputs and carries over with no second run.
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

  @Inject ManifestScanner scanner;

  /** The ecosystem whose golden-masters pins this kind follows. */
  abstract Ecosystem ecosystem();

  /** Whether the fold has this kind's build shape: APPLIES, or why not, or UNKNOWN. */
  abstract Applicability shape(FoldReader fold);

  /** Whether a fold of this kind's shape pins a golden masters of its ecosystem. */
  abstract Applicability pinsGoldenMasters(FoldReader fold);

  /** Whether one parsed pin is a golden-masters pin of this kind's ecosystem. */
  abstract boolean goldenMasters(ParsedPin pin);

  @Override
  public Applicability applicability(AutomationSubject subject) {
    Applicability shape = shape(subject.fold());
    return shape.applied() ? pinsGoldenMasters(subject.fold()) : shape;
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

  /** The fold's golden-masters pins against {@code main}'s: see the class javadoc. */
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
    Map<String, String> fold = golden(atFold.pins());
    String main = subject.repository().mainBranchOrDefault();
    ManifestScanner.Pins atMain = scanner.pinsAt(project, name, main);
    if (atMain.status() != FileLookup.Status.FOUND) {
      // Unknown is not "unchanged": a re-record that finds nothing moved writes nothing.
      return Plan.run(Map.of());
    }
    if (fold.equals(golden(atMain.pins()))) {
      return Plan.fresh(
          "every " + ecosystem().wireName() + " golden-masters pin at the fold is " + main
              + "'s, so the recorded pacts stand");
    }
    return Plan.run(Map.of());
  }

  /** This ecosystem's golden-masters pins, by the line that sets them, to the version they name. */
  private Map<String, String> golden(List<ParsedPin> pins) {
    Map<String, String> out = new TreeMap<>();
    for (ParsedPin pin : pins) {
      if (pin.ecosystem() == ecosystem() && goldenMasters(pin)) {
        out.put(pin.manifestPath() + " " + pin.location() + " " + pin.name(), pin.version());
      }
    }
    return out;
  }

  static String unread(String path, FileLookup lookup) {
    return lookup.message() == null
        ? path + " could not be read at the fold (" + lookup.status() + ")"
        : lookup.message();
  }
}
