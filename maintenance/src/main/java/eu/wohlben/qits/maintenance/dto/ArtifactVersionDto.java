package eu.wohlben.qits.maintenance.dto;

import java.time.Instant;

/**
 * One released version of one artifact, as its package page shows it beside qits-artifacts' own
 * document cell (qits-2490).
 *
 * <p><b>The row a person re-reads from.</b> {@link ArtifactDto} answers the newest version of each
 * artifact and nothing older, so it cannot say which version's bill of materials needs {@code POST
 * /artifacts/ingest}; this is every {@code mt_artifact} row of one {@code (ecosystem, name)}, one per
 * version, with how far each one's document got.
 *
 * @param version the released version
 * @param repository which repository released it, by catalog name; null when nothing announced it
 *     (a manual ingest of a version no release event named)
 * @param occurredAt when that release happened
 * @param sbomStatus PENDING, INGESTED, MISSING or FAILED — MISSING is ordinary, not a fault
 * @param sbomError the sentence a FAILED read left; null for every other status
 * @param ingestedAt when the document was last read into the graph; null until it ever was
 */
public record ArtifactVersionDto(
    String version,
    String repository,
    Instant occurredAt,
    String sbomStatus,
    String sbomError,
    Instant ingestedAt) {}
