package eu.wohlben.qits.maintenance.api;

import eu.wohlben.qits.maintenance.entity.MtArtifact;
import eu.wohlben.qits.maintenance.entity.MtArtifactComponent;
import eu.wohlben.qits.maintenance.entity.MtArtifactEdge;
import eu.wohlben.qits.maintenance.entity.MtAutomationDecision;
import eu.wohlben.qits.maintenance.entity.MtBranch;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.entity.MtGitlinkPin;
import eu.wohlben.qits.maintenance.entity.MtGitlinkTree;
import eu.wohlben.qits.maintenance.entity.MtGroup;
import eu.wohlben.qits.maintenance.entity.MtLatest;
import eu.wohlben.qits.maintenance.entity.MtPin;
import eu.wohlben.qits.maintenance.entity.MtRelease;
import eu.wohlben.qits.maintenance.entity.MtReleasePin;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.entity.MtSbomCheckRun;
import eu.wohlben.qits.maintenance.entity.MtSbomTicket;
import eu.wohlben.qits.maintenance.entity.MtSbomTicketVersion;
import eu.wohlben.qits.maintenance.entity.MtScan;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/**
 * Empties the store between test methods.
 *
 * <p><b>The suite shares one database across a class</b> — Flyway's {@code clean-at-start} runs per
 * Quarkus start, not per test — and the bump rows are exactly the kind that must not leak: an
 * active bump holds its (repository, group) lock, so the second test of a class would be answered
 * 409 by the first test's row and read as a broken refusal rather than a dirty fixture.
 *
 * <p>A write the previous test's worker thread makes after this ran is a no-op: every store write
 * looks its row up first and returns when it is gone.
 */
@ApplicationScoped
public class InventoryReset {

  @Transactional
  public void clear() {
    // The graph first: mt_artifact_component and mt_artifact_edge are the only rows in this schema
    // with a real foreign key, and it points at mt_artifact. (The release trains' node table held
    // the other one; V8 dropped both of their tables when the feature was retired.)
    // The SBOM check's tables first: versions point at their ticket row, the only other foreign key.
    MtSbomTicketVersion.deleteAll();
    MtSbomTicket.deleteAll();
    MtSbomCheckRun.deleteAll();
    MtArtifactEdge.deleteAll();
    MtArtifactComponent.deleteAll();
    MtArtifact.deleteAll();
    MtBump.deleteAll();
    MtAutomationDecision.deleteAll();
    MtBranch.deleteAll();
    MtScan.deleteAll();
    // The ledger, pins first: mt_release_pin.release_id is a plain uuid rather than a foreign key
    // (see V9), so nothing enforces the order — but writing it the other way round would leave the
    // pair looking like two unrelated tables to whoever reads this next.
    MtReleasePin.deleteAll();
    MtRelease.deleteAll();
    MtPin.deleteAll();
    MtGitlinkPin.deleteAll();
    MtGitlinkTree.deleteAll();
    MtGroup.deleteAll();
    MtLatest.deleteAll();
    MtRepository.deleteAll();
  }

  /**
   * Drops only the latest-version rows, leaving the pins and the groups standing.
   *
   * <p>What that produces is a repository with an inventory and NOTHING PENDING — every pin is at the
   * newest version this service knows of, because it knows of none. It is the one state a fixture
   * cannot reach by scripting a registry differently: "no answer" and "the answer is the pinned
   * version" are two different rows and only this makes the second.
   */
  @Transactional
  public void clearLatest() {
    MtLatest.deleteAll();
  }
}
