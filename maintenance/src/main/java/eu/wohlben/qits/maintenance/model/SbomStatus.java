package eu.wohlben.qits.maintenance.model;

/**
 * How far one released artifact's bill of materials has got.
 *
 * <p><b>MISSING is not a failure.</b> qits-artifacts stores an SBOM for artifacts released since
 * that route existed and holds nothing for the ones released before it, so a 404 is the ORDINARY
 * answer during the rollout — for most coordinates it is also the permanent one. Nothing retries it
 * on a timer of its own; the daily SBOM check re-reads every MISSING and FAILED row still in
 * qits-artifacts, so a document backfilled later is ingested (qits-739), and a person can re-ingest
 * one by hand.
 */
public enum SbomStatus {
  /** The row exists and the document has not been read yet — the outbox state. */
  PENDING,

  /** The components and edges recorded against this artifact are that document's. */
  INGESTED,

  /** qits-artifacts has no document for this coordinate. The daily SBOM check asks again. */
  MISSING,

  /** The document could not be read, and {@code sbom_error} says why. */
  FAILED;

  /** The status for a wire or column value, defaulting to PENDING for a word this build lacks. */
  public static SbomStatus of(String value) {
    if (value == null) {
      return PENDING;
    }
    try {
      return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      return PENDING;
    }
  }
}
