package eu.wohlben.qits.maintenance.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.bootstrap.graph.Ecosystem;
import eu.wohlben.qits.maintenance.adoption.ReleaseLedger;
import eu.wohlben.qits.maintenance.latest.GitlinkSha;
import eu.wohlben.qits.maintenance.model.GroupSource;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The releases that happened before the ledger existed.</b>
 *
 * <p>The backfill's whole input is {@code mt_latest}'s GITLINK rows, because they are the only
 * place on this platform where "this repository released this version, at this commit" is written
 * down — the {@code SCMRelease} listener has been filling them in since long before there was a
 * release ledger to fill in beside them.
 *
 * <p>What is asserted here is the four decisions it makes per row and nothing below them: reading a
 * tag is {@code ReleaseLedgerTest}'s subject, and the ledger is a recording stand-in.
 *
 * <p><b>Assertions are per repository rather than over totals</b>, because this module has no
 * {@code InventoryReset} and the backfill deliberately walks every gitlink row in the store — which
 * is exactly what it does in a deployment.
 */
@QuarkusTest
class ReleaseLedgerBackfillTest {

  private static final Instant CHECKED = Instant.parse("2026-04-01T10:00:00Z");
  private static final String SHA = "0011223344556677889900aabbccddeeff001122";

  @Inject MaintenanceStore store;

  /** A ledger that records the ask instead of reading a tree, and can refuse one repository. */
  private static final class RecordingLedger extends ReleaseLedger {

    record Recorded(String project, String repository, String version, String sha, Instant when) {}

    final List<Recorded> recorded = new ArrayList<>();
    String failFor;

    @Override
    public boolean record(
        String project, String repository, String version, String sha, Instant occurredAt) {
      if (repository.equals(failFor)) {
        throw new IllegalStateException("the git host is not there");
      }
      recorded.add(new Recorded(project, repository, version, sha, occurredAt));
      return true;
    }

    List<Recorded> of(String repository) {
      return recorded.stream().filter(row -> row.repository().equals(repository)).toList();
    }
  }

  private RecordingLedger ledger;
  private ReleaseLedgerBackfill backfill;
  private String run;

  @BeforeEach
  void aRunOfItsOwn() {
    run = "-" + UUID.randomUUID().toString().substring(0, 8);
    ledger = new RecordingLedger();
    backfill = new ReleaseLedgerBackfill();
    backfill.store = store;
    backfill.ledger = ledger;
  }

  /** A repository the inventory holds, so a project can be read off it. */
  private void scanned(String repository) {
    store.replaceInventory(
        repository,
        "qits",
        null,
        null,
        "main",
        RepositoryStatus.OK,
        "sha",
        null,
        List.of(),
        List.of(),
        GroupSource.DEFAULT,
        candidate -> PinKind.INTERNAL,
        Instant.now());
  }

  /** A gitlink latest, as the release listener writes one: a version and the tag's commit. */
  private void releasedGitlink(String repository, String version, String sourceUrl) {
    store.recordLatestIfNewer(Ecosystem.GITLINK, repository, version, sourceUrl, CHECKED);
  }

  /**
   * <b>Only what is missing, and the ledger row's moment is the latest row's own.</b> A backfill
   * has no frame to take a publisher's moment from, and {@code checked_at} is the closest honest
   * stamp there is for a release that was announced before this table existed.
   */
  @Test
  void itRecordsOnlyTheReleasesTheLedgerDoesNotAlreadyHold() {
    String missing = "qits-backfill-missing" + run;
    String already = "qits-backfill-already" + run;

    scanned(missing);
    releasedGitlink(missing, "2026.906.1", GitlinkSha.of(SHA));
    scanned(already);
    releasedGitlink(already, "2026.906.2", GitlinkSha.of(SHA));
    store.recordRelease(already, "2026.906.2", SHA, CHECKED, List.of());

    backfill.run();

    assertEquals(1, ledger.of(missing).size());
    RecordingLedger.Recorded recorded = ledger.of(missing).get(0);
    assertEquals("qits", recorded.project(), "the project comes off the inventory row");
    assertEquals("2026.906.1", recorded.version());
    assertEquals(SHA, recorded.sha(), "the commit the gitlink latest already recorded");
    assertEquals(CHECKED, recorded.when());

    assertTrue(
        ledger.of(already).isEmpty(), "a release the ledger holds is not read a second time");
  }

  /**
   * <b>Two skips, and neither is a failure.</b> A latest row with no commit in {@code source_url}
   * names no tree to read, and a gitlink whose submodule name is not a catalog name has no
   * inventory row to take a PROJECT from — which is the ordinary state of an estate whose gitlinks
   * outnumber its catalog.
   */
  @Test
  void aRowWithNoShaAndOneWithNoRepositoryAreBothSkipped() {
    String noSha = "qits-backfill-nosha" + run;
    String noRow = "qits-backfill-norow" + run;

    scanned(noSha);
    // A poll's provenance rather than the release listener's: a url, not a commit.
    releasedGitlink(noSha, "1.0.0", "https://example.invalid/whatever");
    // No scanned(...) at all: a gitlink name this inventory has no repository row for.
    releasedGitlink(noRow, "1.0.0", GitlinkSha.of(SHA));

    backfill.run();

    assertTrue(ledger.of(noSha).isEmpty());
    assertTrue(ledger.of(noRow).isEmpty());
    assertFalse(store.releaseRecorded(noSha, "1.0.0"));
    assertFalse(store.releaseRecorded(noRow, "1.0.0"));
  }

  /**
   * <b>A failure is one repository's, never the run's.</b> One unreachable git host costs one
   * ledger row and the next boot asks again; the rest of the catalog is filled in regardless.
   */
  @Test
  void oneRepositoryThatCannotBeReadDoesNotStopTheRest() {
    String dark = "qits-backfill-dark" + run;
    String fine = "qits-backfill-fine" + run;

    scanned(dark);
    releasedGitlink(dark, "1.0.0", GitlinkSha.of(SHA));
    scanned(fine);
    releasedGitlink(fine, "1.0.0", GitlinkSha.of(SHA));
    ledger.failFor = dark;

    backfill.run();

    assertTrue(ledger.of(dark).isEmpty());
    assertEquals(1, ledger.of(fine).size(), "the loop carried on past the one that threw");
  }
}
