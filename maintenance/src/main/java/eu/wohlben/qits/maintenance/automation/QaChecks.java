package eu.wohlben.qits.maintenance.automation;

import eu.wohlben.qits.maintenance.config.MaintenanceConfig;
import eu.wohlben.qits.maintenance.githost.FileLookup;
import eu.wohlben.qits.maintenance.githost.GitHostReader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * <b>Which outputs a repository's QA checks, at one fold</b> (qits-1178): the kinds its QA steps
 * name under {@code check-outputs:}. A kind QA checks is not run before QA (qits-1180).
 *
 * <p><b>Read from the files qits-ci composes the QA from, in qits-ci's order</b>, so the answer
 * cannot differ from the QA run at the same fold:
 *
 * <ol>
 *   <li>{@code .config/qits/release.yml} at the fold. Its own {@code release-request:} slot replaces
 *       the archetype's whole.
 *   <li>Else the archetype it names: the repository's own copy, {@code
 *       .config/qits/release-archetypes/<name>.yml} at the fold, when it has one.
 *   <li>Else qits-ci's packaged recipe, read from qits-ci's repository on the git host ({@code
 *       qits.maintenance.automations.packaged-recipes.*}) at {@code main}, which is what qits-ci
 *       last released. For qits-ci's own repository it is read at the fold, as qits-ci reads it.
 * </ol>
 *
 * <p><b>Every doubt is the empty answer</b>: a file that is absent, unreadable or not the expected
 * shape means "QA checks nothing", and the kind keeps its pre-run. A pre-run that was not needed
 * costs minutes; a skipped one that was needed ships stale output.
 */
@ApplicationScoped
public class QaChecks {

  private static final Logger LOG = Logger.getLogger(QaChecks.class);

  static final String RELEASE_YML = ".config/qits/release.yml";

  static final String OWN_RECIPES = ".config/qits/release-archetypes/";

  /** Where qits-ci keeps its packaged recipes in its own repository. */
  static final String PACKAGED_RECIPES = "ci/src/main/resources/release-archetypes/";

  static final String PACKAGED_REF = "main";

  /** qits-ci's rule for an archetype name, so no other name becomes a path. */
  private static final String ARCHETYPE_NAME = "[a-z0-9][a-z0-9-]{0,63}";

  @Inject GitHostReader gitHost;

  @Inject MaintenanceConfig config;

  /** The kinds the subject's QA checks at its fold; empty when it checks none or in doubt. */
  public Set<String> kinds(AutomationSubject subject) {
    String repository = subject.repository().name;
    Map<?, ?> slots = yaml(subject.fold().file(RELEASE_YML), repository, RELEASE_YML);
    if (slots == null) {
      return Set.of();
    }
    if (slots.containsKey("release-request")) {
      return checked(slots.get("release-request"));
    }
    if (!(slots.get("archetype") instanceof String name) || !name.matches(ARCHETYPE_NAME)) {
      return Set.of();
    }
    String own = OWN_RECIPES + name + ".yml";
    FileLookup copy = subject.fold().file(own);
    if (copy.status() == FileLookup.Status.FOUND) {
      Map<?, ?> recipe = yaml(copy, repository, own);
      return recipe == null ? Set.of() : checked(recipe.get("release-request"));
    }
    if (copy.status() != FileLookup.Status.ABSENT) {
      LOG.debugf("%s: %s could not be read (%s); its QA counts as checking nothing", repository,
          own, copy.status());
      return Set.of();
    }
    String packaged = PACKAGED_RECIPES + name + ".yml";
    String recipes = config.packagedRecipesRepository();
    FileLookup file =
        repository.equals(recipes)
            ? subject.fold().file(packaged)
            : gitHost.blob(config.packagedRecipesProject(), recipes, PACKAGED_REF, packaged);
    Map<?, ?> recipe = yaml(file, recipes, packaged);
    return recipe == null ? Set.of() : checked(recipe.get("release-request"));
  }

  /** The {@code check-outputs:} names of every step in a slot. */
  private static Set<String> checked(Object slot) {
    Set<String> kinds = new LinkedHashSet<>();
    if (slot instanceof List<?> steps) {
      for (Object step : steps) {
        if (step instanceof Map<?, ?> fields && fields.get("check-outputs") instanceof List<?> names) {
          for (Object kind : names) {
            if (kind instanceof String text && !text.isBlank()) {
              kinds.add(text.trim());
            }
          }
        }
      }
    }
    return kinds;
  }

  /** A file as a YAML mapping, or null when it is absent, unreadable or not a mapping. */
  private static Map<?, ?> yaml(FileLookup file, String repository, String path) {
    if (file.status() != FileLookup.Status.FOUND) {
      if (file.status() != FileLookup.Status.ABSENT) {
        LOG.debugf("%s: %s could not be read (%s); its QA counts as checking nothing", repository,
            path, file.status());
      }
      return null;
    }
    try {
      LoaderOptions options = new LoaderOptions();
      options.setAllowDuplicateKeys(false);
      Object document = new Yaml(new SafeConstructor(options)).load(file.content());
      return document instanceof Map<?, ?> map ? map : null;
    } catch (RuntimeException e) {
      LOG.debugf("%s: %s is not valid yaml; its QA counts as checking nothing", repository, path);
      return null;
    }
  }
}
