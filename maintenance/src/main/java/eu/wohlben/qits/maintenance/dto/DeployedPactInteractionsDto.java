package eu.wohlben.qits.maintenance.dto;

import java.util.List;
import java.util.Map;

/**
 * <b>What a provider's deployed consumers actually call</b> (qits-2702): {@link DeployedPactsDto}'s
 * rows, each with the interactions its pact jar holds against the provider — so a provider can ask
 * which of its paths any serving consumer still uses (qits-toggles' retirement, qits-2696).
 *
 * <p>An object around the list, never a bare array: see AGENTS.md "Give a consumer an object".
 *
 * @param provider the provider repository asked about, trimmed
 * @param pacts one row per (consumer repository, pact version), in {@link DeployedPactsDto}'s
 *     order, leaving out every consumer that no longer declares a pact on the provider
 */
public record DeployedPactInteractionsDto(String provider, List<ConsumerPactDto> pacts) {

  /**
   * One deployed consumer pact version and what it asks of the provider.
   *
   * @param consumerRepository the consumer repository
   * @param pactVersion the pact version the deployed versions were built against
   * @param deployedVersions the consumer's deployed or rollback versions it is the pact for, newest
   *     first
   * @param interactions every HTTP interaction of the pact, in the pact file's order
   */
  public record ConsumerPactDto(
      String consumerRepository,
      String pactVersion,
      List<String> deployedVersions,
      List<InteractionDto> interactions) {}

  /**
   * One interaction, reduced to what names the call.
   *
   * @param method the HTTP method, upper case
   * @param path the concrete path the consumer's pact requests — since qits-2511 the provider's
   *     exact example path
   * @param operationId the provider operation the consumer named, null when its pact does not say
   * @param providerStates the states the interaction is verified in, with their params
   */
  public record InteractionDto(
      String method, String path, String operationId, List<ProviderStateDto> providerStates) {}

  /**
   * One provider state.
   *
   * @param name the state's name
   * @param params its params, by name; values that are not strings are given as their JSON text
   */
  public record ProviderStateDto(String name, Map<String, String> params) {}
}
