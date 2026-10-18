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
 * {@code PeerClient.send}. {@code IdpTokenMintPactTest} runs it through the real oidc client, in
 * this module because the mint needs the application; the committed pact file is checked there too.
 *
 * <p>qits-idp records it as {@code issueToken} in {@value #A_SERVICE_CLIENT_CALLING_ITS_PEERS}
 * (golden masters 2026.1018.142635): the Basic credential and the audience are the state's params.
 */
final class IdpContract {

  static final GoldenMasters IDP = GoldenMasters.of("qits-idp-service", "qits-idp");

  static final String A_SERVICE_CLIENT_CALLING_ITS_PEERS = "a service client calling its peers";

  static final GoldenInteraction MINT =
      GoldenInteraction.of(
              Trigger.of("call", "call", "PeerClient.send"),
              A_SERVICE_CLIENT_CALLING_ITS_PEERS,
              "issueToken")
          .header("Authorization", "{authorization}")
          .consumes("access_token", "expires_in");

  static final ConsumerPact PACT = ConsumerPact.of("qits-maintenance-service", IDP, MINT);

  private IdpContract() {}
}
