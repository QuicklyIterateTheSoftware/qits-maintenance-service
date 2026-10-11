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
}
