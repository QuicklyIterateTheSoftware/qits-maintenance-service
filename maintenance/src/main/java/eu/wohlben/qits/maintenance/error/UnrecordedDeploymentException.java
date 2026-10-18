package eu.wohlben.qits.maintenance.error;

import java.util.List;

/**
 * A version qits-deployments serves or would roll back to belongs to a repository this inventory
 * holds, and the release ledger has no row for it — so {@code GET /pins} cannot say what that
 * release declared (qits-1172). A 503, the same refusal {@link DeploymentsUnansweredException} is:
 * the GC treats an answered source as complete, and a keep-set without that release's pins deletes
 * what a restart or a rollback of it would pull.
 *
 * <p><b>Why a refusal and not a 200 with a gap.</b> {@code adoption/ReleaseLedger} declines to
 * record a release whose tag the git host did not hold when the frame arrived, and nothing else
 * ever writes that row. The keep-set used to match deployed versions by version alone, so such a
 * release simply matched nothing and the answer was a 200 — the one answer the consumer believes.
 * The sentence names each application, version and repository, which is what a person needs to
 * fill the row (a redelivered {@code SCMRelease}, or the ledger backfill) and unblock the GC.
 *
 * <p>503 rather than 500: nothing here is broken, the answer is merely not complete yet, and a
 * consumer that retries after the row is filled gets it.
 */
public class UnrecordedDeploymentException extends MaintenanceException {

  /**
   * @param unrecorded one entry per missing release, already spelled for a person — {@code
   *     <application> <version> (repository <name>)}
   */
  public UnrecordedDeploymentException(List<String> unrecorded) {
    super(
        503,
        "a release that serves or would be rolled back to is not in the release ledger, so the pins"
            + " it declared are not known: "
            + String.join(", ", unrecorded));
  }
}
