package eu.wohlben.qits.maintenance.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * This repository's own {@code .config/qits/configuration.yml}.
 *
 * <p>This is not the parser. qits-configuration's {@code DeclarationParser} is, and it was run
 * against the file when it was written. This test holds only what a hand edit can break without
 * anyone seeing it until a release is refused or a needed entry shows as orphaned.
 */
class OwnDeclarationTest {

  private static final String PATH = ".config/qits/configuration.yml";

  /** {@code ConfigurationKeys.ENV_KEY} and {@code INDEXED_KEY}, copied: the store's key grammar. */
  private static final Pattern KEY =
      Pattern.compile(
          "^env\\.[A-Za-z_][A-Za-z0-9_]*$|^(mounts|publishes|groups|aliases)\\[[0-9]{1,4}]$");

  /** The file is found by walking up from the module directory surefire starts in. */
  private static Path declaration() {
    Path at = Path.of("").toAbsolutePath();
    for (int up = 0; up < 4 && at != null; up++, at = at.getParent()) {
      Path candidate = at.resolve(PATH);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new AssertionError("no " + PATH + " above " + Path.of("").toAbsolutePath());
  }

  /** Loaded safely, with duplicate keys refused, as the store refuses them. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> keys() throws IOException {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    Object document =
        new Yaml(new SafeConstructor(options)).load(Files.readString(declaration()));
    Map<String, Object> root = assertInstanceOf(Map.class, document);
    assertEquals(Set.of("keys"), root.keySet(), "the top level holds `keys` and nothing else");
    return assertInstanceOf(Map.class, root.get("keys"));
  }

  @Test
  void everyKeyIsSpelledInTheStoresGrammar() throws IOException {
    Map<String, Object> keys = keys();

    assertFalse(keys.isEmpty());
    for (String key : keys.keySet()) {
      assertTrue(KEY.matcher(key).matches(), "not a store key: " + key);
    }
  }

  /**
   * NO DEFAULT, AND ONLY THE THREE LITERAL TYPES — for now. A default would sit under the stored
   * entries and never be seen; a serviceAddress would make the stored address an ignored row. This
   * file only lists keys. The change that adds a default or a rendered type changes this test too.
   */
  @Test
  void everyKeyIsATypedNameWithNoDefault() throws IOException {
    for (Map.Entry<String, Object> entry : keys().entrySet()) {
      Map<?, ?> shape = assertInstanceOf(Map.class, entry.getValue(), entry.getKey());
      assertTrue(
          Set.of("type", "description").containsAll(shape.keySet()),
          entry.getKey() + " carries " + shape.keySet());
      assertTrue(
          Set.of("string", "boolean", "number").contains(shape.get("type")),
          entry.getKey() + " is typed " + shape.get("type"));
    }
  }

  /**
   * THE `qits` CLIENT HAS NO FALLBACK ANY MORE: its id, secret and idp address read
   * {@code QITS_RESOURCE_IDP_*} alone, injected by the deployer's own {@code idp:client} resource,
   * and the deployer never stores those in qits-configuration. The OLD `projects` client's extras,
   * its audience key and the githost and ci clients' keys are read by nothing, so every
   * {@code QUARKUS_OIDC_CLIENT_*} name must stay undeclared and show as orphaned.
   */
  @Test
  void noQuarkusOidcClientKeyIsDeclaredAnyMore() throws IOException {
    Set<String> declared =
        keys().keySet().stream()
            .filter(key -> key.startsWith("env.QUARKUS_OIDC_CLIENT_"))
            .collect(Collectors.toSet());

    assertEquals(Set.of(), declared);
  }
}
