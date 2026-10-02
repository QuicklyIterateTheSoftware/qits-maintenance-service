package eu.wohlben.qits.maintenance.model;

import java.util.UUID;

/**
 * Where a released artifact came from, as the {@code SoftwareRelease} frame that announced it says
 * — the three facts {@code mt_artifact} keeps for the SBOM check (V14).
 *
 * @param projectId the qits-projects project, or null when the frame named none
 * @param section {@code artifacts} or {@code contracts}, the release.yml section; null when the
 *     frame predates the field, which reads as {@code artifacts}
 * @param runId the qits-ci release run, or null
 */
public record ReleaseOrigin(String projectId, String section, UUID runId) {

  /** Nothing known: a manual ingest, or a caller that predates these fields. */
  public static final ReleaseOrigin NONE = new ReleaseOrigin(null, null, null);
}
