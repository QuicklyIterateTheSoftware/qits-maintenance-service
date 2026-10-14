package eu.wohlben.qits.maintenance.pending;

import eu.wohlben.qits.maintenance.latest.PublishedLookup;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.PinKind;

/**
 * Every version a registry has published of one dependency — the one registry read the pending
 * rule needs, handed in so {@link PendingChanges} stays a pure function.
 *
 * <p>Asked only for a maven property that two or more artifacts of one manifest read (qits-1206);
 * the beans pass {@code LatestResolver::published}, a test passes a map.
 */
@FunctionalInterface
public interface PublishedVersions {

  /** What the registry lists for this dependency; never throws, a failure is the result. */
  PublishedLookup lookup(Ecosystem ecosystem, PinKind kind, String name);
}
