package eu.wohlben.qits.maintenance.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * <b>{@code dependency-bump}'s static declarations</b>, with no Quarkus: none of them reads an
 * injected bean, so a bare instance answers them.
 */
class DependencyBumpDeclarationsTest {

  /**
   * An own-branch kind whose plan IS its change list (qits-1766): the re-run door honours a FRESH
   * plan instead of sending qits-ci a run with no changes.
   */
  @Test
  void anOwnBranchKindWhosePlanIsTheChangeList() {
    DependencyBumpAutomation kind = new DependencyBumpAutomation();

    assertEquals(Target.OWN_BRANCH, kind.target());
    assertTrue(kind.planIsTheChangeList());
  }
}
