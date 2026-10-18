package eu.wohlben.qits.maintenance.automation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.bump.ReleaseRequestClient;
import eu.wohlben.qits.maintenance.bump.ReleaseRequestClient.ReleaseState;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Which requests maintenance may withdraw (qits-1166): only one whose sources, as qits-projects
 * reports them, are main and maintenance's own automation branches.
 *
 * <p>{@code @QuarkusTest} only for {@link #aReleasedTagInQitsProjectsOwnShapeIsReadAndCarriesMore},
 * which pins the PARSE through the real {@link ReleaseRequestClient} and {@link FakePeers} rather
 * than a {@link ReleaseState} built by hand; every other test here needs neither CDI nor a peer.
 */
@QuarkusTest
class CarriesOnlyMainTest {

  private static final MtRepository REPOSITORY = new MtRepository();

  private static ReleaseState sources(String... names) {
    return new ReleaseState(
        "PENDING", null, null, "abc", List.of(), false, null, null, List.of(names));
  }

  @Test
  void mainAloneAndOwnAutomationBranchesCarryOnlyMain() {
    assertTrue(AutomationService.carriesOnlyMain(REPOSITORY, sources("main")));
    assertTrue(
        AutomationService.carriesOnlyMain(
            REPOSITORY, sources("main", "maintenance/automations/dependency-bump/r1")));
  }

  @Test
  void aTagOrAPersonsBranchCarriesMore() {
    assertFalse(AutomationService.carriesOnlyMain(REPOSITORY, sources("main", "2026.1010.172542")));
    assertFalse(AutomationService.carriesOnlyMain(REPOSITORY, sources("main", "external/work")));
  }

  @Test
  void anUnreadableOrEmptyAnswerWithdrawsNothing() {
    assertFalse(AutomationService.carriesOnlyMain(REPOSITORY, sources()));
    assertFalse(
        AutomationService.carriesOnlyMain(REPOSITORY, new ReleaseState(null, null, "down")));
  }

  @Inject ReleaseRequestClient releases;

  @Inject FakePeers peers;

  /**
   * <b>Pins the PARSE, not just the predicate</b> (qits-1166). qits-projects reports a pending
   * released tag as an implicit source of kind {@code RELEASED_TAG}, {@code ref} and {@code
   * implicit} fields included — not {@code TAG}, and not the {@code BRANCH} shape every other
   * source in this suite is built from. Read through {@link ReleaseRequestClient#state} rather
   * than constructed by hand, that answer's {@link ReleaseState#sources()} still carries the tag's
   * name, and {@link AutomationService#carriesOnlyMain} still refuses it.
   */
  @Test
  void aReleasedTagInQitsProjectsOwnShapeIsReadAndCarriesMore() {
    String repoId = "r1";
    String requestId = "9f1c2d3e-aa00-4b11-9c22-dd33ee44ff55";
    String tag = "2026.1018.73956";
    peers.reset();
    peers.answer(
        PeerTarget.PROJECTS,
        ReleaseRequestClient.REQUESTS_PATH_PREFIX + repoId
            + ReleaseRequestClient.REQUESTS_PATH_SUFFIX + "/" + requestId,
        FakePeers.Scripted.ok(
            "{\"request\":{\"id\":\"" + requestId + "\",\"state\":\"PENDING\",\"mergedSha\":\"abc\","
                + "\"sources\":["
                + "{\"kind\":\"BRANCH\",\"name\":\"main\"},"
                + "{\"kind\":\"BRANCH\",\"name\":\"maintenance/automations/dependency-bump/r1\"},"
                + "{\"kind\":\"RELEASED_TAG\",\"name\":\"" + tag + "\",\"ref\":\"refs/tags/" + tag
                + "\",\"implicit\":true}]}}"));

    ReleaseState state = releases.state(repoId, requestId);

    assertTrue(state.sources().contains(tag), state.sources().toString());
    assertFalse(AutomationService.carriesOnlyMain(REPOSITORY, state));
  }
}
