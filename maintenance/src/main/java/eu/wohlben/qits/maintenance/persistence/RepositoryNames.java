package eu.wohlben.qits.maintenance.persistence;

import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>THE ONE PLACE A STORED REPOSITORY SPELLING IS TRANSLATED, and it is a read-side belt for rows
 * written before the write side learned to do it.</b>
 *
 * <p>qits-ci's {@code SoftwareRelease} names the repository by qits-projects' ROW ID rather than by
 * the catalog name — measured live 2026-09-02 — so every artifact row written before that fix
 * carries a uuid in a column the whole read side joins on a name. {@code SoftwareReleaseListener}
 * resolves it at the write now; those rows are immutable history and are still there, so both
 * directions are covered here and nowhere else:
 *
 * <ul>
 *   <li>{@link #of} reads a stored value OUT as a name — what {@code DependentDto.repository} and
 *       {@code ArtifactDto.repository} carry, which is the client's link to the repository page, and
 *       what {@code DownstreamResolver} joins a bill of materials' owner on;
 *   <li>{@link #spellings} reads a name IN as every string that repository's rows may hold — what
 *       "who depends on this repository", the detail page's transitives and "what does this
 *       repository publish" are looked up by, which answered nothing at all for a repository whose
 *       releases predate the fix.
 * </ul>
 *
 * <p><b>Shared, not copied</b> (qits-2375). It used to be a private record of {@code
 * ArtifactGraph}, and {@code DownstreamResolver} kept a third, weaker translation of its own that
 * had none of qits-1735's or qits-1840's fixes — so a downstream closure named a renamed repository
 * under its old name beside its new one. Both now build this from the one {@code repositories()}
 * read each answer already makes; it holds no state between answers and reads nothing itself.
 *
 * <p><b>A catalog id two rows share answers with the live one</b> (qits-1735). A rename keeps
 * qits-projects' row id, so the ABSENT old row and the new one carry the same {@code catalog_id}; a
 * plain put let whichever came last in name order win, and an application's producer became a
 * repository nobody scans. The same preference as {@link MaintenanceStore#repositoryName}: a row
 * that is not {@link RepositoryStatus#retired retired} first, then the row scanned most recently,
 * then the lowest name.
 *
 * <p><b>The most recent scan is the tie-break between two LIVE rows</b> (qits-1840): a rename leaves
 * the old row OK until a scan retires it, and in that window "the lowest name" picked {@code
 * qits-platform-access-cli} over {@code qits-platform-access-monorepo}, measured live. The listed
 * name is the one every scan since the rename has written; a row never scanned sorts last.
 *
 * <p><b>And a retired NAME is translated too</b> (qits-1840). The catalog-id arm only ever covered
 * rows written under the id; an artifact written under the OLD NAME — by a listener that resolved
 * the id to the old row while it was still live, or before the rename at all — passed through as
 * the old name, so {@code ArtifactGraph.producers} named a repository nobody scans and the bump's
 * changelog check looked for {@code @changelog/<old name>}. So every retired row whose id answers
 * with a different, live name is recorded here, both ways round: out to that name, in as one of its
 * spellings.
 *
 * <p><b>An unknown value passes through untouched in both directions.</b> A raw uuid with no catalog
 * row behind it is still what a release said, and a repository the catalog does not carry an id for
 * is looked up by its name alone — which is exactly right, because nothing ever wrote anything else
 * for it. A retired name with no live row on its id passes through too: nothing has replaced it.
 */
public final class RepositoryNames {

  /** Catalog id to the name that answers for it — live first, newest scan. */
  private final Map<String, String> nameByCatalogId;

  /** Name to its own catalog id. */
  private final Map<String, String> catalogIdByName;

  /** A retired name to the live name now answering for its catalog id. */
  private final Map<String, String> liveByRetiredName;

  /** Catalog id to every retired name carrying it. */
  private final Map<String, List<String>> retiredByCatalogId;

  private RepositoryNames(
      Map<String, String> nameByCatalogId,
      Map<String, String> catalogIdByName,
      Map<String, String> liveByRetiredName,
      Map<String, List<String>> retiredByCatalogId) {
    this.nameByCatalogId = nameByCatalogId;
    this.catalogIdByName = catalogIdByName;
    this.liveByRetiredName = liveByRetiredName;
    this.retiredByCatalogId = retiredByCatalogId;
  }

  /**
   * The translation over one read of {@code mt_repository}.
   *
   * <p><b>ONE READ per answer, never one per row</b>, and the caller makes it: the catalog is tens
   * of rows, a dependents listing or a closure can be hundreds, and every row of it names one of
   * those few repositories.
   *
   * @param repositories every inventory row, in any order — the preference above is applied here
   */
  public static RepositoryNames from(Collection<MtRepository> repositories) {
    Map<String, String> byCatalogId = new LinkedHashMap<>();
    Map<String, String> byName = new LinkedHashMap<>();
    List<MtRepository> rows = new ArrayList<>(repositories);
    rows.sort(
        Comparator.comparing((MtRepository row) -> RepositoryStatus.retired(row.status))
            .thenComparing(
                (MtRepository row) -> row.lastScanAt,
                Comparator.nullsLast(Comparator.<Instant>reverseOrder()))
            .thenComparing(row -> row.name, Comparator.nullsLast(Comparator.naturalOrder())));
    Set<String> retired = new HashSet<>();
    for (MtRepository row : rows) {
      if (row.catalogId != null && !row.catalogId.isBlank() && row.name != null) {
        byCatalogId.putIfAbsent(row.catalogId, row.name);
        byName.put(row.name, row.catalogId);
        if (RepositoryStatus.retired(row.status)) {
          retired.add(row.name);
        }
      }
    }
    // A retired row whose id answers with ANOTHER, live row: the name a rename left behind.
    Map<String, String> liveByRetiredName = new LinkedHashMap<>();
    Map<String, List<String>> retiredByCatalogId = new LinkedHashMap<>();
    for (MtRepository row : rows) {
      if (row.name == null || !retired.contains(row.name)) {
        continue;
      }
      retiredByCatalogId.computeIfAbsent(row.catalogId, id -> new ArrayList<>()).add(row.name);
      String winner = byCatalogId.get(row.catalogId);
      if (winner != null && !winner.equals(row.name) && !retired.contains(winner)) {
        liveByRetiredName.put(row.name, winner);
      }
    }
    return new RepositoryNames(byCatalogId, byName, liveByRetiredName, retiredByCatalogId);
  }

  /** A stored {@code mt_artifact.repository} — or a caller's spelling — as a repository is CALLED. */
  public String of(String stored) {
    if (stored == null) {
      return null;
    }
    String renamed = liveByRetiredName.get(stored);
    if (renamed != null) {
      return renamed;
    }
    return nameByCatalogId.getOrDefault(stored, stored);
  }

  /**
   * Every string this repository's artifact rows may carry: its name, its catalog id, and every
   * retired name sharing that id.
   *
   * @param repository a catalog name
   * @return the name first, then its other spellings; just the name when the store knows nothing
   *     more, and empty for null
   */
  public List<String> spellings(String repository) {
    if (repository == null) {
      return List.of();
    }
    String catalogId = catalogIdByName.get(repository);
    if (catalogId == null) {
      return List.of(repository);
    }
    List<String> spellings = new ArrayList<>();
    spellings.add(repository);
    spellings.add(catalogId);
    for (String former : retiredByCatalogId.getOrDefault(catalogId, List.of())) {
      if (!spellings.contains(former)) {
        spellings.add(former);
      }
    }
    return List.copyOf(spellings);
  }
}
