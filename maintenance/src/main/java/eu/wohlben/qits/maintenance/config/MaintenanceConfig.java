package eu.wohlben.qits.maintenance.config;

import eu.wohlben.qits.maintenance.manifest.ParsedPin;
import eu.wohlben.qits.maintenance.model.PinKind;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The policy this service runs on: what counts as internal, whether the clock may scan, whether the
 * clock may bump, and which environment's CI applies one.
 *
 * <p><b>Nothing here decides what a manifest says or what a registry holds</b> — those are read.
 * What lives here is the handful of decisions a platform makes about its own maintenance, each one
 * an environment variable rather than a rebuild.
 */
@ApplicationScoped
public class MaintenanceConfig {

  @ConfigProperty(name = "qits.maintenance.environment")
  String environment;

  @ConfigProperty(name = "qits.maintenance.scan.enabled")
  boolean scanEnabled;

  @ConfigProperty(name = "qits.maintenance.bump.enabled")
  boolean bumpEnabled;

  @ConfigProperty(name = "qits.maintenance.bump.internal.auto")
  boolean bumpInternalAuto;

  @ConfigProperty(name = "qits.maintenance.bump.external.auto")
  boolean bumpExternalAuto;

  @ConfigProperty(name = "qits.maintenance.bump.dispatch.gated")
  boolean bumpDispatchGated;

  @ConfigProperty(name = "qits.maintenance.bump.internal.window")
  Duration bumpWindow;

  @ConfigProperty(name = "qits.maintenance.bump.dispatch.release-state-ttl")
  Duration bumpReleaseStateTtl;

  // OPTIONAL BECAUSE THE DEFAULT IS THE EMPTY STRING, and SmallRye converts an empty value to a
  // missing one: declared as a plain String this refuses to boot on the shipped default.
  @ConfigProperty(name = "qits.maintenance.bump.dispatch.quiet-hours")
  Optional<String> bumpQuietHours;

  @ConfigProperty(name = "qits.maintenance.time-zone")
  String timeZone;

  @ConfigProperty(name = "qits.maintenance.pre-run.upstream.enabled", defaultValue = "false")
  boolean preRunUpstreamEnabled;

  /** {@link #bumpQuietHours()}, parsed once. */
  private volatile QuietHours quietHours;

  @ConfigProperty(name = "qits.maintenance.internal.maven-groups")
  List<String> internalMavenGroups;

  @ConfigProperty(name = "qits.maintenance.internal.npm-scopes")
  List<String> internalNpmScopes;

  @ConfigProperty(name = "qits.maintenance.internal.image-prefixes")
  List<String> internalImagePrefixes;

  /** Which environment's qits-ci applies a bump. Recorded on every bump row. */
  public String environment() {
    return environment;
  }

  /** Whether the CLOCK may start a scan. A manual scan ignores it — a person is the trigger. */
  public boolean scanEnabled() {
    return scanEnabled;
  }

  /**
   * Whether a bump may be dispatched at all.
   *
   * <p>False stops the button as well as the schedule, and the UI still shows what is pending: a
   * platform that wants to watch the changes for a week before letting anything push a branch sets
   * this and reads the inventory.
   */
  public boolean bumpEnabled() {
    return bumpEnabled;
  }

  /**
   * Whether the CLOCK asks for the INTERNAL group's bumps — the nightly one.
   *
   * <p>It gates {@code schedule/BumpSchedule} and nothing else: the button is a person's decision
   * and answers to {@link #bumpEnabled()} alone. The jar defaults it true, because a platform that
   * installs this service wants its own releases to travel; the LIVE deployment holds it false until
   * the cutover, which is an environment variable rather than a release.
   */
  public boolean bumpInternalAuto() {
    return bumpInternalAuto;
  }

  /**
   * Whether the CLOCK asks for the EXTERNAL group's bumps — <b>reserved, and read only to refuse</b>.
   *
   * <p>External bumps are manual-only. The key exists so the deployment surface is the same shape as
   * the internal one the day it is implemented — a deployment that sets it gets a WARN saying so
   * rather than a silently ignored variable, which is the failure mode a key that was merely
   * documented would have.
   */
  public boolean bumpExternalAuto() {
    return bumpExternalAuto;
  }

  /**
   * Whether the nightly bump is DISPATCHED against qits-ci's free slots, or fired all at once the
   * way it used to be.
   *
   * <p>True — the default — makes {@code BumpDispatcher} hand out as many bumps per tick as qits-ci
   * has free slots, deepest upstream first. False restores the loop-and-fire: every eligible
   * repository asked for in one breath, which is what produced 30 simultaneous builds the first
   * night this ran. The old behaviour stays REACHABLE rather than removed, because a platform whose
   * qits-ci is not the bottleneck should be able to say so without a release.
   */
  public boolean bumpDispatchGated() {
    return bumpDispatchGated;
  }

  /**
   * <b>The upstream half of the pre-run (qits-1133)</b> — off by default, and the one switch R2's
   * cutover flips. On: every time {@code mt_latest} advances, each open release request of a
   * consumer that is not READY has its {@code dependency-bump} re-planned on its current fold (three
   * restarts without a QA verdict and it is left alone until it has one), and the dispatcher, where
   * it would have cut a {@code maintenance/dependencies} branch, opens a MAIN-ONLY {@code LOWEST}
   * request for the pre-run to write the bump into instead — and withdraws it again when that
   * pre-run finds nothing to write. Off: none of that, and group dispatch is exactly what it was.
   */
  public boolean preRunUpstreamEnabled() {
    return preRunUpstreamEnabled;
  }

  /**
   * How long after the nightly cron bumps may still be handed out.
   *
   * <p>Bounded by qits-ci's free slots and by each bump waiting on its upstream's release, the
   * night's work is spread over many ticks, so the window has to be wide enough for the whole chain and narrow enough that a
   * branch never arrives in somebody's working day. The window ends early and by itself the moment
   * nothing is owed, so this is a CEILING rather than a duration anything runs for.
   */
  public Duration bumpWindow() {
    return bumpWindow == null ? Duration.ofHours(6) : bumpWindow;
  }

  /**
   * How long qits-projects' answer about one release request is reused before it is asked again.
   *
   * <p>The dispatcher asks that question of every HELD candidate on every tick — "is the release
   * this branch is waiting for still coming" — and a chain waiting on ten releases would make ten
   * calls every fifteen seconds for hours. Never negative, and zero is honoured: it means ask every
   * tick, which is what a suite that drives ticks back to back wants.
   */
  public Duration bumpReleaseStateTtl() {
    if (bumpReleaseStateTtl == null || bumpReleaseStateTtl.isNegative()) {
      return Duration.ofMinutes(1);
    }
    return bumpReleaseStateTtl;
  }

  /**
   * The hours in which a bump may NOT be dispatched — <b>the only thing that says "not now"</b>.
   *
   * <p>Dispatch arms itself on debt (see {@code BumpDispatcher}),
   * so the hour no longer decides whether the work may start. What an hour can still decide is
   * whether a {@code maintenance/dependencies} branch is welcome to arrive in somebody's working
   * afternoon, and that is this key rather than an implication of a cron. Empty by default: a
   * platform that never asked for a quiet period does not silently have one.
   *
   * <p><b>Parsed once and kept</b>, because the tick asks for it every fifteen seconds and an
   * injected config field does not move under a running process anyway. Parsing it per read would
   * also repeat the typo WARN four thousand times a day, which is how a line worth reading becomes
   * one nobody does.
   */
  public QuietHours bumpQuietHours() {
    QuietHours parsed = quietHours;
    if (parsed == null) {
      parsed = QuietHours.parse(bumpQuietHours.orElse(null));
      quietHours = parsed;
    }
    return parsed;
  }

  /**
   * The zone every clock here is read in — the crons already declare it, and quiet hours are the
   * same fact from the other side. UTC is the platform's convention, so "18:00" in a config value
   * is the 18:00 a reader sees on a row.
   */
  public ZoneId timeZone() {
    if (timeZone == null || timeZone.isBlank()) {
      return ZoneOffset.UTC;
    }
    try {
      return ZoneId.of(timeZone.trim());
    } catch (RuntimeException e) {
      return ZoneOffset.UTC;
    }
  }

  /**
   * What can be done with one pin.
   *
   * <p><b>The two "nothing" answers come first, and they are not about who published it.</b> A pin
   * carrying an expression this service could not resolve is UNRESOLVED; one that is this
   * repository's own artifact — a version from maven's coordinates, or a coordinate that is a
   * module of this same reactor — is REACTOR. Both are recorded, because a person reading a
   * repository expects to see every dependency it declares, and neither is ever looked up or
   * bumped.
   */
  public PinKind kindOf(ParsedPin pin) {
    if (pin.unresolved()) {
      return PinKind.UNRESOLVED;
    }
    if (pin.reactorOwn()) {
      return PinKind.REACTOR;
    }
    return kindOf(pin.ecosystem(), pin.name());
  }

  /**
   * Whether this platform publishes the named dependency.
   *
   * <p><b>A name rule, not a lookup.</b> Asking a registry whether it holds a package would make
   * every scan a round trip per dependency, and a registry that is briefly down would reclassify
   * half the inventory as external — which would then be looked up against Maven Central, where
   * {@code eu.wohlben.qits} does not exist, and every internal pin would report no latest at all.
   *
   * <p><b>A GITLINK is INTERNAL by construction and has no key.</b> A submodule is a repository on
   * this platform's own git host — nothing else can be one — so there is no name rule to configure
   * and no external half for the answer to be wrong about. A key for it would be a knob whose only
   * correct setting is the default, and setting it would send gitlink pins to a registry that has
   * never heard of a git repository.
   */
  public PinKind kindOf(eu.wohlben.qits.maintenance.model.Ecosystem ecosystem, String name) {
    if (name == null) {
      return PinKind.EXTERNAL;
    }
    String value = name.trim();
    boolean internal =
        switch (ecosystem) {
          case MAVEN -> matchesGroup(value);
          case NPM -> startsWithAny(value, internalNpmScopes, true);
          case DOCKER -> startsWithAny(value, internalImagePrefixes, false);
          case GITLINK -> true;
        };
    return internal ? PinKind.INTERNAL : PinKind.EXTERNAL;
  }

  /**
   * A maven name is {@code groupId:artifactId}, and a configured group matches the whole groupId or
   * a parent of it — {@code eu.wohlben.qits} claims {@code eu.wohlben.qits.something} and does not
   * claim {@code eu.wohlben.qitsy}.
   */
  private boolean matchesGroup(String name) {
    int colon = name.indexOf(':');
    String groupId = colon < 0 ? name : name.substring(0, colon);
    for (String configured : internalMavenGroups) {
      String prefix = configured.trim();
      if (prefix.isEmpty()) {
        continue;
      }
      if (groupId.equals(prefix) || groupId.startsWith(prefix + ".")) {
        return true;
      }
    }
    return false;
  }

  /**
   * @param caseInsensitive npm names are lower case by rule but a manifest can spell one otherwise;
   *     an image name is case-sensitive and a prefix that differs in case is a different registry
   *     path
   */
  private static boolean startsWithAny(
      String value, List<String> prefixes, boolean caseInsensitive) {
    String candidate = caseInsensitive ? value.toLowerCase(Locale.ROOT) : value;
    for (String configured : prefixes) {
      String prefix = configured.trim();
      if (prefix.isEmpty()) {
        continue;
      }
      String compare = caseInsensitive ? prefix.toLowerCase(Locale.ROOT) : prefix;
      if (candidate.startsWith(compare)) {
        return true;
      }
    }
    return false;
  }
}
