package eu.wohlben.qits.maintenance.manifest;

import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * A repository's {@code .config/qits/repository.yml} — of which this service reads ONE key, {@code
 * repository.archived} (qits-1784).
 *
 * <p><b>The file is not this service's.</b> It belongs to the workspace tooling, which writes it
 * with {@code version: 1} and whatever else it keeps there; this service only asks whether the
 * repository says it is archived, because an archived repository is not owed dependency bumps
 * unless its own {@code maintenance.yml} says otherwise (see {@link DependencyBumps}). So every
 * other key is ignored, unknown ones included — refusing a key this service has never heard of would
 * turn somebody else's schema change into a CONFIG_ERROR here.
 *
 * <p><b>An absent or empty file means not archived</b>, and so does a file without a {@code
 * repository} section or a section without {@code archived}: archiving is something a repository
 * says, never something it is assumed to have said.
 *
 * <p><b>What it does read, it reads strictly.</b> Yaml that does not parse, a root or a {@code
 * repository} section that is not a mapping, or an {@code archived} that is not a boolean is a
 * CONFIG_ERROR on the repository row, naming this file — the same "a typo is not an opt-out" rule as
 * {@link GroupConfig}: {@code archived: yes-ish} read as "not archived" would go on bumping the very
 * repository somebody retired.
 */
public final class RepositoryConfig {

  /** The path a scan and a fold read it from, in every repository. */
  public static final String PATH = ".config/qits/repository.yml";

  private RepositoryConfig() {}

  /**
   * The one fact read, or the reason the file is not usable.
   *
   * @param archived whether {@code repository.archived} is true — false when the file says nothing
   * @param error the sentence for the repository row, or null
   */
  public record Parsed(boolean archived, String error) {

    public boolean ok() {
      return error == null;
    }
  }

  /** What a repository with no {@code .config/qits/repository.yml} is: not archived. */
  public static Parsed fallback() {
    return new Parsed(false, null);
  }

  /**
   * Reads one file.
   *
   * <p>Loaded with a SafeConstructor and duplicate keys refused, as {@link GroupConfig} is: it is
   * somebody else's repository, and two {@code archived} keys are two answers.
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
      // An empty file says nothing, which is what an absent one says too.
      return fallback();
    }
    if (!(document instanceof Map<?, ?> root)) {
      return invalid(PATH + " must be a mapping");
    }
    Object rawSection = root.get("repository");
    if (rawSection == null) {
      return fallback();
    }
    if (!(rawSection instanceof Map<?, ?> section)) {
      return invalid("`repository` in " + PATH + " must be a mapping");
    }
    Object rawArchived = section.get("archived");
    if (rawArchived == null) {
      return fallback();
    }
    if (!(rawArchived instanceof Boolean archived)) {
      return invalid(
          "`repository.archived` in " + PATH + " must be true or false, not `" + rawArchived + "`");
    }
    return new Parsed(archived, null);
  }

  private static Parsed invalid(String message) {
    return new Parsed(false, message);
  }

  private static String message(RuntimeException e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.toString() : message.replace('\n', ' ');
  }
}
