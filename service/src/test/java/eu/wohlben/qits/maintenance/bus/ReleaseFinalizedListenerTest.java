package eu.wohlben.qits.maintenance.bus;

import static eu.wohlben.qits.maintenance.bus.ForeignEventContractTest.frame;
import static eu.wohlben.qits.maintenance.bus.ForeignEventContractTest.releaseRequestStateChangedPayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The finalization listener's decision, in isolation from the bus and the database (qits-1472):
 * which frames reach {@code MaintenanceStore.adoptFinalized}, with what, and what the upstream hook
 * is told afterwards. The join itself is {@code MaintenanceStoreTest}'s and, end to end through
 * both listeners, {@code FinalizedAdoptionTest}'s.
 */
class ReleaseFinalizedListenerTest {

  private static final String REPO_ID = "daf73ae4-1c9a-4f8e-9a51-0b0d0e0f1234";

  /** Records each adoption it was asked for and answers what a test scripted. */
  private static final class RecordingStore extends MaintenanceStore {

    record Asked(String repository, String version, String sourceUrl) {}

    final List<Asked> asked = new ArrayList<>();

    List<Adoption> answer = List.of();

    RuntimeException failWith;

    @Override
    public List<Adoption> adoptFinalized(
        String repository, String version, String sourceUrl, Instant finalizedAt, Instant now) {
      if (failWith != null) {
        throw failWith;
      }
      asked.add(new Asked(repository, version, sourceUrl));
      return answer;
    }
  }

  private ReleaseFinalizedListener listener;
  private RecordingStore store;
  private SoftwareReleaseListenerTest.RecordingUpstream upstream;

  @BeforeEach
  void setUp() {
    store = new RecordingStore();
    upstream = new SoftwareReleaseListenerTest.RecordingUpstream();
    listener = new ReleaseFinalizedListener();
    listener.store = store;
    listener.upstream = upstream;
  }

  @Test
  void itSubscribesToTheOneSignatureUnderItsOwnNewStorageKey() {
    assertEquals(Set.of("ReleaseRequestStateChanged"), listener.signatures());
    assertEquals("maintenance-release-finalized", listener.consumerId());
  }

  @Test
  void aFinalizationAsksTheStoreToAdoptUnderTheRepoIdVerbatim() {
    EventFrame finalized =
        frame(
            "ReleaseRequestStateChanged",
            releaseRequestStateChangedPayload(REPO_ID, "FINALIZED", "2026.901.1"));

    listener.onFrame(finalized);

    assertEquals(
        List.of(new RecordingStore.Asked(REPO_ID, "2026.901.1", "event:" + finalized.id())),
        store.asked,
        "the row id is the join key, never translated to a catalog name");
  }

  /** Only a column that MOVED is a re-plan; an adoption the forward-only rule refused is not. */
  @Test
  void theUpstreamHookIsToldOnlyAboutTheColumnsThatMoved() {
    store.answer =
        List.of(
            new MaintenanceStore.Adoption(Ecosystem.MAVEN, "eu.wohlben.qits:a", "2026.901.1", true),
            new MaintenanceStore.Adoption(Ecosystem.NPM, "@qits/b", "2026.901.1", false));

    listener.onFrame(
        frame(
            "ReleaseRequestStateChanged",
            releaseRequestStateChangedPayload(REPO_ID, "FINALIZED", "2026.901.1")));

    assertEquals(List.of("maven:eu.wohlben.qits:a"), upstream.moved);
  }

  /** Every other state is ordinary traffic and settles without a store call. */
  @Test
  void aStateOtherThanFinalizedAdoptsNothing() {
    for (String state :
        List.of(
            "PENDING", "READY", "RELEASED", "REJECTED", "FAILED", "CONFLICTED", "WITHDRAWN",
            "OBSOLETE")) {
      listener.onFrame(
          frame(
              "ReleaseRequestStateChanged",
              releaseRequestStateChangedPayload(REPO_ID, state, "2026.901.1")));
    }

    assertTrue(store.asked.isEmpty());
    assertTrue(upstream.moved.isEmpty());
  }

  @Test
  void aFinalizationNamingNoRepositoryOrNoVersionIsPoisonAndIsSettled() {
    listener.onFrame(
        frame(
            "ReleaseRequestStateChanged",
            releaseRequestStateChangedPayload("  ", "FINALIZED", "2026.901.1")));
    listener.onFrame(
        frame(
            "ReleaseRequestStateChanged",
            releaseRequestStateChangedPayload(REPO_ID, "FINALIZED", null)));
    listener.onFrame(frame("ReleaseRequestStateChanged", "not json at all"));

    assertTrue(store.asked.isEmpty());
  }

  @Test
  void aStoreThatWillNotAnswerIsLeftToThrowSoTheFinalizationStaysOwed() {
    store.failWith = new IllegalStateException("the database is not there");

    assertThrows(
        IllegalStateException.class,
        () ->
            listener.onFrame(
                frame(
                    "ReleaseRequestStateChanged",
                    releaseRequestStateChangedPayload(REPO_ID, "FINALIZED", "2026.901.1"))));
  }
}
