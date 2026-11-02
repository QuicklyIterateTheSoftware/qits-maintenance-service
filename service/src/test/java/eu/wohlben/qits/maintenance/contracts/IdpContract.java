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
 *
 * <p>And the two reads the quarkus-oidc TENANT makes at startup (qits-708, the qits-1149 pattern):
 * the discovery document read from {@code quarkus.oidc.auth-server-url}, whose {@code issuer} is
 * what every bearer's {@code iss} is checked against and is therefore bound by value, and the JWKS
 * at the {@code jwks_uri} it names. No class of this source tree makes either call, so they are
 * framework rows: {@code IdpTenantReadsPactTest} reads them as quarkus-oidc does and holds them to
 * the shipped tenant configuration.
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

  /** The qits-idp state that records the discovery document and the JWKS. */
  static final String A_PUBLISHED_SIGNING_KEY = "a published signing key";

  /** The quarkus-oidc tenant's startup reads, as qits-idp's consumers trigger them. */
  private static final Trigger STARTUP = Trigger.event("StartupEvent");

  /**
   * The discovery document: the issuer the tenant checks every bearer's {@code iss} against, and
   * where the keys are. The issuer is bound BY VALUE ({@code exact}): an idp answering another
   * issuer refuses every token this service accepts, so its type alone is not the contract.
   */
  static final GoldenInteraction DISCOVERY =
      GoldenInteraction.of(STARTUP, A_PUBLISHED_SIGNING_KEY, "getOpenIdConfiguration")
          .consumes("issuer", "jwks_uri", "token_endpoint")
          .exact("issuer");

  /** The JWKS: each key with what quarkus-oidc needs to build it and to pick it by {@code kid}. */
  static final GoldenInteraction JWKS =
      GoldenInteraction.of(STARTUP, A_PUBLISHED_SIGNING_KEY, "getJwks")
          .consumes("keys[].kid", "keys[].kty", "keys[].n", "keys[].e", "keys[].alg", "keys[].use");

  static final ConsumerPact PACT =
      ConsumerPact.of("qits-maintenance-service", IDP, MINT, DISCOVERY, JWKS);

  private IdpContract() {}
}
