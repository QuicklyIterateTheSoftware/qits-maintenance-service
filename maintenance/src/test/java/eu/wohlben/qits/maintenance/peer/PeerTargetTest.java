package eu.wohlben.qits.maintenance.peer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Every {@link PeerTarget} is configuration — {@link PeerTarget#NPM_MIRROR} included, since
 * qits-533 undid the one exception qits-472 carved out — and the npm mirror's default is the public
 * edge.
 */
class PeerTargetTest {

  private static Properties shippedConfig() throws Exception {
    Properties shipped = new Properties();
    try (InputStream in =
        PeerTarget.class.getResourceAsStream("/META-INF/microprofile-config.properties")) {
      shipped.load(in);
    }
    return shipped;
  }

  @Test
  void theNpmMirrorIsReachedThroughThePublicEdge() throws Exception {
    // The raw line, unexpanded: no default for QITS_DOMAIN, so an unset one fails rather than
    // quietly naming a host nobody serves.
    assertEquals(
        "https://mirror.qits.${QITS_DOMAIN}/npm/npmjs",
        shippedConfig().getProperty("qits.maintenance.mirror.npm-url"));
  }

  @Test
  void everyTargetIsConfigured() throws Exception {
    Properties shipped = shippedConfig();
    for (PeerTarget target : PeerTarget.values()) {
      assertNotNull(target.urlKey(), target + " has no url key");
      assertTrue(
          shipped.containsKey(target.urlKey()),
          target + "'s key " + target.urlKey() + " is not in the shipped config");
    }
  }
}
