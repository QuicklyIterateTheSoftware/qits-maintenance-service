package eu.wohlben.qits.maintenance.manifest;

import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.GroupSource;
import eu.wohlben.qits.maintenance.model.PinKind;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * A repository's own {@code .config/qits/maintenance.yml}: which ecosystems it is not scanned for
 * ({@code ignore:}) and which dependencies no bump moves ({@code hold:}) — and the two built-in
 * groups every repository's pins are split into.
 *
 * <p><b>The groups are the INTERNAL/EXTERNAL split, and nothing else any more.</b> {@code
 * dependencies} claims every INTERNAL pin and {@code external} every EXTERNAL one; the dispatcher
 * reads the first to decide what a repository is owed, and the repository page shows both. They
 * used to name the {@code maintenance/<group>} branch a group's bump travelled on, and a repository
 * could declare FINER groups of its own under {@code groups:} — globs on dependency names, each its
 * own branch. qits-1133 retired those branches (R2) and the group code with them (R5): a
 * repository's pins are written by the {@code dependency-bump} release-request automation inside the
 * request they belong to, so a group no longer decides anything a file could configure. <b>A {@code
 * groups:} key is IGNORED with a WARN, never a parse failure</b> — a file written for the old
 * service must not turn its repository into a CONFIG_ERROR, which would hide every pin it has.
 *
 * <p><b>The file is optional.</b> An absent or empty one is the default: nothing ignored, nothing
 * held.
 *
 * <p><b>An invalid file is a CONFIG_ERROR on the repository row and nothing is bumped for it.</b>
 * The alternative — falling back to the default — would bump the very ecosystem or dependency the
 * author wrote down to protect, quietly.
 *
 * <p><b>The same file also carries {@code ignore:}, which takes a whole ECOSYSTEM off the
 * repository.</b> Grouping decides which branch a pin's bump travels on; {@code ignore} decides
 * that the pin is not one at all. An ignored ecosystem is not parsed, not stored, not grouped and
 * never pending — the pins simply do not exist for that repository, and a rescan after the line is
 * added makes the ones already stored disappear, because an inventory is replaced wholesale.
 *
 * <p><b>Why it exists: the qits-qits wrapper's gitlinks are bank markers, not version pins.</b> The
 * wrapper declares some forty-seven submodules and its own README says outright that the recorded
 * gitlinks are expected to lag — they exist so {@code git submodule update --init} works on a fresh
 * clone, while the submodules themselves follow their branches. Every entry carries
 * {@code ignore = all} for the same reason. Without an opt-out this service would read those
 * forty-seven lagging shas as forty-seven upgrades and open a nightly bump against every one of
 * them, fighting a doctrine the repository states in writing. {@code ignore: [gitlink]} in the
 * wrapper is the answer. The mechanism is general — any of the four ecosystems may be named, by any
 * repository — but that is the case it was built for.
 *
 * <p><b>And {@code hold:} (qits-1133) takes one DEPENDENCY off the bumps without taking it off the
 * inventory.</b> {@code ignore} says a whole ecosystem is not a pin at all; {@code hold} says a pin is
 * real, is read, is shown and may be behind — and is not to be moved. It is the escape hatch for a
 * breaking upstream: the {@code dependency-bump} automation plans every other pin at the fold and
 * leaves a held one where it is. Each entry is a dependency name in its own ecosystem's spelling,
 * globs allowed ({@code @angular/*}).
 *
 * <p><b>An unknown ecosystem name is invalid.</b> This file is this
 * service's OWN configuration surface, unlike {@code .gitmodules}, so strictness is right here: a
 * typo silently ignored would read as a working opt-out and bump the very ecosystem the author
 * meant to protect.
 */
public final class GroupConfig {

  private static final Logger LOG = Logger.getLogger(GroupConfig.class);

  /** The path a scan reads it from, in every repository. */
  public static final String PATH = ".config/qits/maintenance.yml";

  /**
   * The INTERNAL half of the split — what the dispatcher reads a repository's debt from. The name is
   * the retired {@code maintenance/dependencies} branch's, kept because {@code mt_group} rows and the
   * repository page already spell it.
   */
  public static final String DEFAULT_GROUP = "dependencies";

  /** The EXTERNAL half of the split. */
  public static final String EXTERNAL_GROUP = "external";

  private GroupConfig() {}

  /**
   * One group: a name, and either a kind or a set of globs to claim pins with.
   *
   * @param name the group, which is also the branch suffix
   * @param patterns the globs on a dependency name — empty for a kind group
   * @param kind the pin kind this group claims, or null when the patterns decide
   */
  public record Group(String name, List<String> patterns, PinKind kind) {

    /** A group that claims what its globs match. No file declares one since qits-1133 R5. */
    public static Group glob(String name, List<String> patterns) {
      return new Group(name, List.copyOf(patterns), null);
    }

    /** A group that claims every pin of one kind, and carries no globs at all. */
    public static Group ofKind(String name, PinKind kind) {
      return new Group(name, List.of(), kind);
    }
  }

  /**
   * The parse of one file, or the reason it is not usable.
   *
   * @param groups the groups, in the order they claim — the kind pair
   * @param source DEFAULT, or CONFIG for a file that did not parse
   * @param ignored the ecosystems this repository is not scanned for at all — empty by default
   * @param held the dependencies no bump moves, as names or globs — empty by default
   * @param error the sentence for the repository row, or null
   */
  public record Parsed(
      List<Group> groups,
      GroupSource source,
      Set<Ecosystem> ignored,
      List<String> held,
      String error) {

    public Parsed {
      held = held == null ? List.of() : List.copyOf(held);
    }

    /** The shape before {@code hold:} existed: nothing held. */
    public Parsed(List<Group> groups, GroupSource source, Set<Ecosystem> ignored, String error) {
      this(groups, source, ignored, List.of(), error);
    }

    /** Whether no bump may move this dependency. */
    public boolean holds(String dependency) {
      return dependency != null && Globs.matchesAny(held, dependency);
    }

    public boolean ok() {
      return error == null;
    }

    /** Whether this repository's pins in that ecosystem are to be read at all. */
    public boolean ignores(Ecosystem ecosystem) {
      return ignored.contains(ecosystem);
    }
  }

  /** What a repository with no {@code .config/qits/maintenance.yml} gets: the split. */
  public static Parsed fallback() {
    return new Parsed(kindTail(), GroupSource.DEFAULT, Set.of(), null);
  }

  /**
   * The two kind groups, in the order they claim: INTERNAL first.
   *
   * <p>The order is not cosmetic. A pin is claimed by the first group whose rule matches, and a pin
   * has exactly one kind — so the pair is unambiguous whichever way round it is written, and this
   * order is the one an operator reads on the page: our own releases, then everybody else's.
   */
  private static List<Group> kindTail() {
    return List.of(
        Group.ofKind(DEFAULT_GROUP, PinKind.INTERNAL), Group.ofKind(EXTERNAL_GROUP, PinKind.EXTERNAL));
  }

  /**
   * Reads one file.
   *
   * <p>The document is loaded with a SafeConstructor: it is somebody else's repository, and a yaml
   * loader that instantiates arbitrary classes reads that repository as code.
   */
  public static Parsed parse(String yaml) {
    Object document;
    try {
      LoaderOptions options = new LoaderOptions();
      options.setAllowDuplicateKeys(false);
      document = new Yaml(new SafeConstructor(options)).load(yaml);
    } catch (RuntimeException e) {
      return invalid(PATH + " is not valid yaml: " + message(e));
    }
    if (document == null) {
      // An empty file is a file that says nothing, which is what an absent one says too.
      return fallback();
    }
    if (!(document instanceof Map<?, ?> root)) {
      return invalid(PATH + " must be a mapping with an `ignore` or `hold` key");
    }
    Set<Ecosystem> ignored = EnumSet.noneOf(Ecosystem.class);
    Object rawIgnore = root.get("ignore");
    if (rawIgnore != null) {
      if (!(rawIgnore instanceof List<?> names)) {
        return invalid("`ignore` must be a list of ecosystem names — one of " + wireNames());
      }
      for (Object element : names) {
        if (!(element instanceof String wire) || wire.isBlank()) {
          return invalid("every entry of `ignore` must be an ecosystem name — one of " + wireNames());
        }
        Optional<Ecosystem> ecosystem = Ecosystem.of(wire.trim());
        if (ecosystem.isEmpty()) {
          // A TYPO IS NOT AN OPT-OUT. Skipping what we do not recognise would read as a working
          // `ignore` while the ecosystem it meant to protect went on being bumped nightly.
          return invalid(
              "`" + wire.trim() + "` is not an ecosystem — `ignore` takes " + wireNames());
        }
        ignored.add(ecosystem.get());
      }
    }
    List<String> held = new ArrayList<>();
    Object rawHold = root.get("hold");
    if (rawHold != null) {
      if (!(rawHold instanceof List<?> names)) {
        return invalid("`hold` must be a list of dependency names");
      }
      for (Object element : names) {
        if (!(element instanceof String dependency) || dependency.isBlank()) {
          // A HOLD THAT DOES NOT PARSE IS NOT A HOLD. Skipping it would bump the very dependency
          // somebody wrote down to protect, so the whole file is refused, as a bad `ignore` is.
          return invalid("every entry of `hold` must be a non-empty dependency name");
        }
        held.add(dependency.trim());
      }
    }
    if (root.containsKey("groups")) {
      // RETIRED, NOT REFUSED (qits-1133 R5). A file written for the group branches must not turn its
      // repository into a CONFIG_ERROR: that would hide every pin it has over a key that no longer
      // means anything. Said once per parse, which is once per scan of that repository.
      LOG.warnf(
          "%s declares `groups`, which is ignored: maintenance/<group> branches are retired"
              + " (qits-1133) and every pin is bumped in its release request's pre-run. `ignore`"
              + " and `hold` still apply.",
          PATH);
    }
    return defaults(ignored, held);
  }

  /**
   * The default grouping, carrying whatever the file's {@code ignore} took off the repository and
   * whatever its {@code hold} kept where it is.
   */
  private static Parsed defaults(Set<Ecosystem> ignored, List<String> held) {
    return new Parsed(kindTail(), GroupSource.DEFAULT, Set.copyOf(ignored), List.copyOf(held), null);
  }

  /** The four spellings {@code ignore} accepts, for the sentence a broken file is told. */
  private static String wireNames() {
    return String.join(
        ", ", java.util.Arrays.stream(Ecosystem.values()).map(Ecosystem::wireName).toList());
  }

  private static Parsed invalid(String message) {
    return new Parsed(List.of(), GroupSource.CONFIG, Set.of(), message);
  }

  private static String message(RuntimeException e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.toString() : message.replace('\n', ' ');
  }
}
