package eu.wohlben.qits.maintenance.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * The npmjs cache's address is not configuration (qits-472): {@link PeerTarget#NPM_MIRROR} carries
 * no key, and its base is derived from the platform-injected {@code QITS_ENVIRONMENT} alone.
 */
class PeerTargetTest {

  @Test
  void theNpmMirrorIsDerivedFromTheEnvironment() {
    assertEquals(
        "http://prod-qits-platform-mirror:8080/npm/npmjs", PeerTarget.NPM_MIRROR.derivedBase("prod"));
  }

  @Test
  void noEnvironmentMeansDev() {
    // The same default every `${QITS_ENVIRONMENT:dev}` line in the shipped config expands to.
    assertEquals(
        "http://dev-qits-platform-mirror:8080/npm/npmjs", PeerTarget.NPM_MIRROR.derivedBase(null));
    assertEquals(
        "http://dev-qits-platform-mirror:8080/npm/npmjs", PeerTarget.NPM_MIRROR.derivedBase(" "));
  }

  @Test
  void theNpmMirrorHasNoKey() {
    assertNull(PeerTarget.NPM_MIRROR.urlKey());
  }

  @Test
  void aConfiguredTargetIsNotDerived() {
    assertThrows(IllegalStateException.class, () -> PeerTarget.MAVEN_MIRROR.derivedBase("dev"));
  }

  @Test
  void theShippedConfigDeclaresNoNpmMirrorKey() throws Exception {
    // A leftover QITS_MAINTENANCE_MIRROR_NPM_URL must be dead: the key it maps to is read nowhere,
    // and reinstating a default here would quietly make it live again.
    Properties shipped = new Properties();
    try (InputStream in =
        PeerTarget.class.getResourceAsStream("/META-INF/microprofile-config.properties")) {
      shipped.load(in);
    }
    assertFalse(shipped.containsKey("qits.maintenance.mirror.npm-url"));
  }
}
