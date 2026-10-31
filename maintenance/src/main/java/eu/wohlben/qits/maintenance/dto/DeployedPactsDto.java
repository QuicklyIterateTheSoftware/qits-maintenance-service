package eu.wohlben.qits.maintenance.dto;

import java.util.List;

/**
 * <b>The consumer pact versions a provider's release gate must verify</b> — those of every consumer
 * version that serves or would be rolled back to (qits-1765).
 *
 * <p>An object around the list, never a bare array: see AGENTS.md "Give a consumer an object" — a
 * pact rule at the body root matches whatever the provider answers.
 *
 * @param provider the provider repository asked about, trimmed
 * @param pacts one row per (consumer repository, pact version), ordered by consumer repository and
 *     then by pact version, oldest first; empty for a provider no consumer publishes a pact against
 */
public record DeployedPactsDto(String provider, List<DeployedPactDto> pacts) {

  /**
   * One consumer pact version, and the deployed consumer versions that were built against it.
   *
   * @param consumerRepository the consumer repository, as the pact artifact's name spells it
   * @param groupId the pact artifact's maven groupId
   * @param artifactId the pact artifact's maven artifactId, {@code
   *     <consumerRepository>-pacts-<provider>}
   * @param pactVersion the newest published pact version at or below each of {@code
   *     deployedVersions}
   * @param deployedVersions the consumer's deployed or rollback versions this pact version is the
   *     one for, newest first
   */
  public record DeployedPactDto(
      String consumerRepository,
      String groupId,
      String artifactId,
      String pactVersion,
      List<String> deployedVersions) {}
}
