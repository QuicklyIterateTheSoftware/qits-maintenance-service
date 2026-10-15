package eu.wohlben.qits.maintenance.bump;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link ReleaseRequestClient.Listed#verdict()} (qits-1199): a plain REJECTED is not itself a QA
 * verdict, because qits-projects also lands a request there when a pre-run automation fails, before
 * any QA ran — its {@code CI} gate is still PENDING at that point. A CI-rejected request has
 * {@code ciGate} FAILED, and a person's decline comes only after CI passed, {@code ciGate} PASSED,
 * so both of those still count.
 */
class ReleaseRequestClientTest {

  private static ReleaseRequestClient.Listed listed(String state, String ciGate) {
    return new ReleaseRequestClient.Listed("req-1", state, null, ciGate);
  }

  @Test
  void pendingWithAPendingGateIsNotAVerdict() {
    assertFalse(listed("PENDING", "PENDING").verdict());
  }

  @Test
  void anAutomationRejectionWithAPendingGateIsNotAVerdict() {
    assertFalse(listed("REJECTED", "PENDING").verdict());
  }

  @Test
  void aCiRejectionIsAVerdict() {
    assertTrue(listed("REJECTED", "FAILED").verdict());
  }

  @Test
  void aPersonsDeclineAfterCiPassedIsAVerdict() {
    assertTrue(listed("REJECTED", "PASSED").verdict());
  }

  @Test
  void readyWithAPendingGateIsAVerdict() {
    assertTrue(listed("READY", "PENDING").verdict());
  }

  @Test
  void aPassedGateIsAVerdictWhateverTheState() {
    assertTrue(listed("PENDING", "PASSED").verdict());
  }

  /**
   * {@link ReleaseRequestClient.Listed#inFlight()} (qits-1251): RELEASED holds exactly as long as
   * neither the PUBLISH phase nor the DEPLOYMENT gate has FAILED, because a release still going out
   * is what a main-only request opened now would obsolete and re-release with nothing new in it. A
   * FAILED publish or deployment does not hold — such a request stays RELEASED until a person retries
   * it, and a hold would starve bumps for good, so a main-only request overtaking it is the recovery.
   */
  private static ReleaseRequestClient.Listed listedInFlight(
      String state, String publish, String deploymentGate) {
    return new ReleaseRequestClient.Listed("req-1", state, null, null, publish, deploymentGate);
  }

  @Test
  void releasedWithNoPipelineAtAllIsInFlight() {
    assertTrue(listedInFlight("RELEASED", null, null).inFlight());
  }

  @Test
  void releasedWithAPassedOrPendingPublishIsInFlight() {
    assertTrue(listedInFlight("RELEASED", "PASSED", null).inFlight());
    assertTrue(listedInFlight("RELEASED", "PENDING", null).inFlight());
  }

  @Test
  void releasedWithAFailedPublishIsNotInFlight() {
    assertFalse(listedInFlight("RELEASED", "FAILED", null).inFlight());
  }

  @Test
  void releasedWithAFailedDeploymentGateIsNotInFlight() {
    assertFalse(listedInFlight("RELEASED", null, "FAILED").inFlight());
  }

  @Test
  void releasedWithAPassingDeploymentGateIsInFlight() {
    assertTrue(listedInFlight("RELEASED", null, "PASSED").inFlight());
  }

  @Test
  void aNonReleasedStateIsNeverInFlightWhateverThePublishAndTheGateSay() {
    assertFalse(listedInFlight("PENDING", null, null).inFlight());
    assertFalse(listedInFlight("FINALIZED", null, null).inFlight());
    assertFalse(listedInFlight("OBSOLETE", "FAILED", null).inFlight());
    assertFalse(listedInFlight("WITHDRAWN", null, "FAILED").inFlight());
  }
}
