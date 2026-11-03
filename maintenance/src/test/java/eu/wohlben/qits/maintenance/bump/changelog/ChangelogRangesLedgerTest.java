package eu.wohlben.qits.maintenance.bump.changelog;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link ChangelogRanges}' store-backed ledger, against a real PostgreSQL (qits-1840).
 *
 * <p>The rules themselves are {@link ChangelogRangesTest}'s, over the {@link
 * ChangelogRanges.Sources} seam; this is the one source whose answer depends on how the store
 * spells a repository, which a seam that states the ledger outright cannot see.
 */
@QuarkusTest
class ChangelogRangesLedgerTest {

  @Inject MaintenanceStore store;

  @Inject ChangelogRanges ranges;

  /**
   * <b>A release recorded under the OLD NAME is in the renamed repository's ledger</b>, once — a
   * version recorded under both names is one release, the live name's row answering.
   */
  @Test
  void theLedgerOfARenamedRepositoryIncludesTheReleasesRecordedUnderItsOldName() {
    String catalogId = UUID.randomUUID().toString();
    String suffix = UUID.randomUUID().toString();
    String old = "a-old-" + suffix;
    String current = "b-new-" + suffix;
    Instant at = Instant.parse("2026-11-01T10:00:00Z");
    store.markRepository(
        old, "qits", catalogId, null, RepositoryStatus.ABSENT, "dropped from the catalog", at);
    store.markRepository(current, "qits", catalogId, null, RepositoryStatus.OK, null, at);
    store.recordRelease(old, "2026.1030.1", "sha-before", at.minusSeconds(7200), List.of());
    store.recordRelease(old, "2026.1101.1", "sha-old-row", at.minusSeconds(60), List.of());
    store.recordRelease(current, "2026.1101.1", "sha-new-row", at.minusSeconds(60), List.of());
    store.recordRelease(current, "2026.1102.1", "sha-after", at.plusSeconds(3600), List.of());

    List<ChangelogRanges.Release> ledger = ranges.ledgerOf(current);

    assertEquals(
        List.of("2026.1101.1", "2026.1102.1", "2026.1030.1"),
        ledger.stream().map(ChangelogRanges.Release::version).toList(),
        "the live name's releases first, then the old name's it does not already hold");
    assertEquals(
        "sha-new-row",
        ledger.stream()
            .filter(release -> release.version().equals("2026.1101.1"))
            .findFirst()
            .orElseThrow()
            .sha());
  }

  /** A repository nothing was ever renamed away from reads its own name, as before. */
  @Test
  void theLedgerOfAnUnrenamedRepositoryIsItsOwn() {
    String repository = "plain-" + UUID.randomUUID();
    store.markRepository(
        repository,
        "qits",
        UUID.randomUUID().toString(),
        null,
        RepositoryStatus.OK,
        null,
        Instant.now());
    store.recordRelease(repository, "2026.1101.1", "sha", Instant.now(), List.of());

    assertEquals(
        List.of("2026.1101.1"),
        ranges.ledgerOf(repository).stream().map(ChangelogRanges.Release::version).toList());
  }
}
