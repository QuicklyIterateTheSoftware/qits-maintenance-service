package eu.wohlben.qits.maintenance.idp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * The one named oidc client, {@code qits}, as the shipped configuration resolves it with no {@code
 * QITS_RESOURCE_IDP_*} env set — the "no deployer" arm every clone-alone build and every other test
 * in this repo runs on (epic qits-540 dossier, 'Plan (as of 2026-09-13)', C4).
 */
@QuarkusTest
class QitsOidcClientShippedConfigTest {

  private static String value(String key) {
    Config config = ConfigProvider.getConfig();
    return config.getValue(key, String.class);
  }

  @Test
  void theQitsClientResolvesItsOwnLiteralDefaults() {
    // The tier is DERIVED, not written: QITS_ENVIRONMENT is injected into every container by
    // qits-deployments and is unset under test, so the `:dev` fallback is what resolves here. The
    // assertion is on the resolved string on purpose — a default that stopped deriving would still
    // read `dev-` on this suite only if somebody hardcoded it, and that is the mistake worth
    // catching.
    assertEquals(
        "http://dev-qits-idp:8080/idp", value("quarkus.oidc-client.qits.auth-server-url"));
    assertEquals("dev-qits-maintenance", value("quarkus.oidc-client.qits.client-id"));
    // Empty, not absent — SmallRye reads a configured-empty String as null, so an empty secret reads
    // as an empty Optional rather than as "" itself.
    Optional<String> secret =
        ConfigProvider.getConfig()
            .getOptionalValue("quarkus.oidc-client.qits.credentials.secret", String.class);
    assertTrue(secret.isEmpty());
    // One audience for every peer now, never a peer-scoped one.
    assertEquals("qits-platform", value("quarkus.oidc-client.qits.grant-options.client.audience"));
  }

  @Test
  void theClientStaysDisabledUnderTest() {
    // %test.quarkus.oidc-client.qits.client-enabled=false wins over the shipped `true` — the arm
    // every test in this repo is on, so a suite never dials a real idp.
    assertEquals("false", value("quarkus.oidc-client.qits.client-enabled"));
  }

  @Test
  void theProjectsBlockStaysNeutralised() {
    // Not a stub for a client nobody configures: the container still carries
    // QUARKUS_OIDC_CLIENT_PROJECTS_{CLIENT_ID,CREDENTIALS_SECRET,CLIENT_ENABLED}, one such variable
    // is enough to mint the map key, and these three are what stop an env-enabled client dialling its
    // issuer during runtime init and failing the boot on one that never answers.
    assertEquals("false", value("quarkus.oidc-client.projects.client-enabled"));
    assertEquals("false", value("quarkus.oidc-client.projects.discovery-enabled"));
    assertEquals("token", value("quarkus.oidc-client.projects.token-path"));
  }

  @Test
  void theCiAndGithostBlocksStayDisabledAndInert() {
    // Neither name is minted through by code and no deployment sets a _CLIENT_ENABLED for either —
    // the container's leftover _AUTH_SERVER_URL alone is what mints the map key — and these three
    // values are what stop that env-enabled client dialling its issuer during runtime init and
    // failing the boot once the address it is pointed at stops resolving.
    for (String name : new String[] {"ci", "githost"}) {
      assertEquals("false", value("quarkus.oidc-client." + name + ".client-enabled"), name);
      assertEquals("false", value("quarkus.oidc-client." + name + ".discovery-enabled"), name);
      assertEquals("token", value("quarkus.oidc-client." + name + ".token-path"), name);
    }
  }

  @Test
  void theOtherNamedClientsAreGone() {
    // Nothing mints through them and no deployment sets their extras, so there is no block to
    // configure: the keys resolve to nothing at all rather than to a disabled client.
    for (String name : new String[] {"artifacts", "mirror"}) {
      assertTrue(
          ConfigProvider.getConfig()
              .getOptionalValue("quarkus.oidc-client." + name + ".client-id", String.class)
              .isEmpty(),
          name + " client-id");
    }
  }
}
