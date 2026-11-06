package eu.wohlben.qits.maintenance.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.bootstrap.graph.Ecosystem;
import eu.wohlben.qits.maintenance.manifest.ManifestScanner;
import eu.wohlben.qits.maintenance.pending.Change;
import java.util.List;
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

  /**
   * {@code MANIFESTS} names a pnpm-managed root's lock beside the npm/yarn-shaped one (qits-1914):
   * a repository keeps only one of the two, and naming both costs nothing on the one it lacks.
   */
  @Test
  void manifestsNamesBothNpmLockSpellings() {
    assertTrue(
        DependencyBumpAutomation.MANIFESTS.contains(
            ":(glob)**/" + ManifestScanner.PACKAGE_LOCK));
    assertTrue(
        DependencyBumpAutomation.MANIFESTS.contains(":(glob)**/" + ManifestScanner.PNPM_LOCK));
  }

  /**
   * An npm change's commit paths carry BOTH lock spellings beside the manifest, not just {@code
   * package-lock.json} (qits-1914): the qits-ci postlude stages a commit path only when a file
   * sits there, so naming the lock a repository does not keep is harmless.
   */
  @Test
  void anNpmChangeCommitsBothLockSpellingsBesideTheManifest() {
    Change change =
        Change.of(Ecosystem.NPM, "frontend/package.json", "left-pad", "1.0.0", "1.0.1", "line 3");

    List<String> paths = DependencyBumpAutomation.commitPaths(List.of(change));

    assertEquals(
        List.of(
            "frontend/package.json",
            "frontend/" + ManifestScanner.PACKAGE_LOCK,
            "frontend/" + ManifestScanner.PNPM_LOCK),
        paths);
  }
}
