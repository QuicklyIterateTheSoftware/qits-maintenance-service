package eu.wohlben.qits.maintenance.bus;

import static eu.wohlben.qits.maintenance.bus.ForeignEventContractTest.frame;
import static eu.wohlben.qits.maintenance.bus.ForeignEventContractTest.releaseRequestStateChangedPayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.automation.StrandedBumpReoffer;
import eu.wohlben.qits.maintenance.entity.MtBump;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The release listener's decision, in isolation from the bus and the database (qits-2290): which
 * frames reach {@code StrandedBumpReoffer.releasedAt}, and with what. What that marks is {@code
 * UpstreamReplanTest}'s, against the real store and the dispatcher.
 */
class ReleaseReleasedListenerTest {

  private static final String REPO_ID = "daf73ae4-1c9a-4f8e-9a51-0b0d0e0f1234";

  /** Records each release it was told about. */
  private static final class RecordingReoffer extends StrandedBumpReoffer {

    record Asked(String requestId, String requestName, String mergedSha) {}

    final List<Asked> asked = new ArrayList<>();

    RuntimeException failWith;

    @Override
    public List<MtBump> releasedAt(String requestId, String requestName, String mergedSha) {
      if (failWith != null) {
        throw failWith;
      }
      asked.add(new Asked(requestId, requestName, mergedSha));
      return List.of();
    }
  }

  private ReleaseReleasedListener listener;
  private RecordingReoffer reoffers;

  @BeforeEach
  void setUp() {
    reoffers = new RecordingReoffer();
    listener = new ReleaseReleasedListener();
    listener.reoffers = reoffers;
  }

  @Test
  void itSubscribesToTheOneSignatureUnderItsOwnNewStorageKey() {
    assertEquals(Set.of("ReleaseRequestStateChanged"), listener.signatures());
    assertEquals("maintenance-release-released", listener.consumerId());
  }

  @Test
  void aReleaseIsLookedUpByTheRequestIdAndTheFoldItReleased() {
    listener.onFrame(
        frame(
            "ReleaseRequestStateChanged",
            releaseRequestStateChangedPayload(REPO_ID, "RELEASED", "2026.901.1")));

    assertEquals(
        List.of(new RecordingReoffer.Asked("rr-uuid-1", "qits-eventstream-javalib-rr-7", "abc1234")),
        reoffers.asked);
  }

  /** Every other state is ordinary traffic and settles without a lookup — FINALIZED included. */
  @Test
  void aStateOtherThanReleasedLooksNothingUp() {
    for (String state :
        List.of(
            "PENDING", "READY", "FINALIZED", "REJECTED", "FAILED", "CONFLICTED", "WITHDRAWN",
            "OBSOLETE")) {
      listener.onFrame(
          frame(
              "ReleaseRequestStateChanged",
              releaseRequestStateChangedPayload(REPO_ID, state, "2026.901.1")));
    }

    assertTrue(reoffers.asked.isEmpty());
  }

  @Test
  void aReleaseNamingNoRequestOrNoFoldIsPoisonAndIsSettled() {
    listener.onFrame(
        frame(
            "ReleaseRequestStateChanged",
            "{\"releaseRequestId\":\"  \",\"state\":\"RELEASED\",\"mergedSha\":\"abc1234\"}"));
    listener.onFrame(
        frame(
            "ReleaseRequestStateChanged",
            "{\"releaseRequestId\":\"rr-uuid-1\",\"state\":\"RELEASED\"}"));
    listener.onFrame(frame("ReleaseRequestStateChanged", "not json at all"));

    assertTrue(reoffers.asked.isEmpty());
  }

  @Test
  void aStoreThatWillNotAnswerIsLeftToThrowSoTheReleaseStaysOwed() {
    reoffers.failWith = new IllegalStateException("the database is not there");

    assertThrows(
        IllegalStateException.class,
        () ->
            listener.onFrame(
                frame(
                    "ReleaseRequestStateChanged",
                    releaseRequestStateChangedPayload(REPO_ID, "RELEASED", "2026.901.1"))));
  }
}
