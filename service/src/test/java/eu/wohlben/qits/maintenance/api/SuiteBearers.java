package eu.wohlben.qits.maintenance.api;

import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * <b>How a {@code @QuarkusTest} names the caller of one request</b> (epic qits-543, qits-1365).
 *
 * <p>Forward-auth — {@code X-Qits-User} / {@code X-Qits-Roles} and the {@code %test} dev user,
 * which held {@code qits:admin} and {@code qits:system} — leaves qits-auth-core with qits-1338, so
 * no test here may lean on either. Every request names its caller instead: a platform peer
 * ({@code qits:system}) reading pins, ingesting an SBOM or asking for a scan, a signed-in operator
 * ({@code qits:admin}) reading the inventory and pressing the levers, an agent ({@code qits:agent})
 * or a CI run ({@code qits:ci-run}) where a door test is about exactly that caller. Each sends its
 * own bearer, made by {@link #bearer}, exactly as a machine or a signed-in person reaches this
 * service.
 *
 * <p>The suite's default profile runs no idp (the OIDC tenant is off, and a disabled tenant answers
 * any bearer 401), so this is an authentication mechanism of the suite's own, asked before every
 * other: a request carrying a bearer {@link #bearer} granted is the identity the token stands for —
 * a JWT principal with its {@code sub}, the platform audience and its roles. Anything else is not
 * its business and goes on to the shipped mechanisms untouched. The shape is
 * qits-deployments-service's {@code SuiteBearers}.
 *
 * <p><b>Idle unless a test granted one</b>: with nothing granted it answers nothing, so no other
 * suite sees it. A profile with the tenant on ({@code BearerIssuerTest}) validates every bearer
 * itself against a mock idp instead.
 */
@ApplicationScoped
public class SuiteBearers implements HttpAuthenticationMechanism {

  static final String PLATFORM_AUDIENCE = "qits-platform";

  /** The platform peer that reads pins, ingests SBOMs and asks for scans. */
  public static final String PLATFORM = "a-platform-service";

  /** The signed-in person who reads the inventory and pulls the operator levers. */
  public static final String OPERATOR = "an-operator";

  /** A CI run's step. */
  public static final String CI_RUN = "a-ci-run";

  /** What a granted bearer stands for. */
  record Grant(String subject, Set<String> roles) {}

  /**
   * Every bearer {@link #bearer} handed out. Static rather than a bean field: the mechanism the HTTP
   * layer asks is not reliably the contextual instance a test reaches through Arc, and a grant that
   * lands on one and is read from the other is a 401 nobody can explain.
   */
  private static final Map<String, Grant> grants = new ConcurrentHashMap<>();

  /**
   * The {@code Authorization} value of {@code subject} holding {@code roles} (each may be a
   * comma-separated list), granted so a request carrying it is that identity.
   */
  public static String bearer(String subject, String... roles) {
    String[] each =
        Arrays.stream(roles)
            .flatMap(role -> Arrays.stream(role.split(",")))
            .map(String::trim)
            .filter(role -> !role.isEmpty())
            .toArray(String[]::new);
    String authorization = "Bearer suite." + subject + "." + String.join("+", each);
    grants.put(authorization, new Grant(subject, Set.of(each)));
    return authorization;
  }

  /** A platform peer's bearer: {@code qits:system}. */
  public static String platform() {
    return bearer(PLATFORM, "qits:system");
  }

  /** A signed-in operator's bearer: {@code qits:admin}. */
  public static String operator() {
    return bearer(OPERATOR, "qits:admin");
  }

  /** A CI run's bearer: {@code qits:ci-run}. */
  public static String ciRun() {
    return bearer(CI_RUN, "qits:ci-run");
  }

  @Override
  public Uni<SecurityIdentity> authenticate(
      RoutingContext context, IdentityProviderManager identityProviderManager) {
    String authorization = context.request().getHeader("Authorization");
    Grant grant = authorization == null ? null : grants.get(authorization);
    if (grant == null) {
      return Uni.createFrom().nullItem();
    }
    QuarkusSecurityIdentity.Builder named =
        QuarkusSecurityIdentity.builder().setPrincipal(new Token(grant.subject()));
    grant.roles().forEach(named::addRole);
    return Uni.createFrom().item(named.build());
  }

  @Override
  public Uni<ChallengeData> getChallenge(RoutingContext context) {
    return Uni.createFrom().nullItem();
  }

  @Override
  public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
    return Set.of();
  }

  /** Before the shipped mechanisms, so the disabled OIDC tenant never sees a suite bearer. */
  @Override
  public int getPriority() {
    return 3000;
  }

  /** The JWT the caller presents: a subject and the platform audience, nothing else. */
  private record Token(String subject) implements JsonWebToken {

    @Override
    public String getName() {
      return subject;
    }

    @Override
    public String getSubject() {
      return subject;
    }

    @Override
    public Set<String> getAudience() {
      return Set.of(PLATFORM_AUDIENCE);
    }

    @Override
    public Set<String> getClaimNames() {
      return Set.of("sub", "aud");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getClaim(String claimName) {
      return switch (claimName) {
        case "sub" -> (T) subject;
        case "aud" -> (T) Set.of(PLATFORM_AUDIENCE);
        default -> null;
      };
    }
  }
}
