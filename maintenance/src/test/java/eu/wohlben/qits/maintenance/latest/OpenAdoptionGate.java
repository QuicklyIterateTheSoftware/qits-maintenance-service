package eu.wohlben.qits.maintenance.latest;

import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.PinKind;
import java.util.function.Predicate;

/**
 * An {@link AdoptionGate} that admits every version, for suites that build a {@link LatestResolver}
 * by hand (qits-1472).
 *
 * <p>A contract row asks what a registry answers and how the client reads it; which of those
 * versions a scan may adopt is the gate's own question, answered by {@code LatestResolverTest}.
 * Wiring the real gate would need the database those suites do not start, and leaving the field
 * unset makes every lookup a {@code NullPointerException} the resolver reports as a failed row.
 */
public final class OpenAdoptionGate extends AdoptionGate {

  @Override
  public Predicate<String> admits(Ecosystem ecosystem, PinKind kind, String name) {
    return version -> true;
  }

  /** {@code resolver}, with this gate set on it. */
  public static LatestResolver on(LatestResolver resolver) {
    resolver.gate = new OpenAdoptionGate();
    return resolver;
  }
}
