package eu.wohlben.qits.maintenance.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.bootstrap.graph.Ecosystem;
import eu.wohlben.qits.bootstrap.graph.manifest.ParsedPin;
import eu.wohlben.qits.maintenance.model.PinKind;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The name rule that decides which registry answers — and the one ecosystem that has no name rule
 * at all.
 *
 * <p>No Quarkus: the three lists are the whole of the policy, so a configured instance is three
 * assignments.
 */
class MaintenanceConfigTest {

  private static MaintenanceConfig config() {
    MaintenanceConfig config = new MaintenanceConfig();
    config.internalMavenGroups = List.of("eu.wohlben.qits");
    config.internalNpmScopes = List.of("@qits");
    config.internalImagePrefixes = List.of("qits/");
    return config;
  }

  @Test
  void theThreeNameRulesSplitInternalFromExternal() {
    MaintenanceConfig config = config();

    assertEquals(PinKind.INTERNAL, config.kindOf(Ecosystem.MAVEN, "eu.wohlben.qits:qits-eventstream"));
    assertEquals(PinKind.EXTERNAL, config.kindOf(Ecosystem.MAVEN, "io.quarkus:quarkus-core"));
    assertEquals(PinKind.INTERNAL, config.kindOf(Ecosystem.NPM, "@qits/ui-components"));
    assertEquals(PinKind.EXTERNAL, config.kindOf(Ecosystem.NPM, "@angular/core"));
    assertEquals(PinKind.INTERNAL, config.kindOf(Ecosystem.DOCKER, "qits/build-images/node-base"));
    assertEquals(PinKind.EXTERNAL, config.kindOf(Ecosystem.DOCKER, "eclipse-temurin"));
  }

  /**
   * A GITLINK IS INTERNAL BY CONSTRUCTION, and there is no key to get it wrong with. A submodule is
   * a repository on this platform's own git host — nothing else can be one — so classifying one
   * EXTERNAL would send it to a maven mirror that has never heard of a git repository.
   */
  @Test
  void aGitlinkIsInternalWhateverItIsNamed() {
    MaintenanceConfig config = config();

    assertEquals(PinKind.INTERNAL, config.kindOf(Ecosystem.GITLINK, "qits-artifacts-frontend"));
    assertEquals(PinKind.INTERNAL, config.kindOf(Ecosystem.GITLINK, "something-nobody-configured"));
    assertEquals(
        PinKind.INTERNAL,
        config.kindOf(
            new ParsedPin(
                Ecosystem.GITLINK,
                "service/src/main/webui",
                "qits-artifacts-frontend",
                "aa11bb22cc33dd44ee55ff6677889900aabbccdd",
                null,
                "gitlink:service/src/main/webui",
                false)));
  }
}
