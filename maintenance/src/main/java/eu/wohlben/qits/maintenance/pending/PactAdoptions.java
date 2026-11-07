package eu.wohlben.qits.maintenance.pending;

import eu.wohlben.qits.bootstrap.graph.Ecosystem;
import eu.wohlben.qits.maintenance.entity.MtLatest;
import eu.wohlben.qits.maintenance.entity.MtPactEdge;
import eu.wohlben.qits.maintenance.entity.MtPin;
import eu.wohlben.qits.maintenance.latest.VersionOrder;
import eu.wohlben.qits.maintenance.model.PinKind;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * <b>The FIRST pin of a consumer's pact jar in its provider</b> (qits-1952) — the one edit a pin move
 * can never make, because there is no pin to move.
 *
 * <p><b>Why.</b> A consumer {@code C} that declares a pact against provider {@code P} in its {@code
 * .config/qits/release.yml} ({@code contracts.pacts}, keyed by {@code P}'s repository name) publishes
 * {@code eu.wohlben.qits:<C>-pacts-<P>} — qits-ci's {@code CiContracts} coordinate. {@code P}'s
 * release gate verifies only the consumers {@code P} PINS and drops every unpinned consumer's
 * deployed pacts, and maintenance only ever moved pins a manifest already declared — so a new
 * consumer's pact was verified by nobody, for good (eight such on 2026-11-07).
 *
 * <p><b>The shape: a transient pin, never persisted, that the ordinary pending rule then judges.</b>
 * {@link #adoptions} answers one {@link MtPin} per owed adoption — {@code version} null, {@code
 * kind} INTERNAL, {@code location} {@value #LOCATION}{@code <name>} — which the caller appends to
 * the provider's real pins. {@link PendingChanges#newerVersion} offers it the coordinate's {@code
 * mt_latest}, and from there it is grouped, held, planned, dispatched and deduped exactly as any
 * other change: the change it becomes is {@code {maven, <name>, from: null, to: <latest>,
 * manifestPath, location: "pact-adopt:<name>"}}, the contract fixed with qits-ci's {@code
 * dependency-bump.yml}, which clones a sibling pact pin (root pom property, {@code
 * dependencyManagement} entry and the versionless test dependency in each module pom) under the new
 * name. The location string carries the kind, so no wire shape changes.
 *
 * <p><b>Owed iff all four hold</b>, and each one is load-bearing:
 *
 * <ol>
 *   <li><b>The consumer declares the edge NOW</b> — its last scan stored it in {@code mt_pact_edge},
 *       which a scan replaces wholesale; an edge it stopped declaring is never adopted (qits-1765).
 *   <li><b>A version is ADOPTED</b>: the coordinate's {@code mt_latest}, which moves only through
 *       the finalization rule (qits-1472, {@code MaintenanceStore.adoptFinalized}) — a jar whose
 *       release request never FINALIZED is published and still not offered, as for any pin. That
 *       is the rule every move's {@code to} comes from, and a first pin is not owed a laxer one.
 *   <li><b>{@code P} pins no artifact of that name</b>, in any manifest at any location. The pin
 *       the bump writes is the next scan's ordinary pin, which this rule then skips and the move
 *       rule takes over — so an adoption is pended once and never beside its own pin.
 *   <li><b>{@code P} already pins a SIBLING</b> {@code eu.wohlben.qits:*-pacts-<P>}: the step
 *       clones one, and refuses where there is none — so a provider without one is not pended at
 *       all, and one INFO line per provider says why.
 * </ol>
 *
 * <p>A provider whose dependency bumps are off — archived, or {@code dependency-bumps: false}
 * (qits-1784) — is skipped by every caller before its pending changes are computed, adoptions
 * included.
 */
public final class PactAdoptions {

  /** The location prefix that marks an adoption change; the rest is the coordinate. */
  public static final String LOCATION = "pact-adopt:";

  /** The maven group every pact jar is published under (qits-ci {@code CiContracts}). */
  public static final String GROUP = "eu.wohlben.qits";

  /** The root manifest, preferred as the adoption's {@code manifestPath}. */
  private static final String ROOT_POM = "pom.xml";

  private static final Logger LOG = Logger.getLogger(PactAdoptions.class);

  /** The providers already told they hold no sibling pin: the INFO line is said once per process. */
  private static final Set<String> NO_SIBLING_LOGGED = ConcurrentHashMap.newKeySet();

  private PactAdoptions() {}

  /** {@code eu.wohlben.qits:<consumer>-pacts-<provider>} — qits-ci's coordinate rule. */
  public static String coordinate(String consumer, String provider) {
    return GROUP + ":" + consumer + "-pacts-" + provider;
  }

  /** Whether this pin is an adoption {@link #adoptions} made, rather than a manifest line. */
  public static boolean isAdoption(MtPin pin) {
    return pin != null && pin.location != null && pin.location.startsWith(LOCATION);
  }

  /** Whether this change is an adoption. */
  public static boolean isAdoption(Change change) {
    return change != null && change.location() != null && change.location().startsWith(LOCATION);
  }

  /**
   * The version an adoption pin is offered: the coordinate's adopted latest, unless it is a
   * prerelease — rule 2 of {@link PendingChanges}, with "the pin is not a prerelease" read as true
   * for a pin that has no version at all.
   */
  static Optional<String> offered(MtLatest row) {
    if (row == null || row.latest == null || row.latest.isBlank()) {
      return Optional.empty();
    }
    if (VersionOrder.prerelease(Ecosystem.MAVEN, row.latest)) {
      return Optional.empty();
    }
    return Optional.of(row.latest.trim());
  }

  /** Every edge, as provider → its consumers, both sorted. */
  public static Map<String, List<String>> consumersByProvider(List<MtPactEdge> edges) {
    Map<String, TreeSet<String>> grouped = new LinkedHashMap<>();
    for (MtPactEdge edge : edges) {
      grouped
          .computeIfAbsent(edge.providerRepository, provider -> new TreeSet<>())
          .add(edge.consumerRepository);
    }
    Map<String, List<String>> answer = new LinkedHashMap<>();
    grouped.forEach((provider, consumers) -> answer.put(provider, List.copyOf(consumers)));
    return answer;
  }

  /** The provider's pins with its owed adoptions appended — see {@link #adoptions}. */
  public static List<MtPin> withAdoptions(
      String provider, List<MtPin> pins, Collection<String> consumers) {
    List<MtPin> adoptions = adoptions(provider, pins, consumers);
    if (adoptions.isEmpty()) {
      return pins;
    }
    List<MtPin> all = new ArrayList<>(pins);
    all.addAll(adoptions);
    return List.copyOf(all);
  }

  /**
   * One transient pin per consumer that declares a pact against {@code provider} whose jar the
   * provider does not pin yet — rules 1, 3 and 4 of the class doc; rule 2 is {@link
   * PendingChanges#newerVersion}'s, through {@link #offered}.
   *
   * @param provider the repository whose pins these are
   * @param pins every pin of {@code provider}, at main or at a fold — any kind, any location
   * @param consumers the repositories whose stored edges name {@code provider}
   */
  public static List<MtPin> adoptions(
      String provider, List<MtPin> pins, Collection<String> consumers) {
    if (provider == null || consumers == null || consumers.isEmpty()) {
      return List.of();
    }
    String maven = Ecosystem.MAVEN.wireName();
    String suffix = "-pacts-" + provider;
    Set<String> pinned = new HashSet<>();
    List<MtPin> siblings = new ArrayList<>();
    for (MtPin pin : pins) {
      if (!maven.equals(pin.ecosystem) || pin.name == null || isAdoption(pin)) {
        continue;
      }
      pinned.add(pin.name);
      if (pin.name.startsWith(GROUP + ":") && pin.name.endsWith(suffix)) {
        siblings.add(pin);
      }
    }
    List<MtPin> adoptions = new ArrayList<>();
    String manifest = null;
    for (String consumer : new TreeSet<>(consumers)) {
      if (consumer == null || consumer.isBlank() || consumer.equals(provider)) {
        continue;
      }
      String name = coordinate(consumer, provider);
      if (pinned.contains(name)) {
        // Rule 3: adopted already — an ordinary pin now, and the move rule's.
        continue;
      }
      if (siblings.isEmpty()) {
        // Rule 4: nothing to clone, and the step would refuse.
        if (NO_SIBLING_LOGGED.add(provider)) {
          LOG.infof(
              "%s pins no %s:*%s to clone, so the pact jars its consumers declare (%s first) are"
                  + " not adopted until one exists (qits-1952)",
              provider, GROUP, suffix, name);
        }
        return List.of();
      }
      if (manifest == null) {
        manifest = manifestOf(siblings);
      }
      MtPin adoption = new MtPin();
      adoption.repository = provider;
      adoption.ecosystem = maven;
      adoption.manifestPath = manifest;
      adoption.name = name;
      adoption.version = null;
      adoption.range = null;
      adoption.kind = PinKind.INTERNAL.name();
      adoption.location = LOCATION + name;
      adoptions.add(adoption);
    }
    return List.copyOf(adoptions);
  }

  /**
   * The manifest the sibling's version is declared in — the root pom when any sibling is there,
   * which is where every pact pin on the estate keeps its property; otherwise the first by path.
   */
  private static String manifestOf(List<MtPin> siblings) {
    for (MtPin sibling : siblings) {
      if (ROOT_POM.equals(sibling.manifestPath)) {
        return ROOT_POM;
      }
    }
    return siblings.stream()
        .map(sibling -> sibling.manifestPath)
        .filter(path -> path != null && !path.isBlank())
        .min(Comparator.naturalOrder())
        .orElse(ROOT_POM);
  }
}
