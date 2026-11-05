package eu.wohlben.qits.maintenance.latest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.bootstrap.graph.Ecosystem;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Three ecosystems, three orders, and the rule about which version is offered. */
class VersionOrderTest {

  @Test
  void mavenCalverOrdersByItsSegments() {
    assertTrue(VersionOrder.newer(Ecosystem.MAVEN, "2026.813.161828", "2026.821.1"));
    assertFalse(VersionOrder.newer(Ecosystem.MAVEN, "2026.821.1", "2026.813.161828"));
    assertFalse(VersionOrder.newer(Ecosystem.MAVEN, "2026.821.1", "2026.821.1"));
  }

  @Test
  void aMavenSnapshotIsAPrerelease() {
    assertTrue(VersionOrder.prerelease(Ecosystem.MAVEN, "1.2.3-SNAPSHOT"));
    assertTrue(VersionOrder.prerelease(Ecosystem.MAVEN, "3.35.0.CR1"));
    assertFalse(VersionOrder.prerelease(Ecosystem.MAVEN, "3.34.6"));
  }

  @Test
  void npmRanksAPrereleaseBelowTheSameNumbersWithoutOne() {
    assertTrue(VersionOrder.newer(Ecosystem.NPM, "21.0.0-rc.1", "21.0.0"));
    assertFalse(VersionOrder.newer(Ecosystem.NPM, "21.0.0", "21.0.0-rc.1"));
  }

  @Test
  void npmComparesPrereleaseIdentifiersPartByPart() {
    assertTrue(VersionOrder.newer(Ecosystem.NPM, "1.0.0-alpha", "1.0.0-alpha.1"));
    assertTrue(VersionOrder.newer(Ecosystem.NPM, "1.0.0-alpha.1", "1.0.0-beta"));
    assertTrue(VersionOrder.newer(Ecosystem.NPM, "1.0.0-beta.2", "1.0.0-beta.11"));
  }

  @Test
  void npmComparesNumbersNumericallyRatherThanAsText() {
    assertTrue(VersionOrder.newer(Ecosystem.NPM, "1.0.9", "1.0.10"));
    assertTrue(VersionOrder.newer(Ecosystem.NPM, "1.9.0", "1.10.0"));
  }

  @Test
  void anOciTagThatIsNotAVersionIsNotRanked() {
    // A tag has to START with digits to be a version here. `latest` is a moving reference and
    // `jdk-25` is an upstream's naming, which v1 does not order at all — only qits/* images are
    // looked up, and those are calver.
    assertFalse(VersionOrder.readable(Ecosystem.DOCKER, "latest"));
    assertFalse(VersionOrder.readable(Ecosystem.DOCKER, "main"));
    assertFalse(VersionOrder.readable(Ecosystem.DOCKER, "jdk-25"));
    assertTrue(VersionOrder.readable(Ecosystem.DOCKER, "2026.821.110104"));
    assertTrue(VersionOrder.readable(Ecosystem.DOCKER, "1.2.3-alpine"));
  }

  @Test
  void aLeadingVIsATagConventionAndNotPartOfTheVersion() {
    assertFalse(VersionOrder.newer(Ecosystem.DOCKER, "1.2.3", "v1.2.3"));
    assertTrue(VersionOrder.newer(Ecosystem.DOCKER, "v1.2.3", "1.2.4"));
  }

  @Test
  void theHighestIsTheHighestRELEASE() {
    // A release candidate in this column would, by the pending rule, be offered to nobody with a
    // released pin — and three stable upgrades would sit behind it unreported.
    assertEquals(
        java.util.Optional.of("3.34.6"),
        VersionOrder.highest(Ecosystem.MAVEN, List.of("3.34.5", "3.34.6", "3.35.0.CR1")));
  }

  @Test
  void aPrereleaseIsTheAnswerOnlyWhenThereIsNoReleaseAtAll() {
    assertEquals(
        java.util.Optional.of("1.0.0-rc.2"),
        VersionOrder.highest(Ecosystem.NPM, List.of("1.0.0-rc.1", "1.0.0-rc.2")));
  }

  @Test
  void nothingRankableIsNoAnswerRatherThanAGuess() {
    assertTrue(VersionOrder.highest(Ecosystem.DOCKER, List.of("latest", "main")).isEmpty());
  }
}
