package eu.wohlben.qits.maintenance.stories.support;

import eu.wohlben.qits.servicemock.idp.MockIdp;
import io.restassured.specification.RequestSpecification;

/**
 * The two kinds of caller this service has, and how a story hands each of them to a request.
 *
 * <h2>A person is a bearer too</h2>
 *
 * <p>{@link #operator(RequestSpecification)} presents a person's token from the same idp: the
 * operator's name in {@code sub}, the platform audience and {@code qits:admin} in {@code groups}.
 * Forward-auth — the {@code X-Qits-User} / {@code X-Qits-Roles} pair the edge used to assert for a
 * logged-in operator — leaves qits-auth-core with qits-1338 (epic qits-543, qits-1365), so a person
 * reaches this service the way a machine does, and the roles are what tell them apart. That is
 * still what lets the scan and bump stories say "an operator" honestly rather than dressing a
 * person up as a machine: the subject is a person's and the role is the human one.
 *
 * <h2>A machine is a bearer this run's idp minted</h2>
 *
 * <p>{@link #machineToken()} mints a token signed by {@link MockIdp}'s generated keypair, addressed
 * to the platform audience this service enforces and carrying {@code qits:system} in {@code groups}.
 * Every token is minted <b>fresh per call and never cached</b>: a helper that handed the same string
 * to two stories would make {@code assertNotLeaked} a weaker claim than it reads as.
 *
 * <h2>Why both, in one catalogue</h2>
 *
 * <p>Because both really reach this service, and the shape of the guard is that <b>they reach
 * exactly the same surface</b>. Every route is {@code @RolesAllowed({"qits:admin", "qits:system"})}
 * — an operator presses <i>Bump now</i> in a browser and a scheduled machine may ask for the same
 * thing, so a machine-only guard would lock the operator out of the button this service exists to
 * offer. There is no ceiling here that one of the two does not reach, which is this repository's
 * shape rather than an omission; what bounds a credential is the role SET, and the refusal stories
 * are where that is proved.
 */
public final class StoryIdentities {

  /** The human role: every route here, including the two that queue work. */
  public static final String ADMIN_ROLE = "qits:admin";

  /** The machine role a platform peer holds — and the only role this service acts OUTWARD with. */
  public static final String MACHINE_ROLE = "qits:system";

  /** A real platform role that is neither of the two every route here names. */
  public static final String READER_ROLE = "qits:reader";

  /** How the diagram names a person at the console. */
  public static final String OPERATOR = "an operator";

  /** …and a platform peer holding a bearer. */
  public static final String PEER = "a platform service";

  /** …a caller who presented no credential at all. */
  public static final String ANONYMOUS = "an unauthenticated caller";

  /** …and one who authenticated perfectly and covers nothing. */
  public static final String WRONG_ROLE = "a caller with the wrong role";

  /** The operator, by name — the {@code sub} of the person's token. */
  private static final String OPERATOR_NAME = "alice";

  private StoryIdentities() {}

  /** {@code given()} with a logged-in operator's bearer: {@code qits:admin}. */
  public static RequestSpecification operator(RequestSpecification request) {
    return withRole(request, ADMIN_ROLE);
  }

  /** The same person, with a role this service's routes have never heard of. */
  public static RequestSpecification withRole(RequestSpecification request, String role) {
    return bearer(request, personToken(role));
  }

  /** A freshly minted person's bearer: the operator's name, the platform audience, {@code role}. */
  private static String personToken(String role) {
    return MockIdp.attach()
        .token()
        .subject(OPERATOR_NAME)
        .audience(StoryProfile.PLATFORM_AUDIENCE)
        .groups(role)
        .mint();
  }

  /** A freshly minted platform-peer bearer: the platform audience, {@code qits:system}. */
  public static String machineToken() {
    return MockIdp.attach()
        .token()
        .subject("a-platform-service")
        .audience(StoryProfile.PLATFORM_AUDIENCE)
        .groups(MACHINE_ROLE)
        .mint();
  }

  /** {@code given()} carrying a bearer the story already holds — it has to pin it as not leaked. */
  public static RequestSpecification bearer(RequestSpecification request, String token) {
    return request.header("Authorization", "Bearer " + token);
  }
}
