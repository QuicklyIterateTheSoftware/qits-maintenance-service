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
  void everyTargetIsAPeersEdgeHostAndNoWireAlias() throws Exception {
    // qits-1424 (feature qits-1406): https://<label>.qits.${QITS_DOMAIN}, the label the peer
    // publishes — no wire alias, no port, no environment label, no fallback domain.
    Properties shipped = shippedConfig();
    java.util.regex.Pattern edge =
        java.util.regex.Pattern.compile("https://[a-z]+\\.qits\\.\\$\\{QITS_DOMAIN}(/.*)?");
    for (PeerTarget target : PeerTarget.values()) {
      String raw = shipped.getProperty(target.urlKey());
      assertTrue(edge.matcher(raw).matches(), target + " is not an edge host: " + raw);
    }
    assertEquals(
        "https://registry.qits.${QITS_DOMAIN}/artifacts/maven/maven",
        shipped.getProperty("qits.maintenance.registries.maven-url"));
    assertEquals(
        "https://registry.qits.${QITS_DOMAIN}/artifacts/npm/npm",
        shipped.getProperty("qits.maintenance.registries.npm-url"));
    assertEquals(
        "https://registry.qits.${QITS_DOMAIN}/v2",
        shipped.getProperty("qits.maintenance.registries.oci-url"));
    assertEquals(
        "https://mirror.qits.${QITS_DOMAIN}/artifacts/maven/central",
        shipped.getProperty("qits.maintenance.mirror.maven-url"));
    assertEquals(
        "https://registry.qits.${QITS_DOMAIN}",
        shipped.getProperty("qits.maintenance.targets.artifacts-url"));
    assertEquals(
        "${QITS_RESOURCE_IDP_URL:https://idp.qits.${QITS_DOMAIN}/idp}",
        shipped.getProperty("quarkus.oidc-client.qits.auth-server-url"));
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
