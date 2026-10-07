package eu.wohlben.qits.maintenance.bump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.automation.EstatePinsAutomation;
import eu.wohlben.qits.maintenance.model.BumpMode;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.pending.Change;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The two decisions a targeted bump makes before anything leaves this process: <b>is this a payload
 * the step would accept</b>, and <b>which reading of the ending applies</b>.
 *
 * <p>Both are cheap to get wrong in a way no round-trip test would notice. A branch the step refuses
 * is a red run and a step log somebody has to go and read; a mode column misread is a workspace
 * branch treated as this service's own.
 */
class TargetedPayloadTest {

  private static final List<Change> GITLINK =
      List.of(
          new Change(
              Ecosystem.GITLINK.wireName(),
              "components/qits-ci/qits-ci-service",
              "qits-ci-service",
              "c0ffee11d00d2233445566778899aabbccddeeff",
              "2026.910.180413",
              "gitlink:components/qits-ci/qits-ci-service"));

  /**
   * The sentinel has to survive the same check a real group name does.
   *
   * <p>It is not decoration: it travels as the payload's {@code group}, and the step refuses a group
   * that is not {@code [0-9A-Za-z._-]+} outright — so a sentinel spelled with a slash or a space
   * would make every targeted bump a red run, and the reason would only be visible in a step log.
   */
  @Test
  void theTargetedSentinelIsSomethingTheStepWouldAccept() {
    assertTrue(
        BumpPayload.GROUP.matcher(EstatePinsAutomation.TARGETED_GROUP).matches(),
        EstatePinsAutomation.TARGETED_GROUP + " reaches the step as the payload's group");
  }

  /** A caller's branch is a plain ref like any other, slash and all. */
  @Test
  void aWorkspaceBranchIsAValidTarget() {
    assertEquals(
        List.of(),
        BumpPayload.problems(EstatePinsAutomation.TARGETED_GROUP, "workspace/ws-7", "main", GITLINK));
    assertEquals(
        List.of(),
        BumpPayload.problems(
            EstatePinsAutomation.TARGETED_GROUP, "release/2026.910.180413", "main", GITLINK));
  }

  /**
   * And the refusals are the same refusals. Every value here reaches a git ref, a shell and a path
   * in somebody else's repository, and the branch is the one a caller supplies verbatim.
   */
  @Test
  void anImplausibleBranchOrPathIsRefusedOnThisSide() {
    assertFalse(
        BumpPayload.problems(EstatePinsAutomation.TARGETED_GROUP, "--force", "main", GITLINK).isEmpty(),
        "a leading dash is an argument, not a branch");
    assertFalse(
        BumpPayload.problems(EstatePinsAutomation.TARGETED_GROUP, "ws 7", "main", GITLINK).isEmpty());
    assertFalse(
        BumpPayload.problems(
                EstatePinsAutomation.TARGETED_GROUP,
                "workspace/ws-7",
                "main",
                List.of(
                    new Change(
                        Ecosystem.GITLINK.wireName(),
                        "../elsewhere",
                        "qits-ci-service",
                        "abc",
                        "2026.910.180413",
                        "gitlink:../elsewhere")))
            .isEmpty(),
        "a manifest path that leaves the tree is refused whichever mode asked for it");
  }

  /**
   * A row written before the mode column existed is a GROUP bump, because there was no other kind —
   * and a word this build does not know falls back the same way, to the conservative reading that
   * compares heads and refuses to release a branch it did not push.
   */
  @Test
  void anAbsentOrUnknownModeReadsAsTheGroupPath() {
    assertEquals(BumpMode.GROUP, BumpMode.of(null));
    assertEquals(BumpMode.GROUP, BumpMode.of(""));
    assertEquals(BumpMode.GROUP, BumpMode.of("SOMETHING_LATER"));
    assertEquals(BumpMode.TARGETED, BumpMode.of("TARGETED"));
    assertTrue(BumpMode.GROUP.ownsTheBranch());
    assertFalse(BumpMode.TARGETED.ownsTheBranch(), "which is the whole of the difference");
  }
}
