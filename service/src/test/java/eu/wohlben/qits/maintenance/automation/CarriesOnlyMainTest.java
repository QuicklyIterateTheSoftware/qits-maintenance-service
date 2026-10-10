package eu.wohlben.qits.maintenance.automation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.bump.ReleaseRequestClient.ReleaseState;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Which requests maintenance may withdraw (qits-1166): only one whose sources, as qits-projects
 * reports them, are main and maintenance's own automation branches.
 */
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
}
