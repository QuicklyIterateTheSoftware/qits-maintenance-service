package eu.wohlben.qits.maintenance.manifest;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * A repository's {@code .config/qits/release.yml} — of which this service reads ONE thing, the
 * keys of {@code contracts.pacts} (qits-1952): the providers this repository, as a CONSUMER,
 * declares a pact against.
 *
 * <p><b>Why this service reads it at all.</b> Maintenance only ever MOVED a pin a manifest already
 * declared, and a consumer's first published pact jar {@code eu.wohlben.qits:<C>-pacts-<P>} has no
 * pin in {@code P} — so no bump ever added one, and the provider's release gate drops an unpinned
 * consumer's deployed pacts: eight declared and published pacts were verified by nobody. The edge
 * declared here is what lets {@code pending/PactAdoptions} offer {@code P} the missing pin.
 *
 * <p><b>The keys are provider REPOSITORY names</b>, the same spelling qits-ci's {@code
 * CiContracts} builds the coordinate from ({@code <consumer-repo>-pacts-<provider-repo>}). The value
 * of each — {@code { packages: [maven] }} today — is qits-ci's, and any shape is tolerated: what is
 * published is read from what WAS published, never from what this file says will be.
 *
 * <p><b>The file is not this service's, so nothing in it is ever an error here.</b> Unlike {@link
 * RepositoryConfig}, whose one key switches bumps off and must not be guessed, a pact edge only ever
 * ADDS an offer: an absent file, a file that is not yaml, a {@code contracts} or {@code pacts} that
 * is not a mapping all read as "declares no pact", with {@link Parsed#problem} saying why for one
 * DEBUG line at the scan. A typo costs the consumer its adoption, never the repository its scan.
 */
public final class ReleaseConfig {

  /** The path a scan reads it from, in every repository. */
  public static final String PATH = ".config/qits/release.yml";

  private ReleaseConfig() {}

  /**
   * The declared providers, and why there are none when the file could not be used.
   *
   * @param providers every key of {@code contracts.pacts}, trimmed, distinct and sorted
   * @param problem why the file declares nothing usable, or null for a file that parsed (whether or
   *     not it declares a pact)
   */
  public record Parsed(List<String> providers, String problem) {

    public Parsed {
      providers = providers == null ? List.of() : List.copyOf(providers);
    }
  }

  /** What a repository without the file declares: no pact. */
  public static Parsed none() {
    return new Parsed(List.of(), null);
  }

  /** Reads one file. Never throws. */
  public static Parsed parse(String yaml) {
    if (yaml == null) {
      return none();
    }
    Object document;
    try {
      LoaderOptions options = new LoaderOptions();
      document = new Yaml(new SafeConstructor(options)).load(yaml);
    } catch (RuntimeException e) {
      return new Parsed(List.of(), PATH + " is not valid yaml: " + message(e));
    }
    if (document == null) {
      return none();
    }
    if (!(document instanceof Map<?, ?> root)) {
      return new Parsed(List.of(), PATH + " is not a mapping");
    }
    Object rawContracts = root.get("contracts");
    if (rawContracts == null) {
      return none();
    }
    if (!(rawContracts instanceof Map<?, ?> contracts)) {
      return new Parsed(List.of(), "`contracts` in " + PATH + " is not a mapping");
    }
    Object rawPacts = contracts.get("pacts");
    if (rawPacts == null) {
      return none();
    }
    if (!(rawPacts instanceof Map<?, ?> pacts)) {
      return new Parsed(List.of(), "`contracts.pacts` in " + PATH + " is not a mapping");
    }
    TreeSet<String> providers = new TreeSet<>();
    for (Object key : pacts.keySet()) {
      // A key yaml typed as something else — a number, a null — is no repository name.
      if (key instanceof String name && !name.isBlank()) {
        providers.add(name.trim());
      }
    }
    return new Parsed(List.copyOf(providers), null);
  }

  private static String message(RuntimeException e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.toString() : message.replace('\n', ' ');
  }
}
