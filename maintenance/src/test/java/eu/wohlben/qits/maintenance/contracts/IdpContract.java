package eu.wohlben.qits.maintenance.contracts;

import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.Trigger;

/**
 * <b>What qits-maintenance asks qits-idp, and why</b> (ticket qits-1149): the client-credentials
 * token every peer call carries, minted by quarkus-oidc-client's named client {@code qits} ({@code
 * PeerTokens}) — {@code POST /idp/token} with HTTP Basic, {@code grant_type=client_credentials} and
 * {@code audience=qits-platform} ({@code grant-options.client.audience}).
 *
 * <p>The mint is caused by whichever peer call needs a bearer first, so its trigger is that call:
 * {@code PeerClient.send}. {@code IdpTokenMintPactTest} runs it through the real oidc client.
 *
 * <p><b>Pending.</b> qits-idp records {@code issueToken} for a bare {@code
 * grant_type=client_credentials}; the platform-audience ask needs a state of its own, and the row
 * waits for it.
 */
final class IdpContract {

  static final GoldenMasters IDP = GoldenMasters.of("qits-idp-service", "qits-idp");

  static final String A_SERVICE_CLIENT_ASKING_FOR_THE_PLATFORM_AUDIENCE =
      "a service client asking for the platform audience";

  static final GoldenInteraction MINT =
      GoldenInteraction.of(
              Trigger.of("call", "call", "PeerClient.send"),
              A_SERVICE_CLIENT_ASKING_FOR_THE_PLATFORM_AUDIENCE,
              "issueToken")
          .header("Authorization", "{authorization}")
          .consumes("access_token", "expires_in");

  static final ConsumerPact PACT =
      ConsumerPact.of(eu.wohlben.qits.maintenance.testing.contracts.GoldenMasters.CONSUMER, IDP, MINT);

  private IdpContract() {}
}
