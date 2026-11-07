package eu.wohlben.qits.maintenance.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code .config/qits/release.yml} as this service reads it (qits-1952): the keys of {@code
 * contracts.pacts}, and nothing else — and never an error, since an edge only ever adds an offer.
 */
class ReleaseConfigTest {

  /** The estate's own spelling, with every sibling key qits-ci keeps beside it. */
  @Test
  void theProvidersAreTheKeysOfContractsPacts() {
    ReleaseConfig.Parsed parsed =
        ReleaseConfig.parse(
            """
            version: 1
            publish:
              maven: { from: ., pom: pom.xml }
            contracts:
              application: qits-artifacts
              golden-masters: { from: golden-masters/, packages: [maven, npm] }
              pacts:
                qits-projects-service: { packages: [maven] }
                qits-edge-service: { packages: [maven] }
                qits-odd-one: ~
                qits-list-shaped: [maven]
            """);

    assertNull(parsed.problem());
    assertEquals(
        List.of("qits-edge-service", "qits-list-shaped", "qits-odd-one", "qits-projects-service"),
        parsed.providers(),
        "every key, any value shape, sorted");
  }

  @Test
  void aMissingFileDeclaresNothing() {
    assertEquals(List.of(), ReleaseConfig.parse(null).providers());
    assertNull(ReleaseConfig.parse(null).problem());
    assertEquals(List.of(), ReleaseConfig.parse("").providers());
    assertEquals(ReleaseConfig.none(), ReleaseConfig.parse("   \n"));
  }

  @Test
  void malformedYamlDeclaresNothingAndSaysWhy() {
    ReleaseConfig.Parsed parsed = ReleaseConfig.parse("contracts: {pacts: [unclosed\n");
    assertEquals(List.of(), parsed.providers());
    assertNotNull(parsed.problem());
    assertTrue(parsed.problem().contains(ReleaseConfig.PATH), parsed.problem());
  }

  @Test
  void noContractsOrNoPactsDeclaresNothing() {
    assertEquals(
        ReleaseConfig.none(), ReleaseConfig.parse("version: 1\npublish: { maven: {} }\n"));
    assertEquals(
        ReleaseConfig.none(), ReleaseConfig.parse("contracts:\n  application: qits-ci\n"));
  }

  @Test
  void aSectionOfTheWrongShapeDeclaresNothing() {
    assertTrue(ReleaseConfig.parse("- a\n- b\n").providers().isEmpty());
    assertNotNull(ReleaseConfig.parse("contracts: [a]\n").problem());
    ReleaseConfig.Parsed listed = ReleaseConfig.parse("contracts:\n  pacts: [qits-ci-service]\n");
    assertEquals(List.of(), listed.providers());
    assertNotNull(listed.problem());
  }
}
