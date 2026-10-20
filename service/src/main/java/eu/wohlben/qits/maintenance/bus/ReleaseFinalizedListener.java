package eu.wohlben.qits.maintenance.bus;

import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.maintenance.automation.UpstreamReplan;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * <b>A release request FINALIZED, so what its repository published at or below that version may be
 * used now</b> (qits-1472). Consumes qits-projects' {@code ReleaseRequestStateChanged} and acts on
 * {@code state=FINALIZED} alone.
 *
 * <p><b>Finalization is the green light, not the publish.</b> qits-ci's {@code SoftwareRelease}
 * arrives after RELEASED and before FINALIZED: qits-projects finalizes only once the post-release
 * gates hold (the publish run green, the deployment active where the tag declares one) and then
 * merges the tag into main, and a RELEASED request a newer one folds past ends OBSOLETE without
 * ever finalizing. So {@link SoftwareReleaseListener} only RECORDS an artifact as published, and
 * this listener is the other half of the join: it records the finalized {@code (repository,
 * version)} and adopts every recorded, not yet adopted artifact of that repository at or below the
 * version — the highest per package — through the same forward-only writer the announcement used
 * to call. See {@code MaintenanceStore.adoptFinalized} for why "at or below" and not "equal" (an
 * OBSOLETE release's artifact gets no announcement at the version that finally finalizes past it).
 *
 * <p><b>The join key is {@code repoId}, verbatim</b>: qits-projects' repository row id, the same
 * uuid {@code SoftwareRelease.repository} carries. Neither side is translated to a catalog name, so
 * the join cannot depend on whether this inventory had scanned the repository when either frame
 * arrived.
 *
 * <p><b>FINALIZED is often announced TWICE</b>, under two event ids a few milliseconds apart (66 of
 * 263 in one measured week), so the bus's event-id dedup does not cover it: the store's insert is
 * {@code on conflict do nothing}, and the second pass finds nothing left to adopt.
 *
 * <h2>Its own consumer id, and a NEW one</h2>
 *
 * <p>{@value #CONSUMER_ID} is a brand-new consumption, which initializes at the head of the log:
 * finalizations before the deploy are not replayed, and need not be — {@code latest/AdoptionGate}
 * treats every version at or below a package's current {@code mt_latest} as adopted, and a package
 * nothing announced since the deploy is not gated at all. It is NOT {@code
 * maintenance-release-trains}, which is abandoned and must never be reused (see {@code
 * ForeignEventContractTest}), and it is not a signature added to an existing listener, whose
 * watermark would then have to be reasoned about for a second event.
 *
 * <h2>Failure</h2>
 *
 * <p>The policy of every listener here: an unreadable payload, and a FINALIZED naming no {@code
 * repoId} or no {@code version}, is a WARN and a settled event; every other state is a DEBUG and a
 * settled event; a database that will not answer throws, the claim rolls back and the event stays
 * owed. The re-plan is a queue submit after the store's transaction, never an HTTP call inside the
 * claim (qits-1133).
 */
@ApplicationScoped
public class ReleaseFinalizedListener implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(ReleaseFinalizedListener.class);

  /**
   * This consumption's storage key. <b>Never change it, and never let it become {@code
   * maintenance-release-trains}</b>: a consumer id is storage, and that one holds an abandoned
   * watermark.
   */
  static final String CONSUMER_ID = "maintenance-release-finalized";

  /** qits-projects' event name — the simple class name of its record. */
  static final String SIGNATURE = "ReleaseRequestStateChanged";

  /** The one state this listener acts on, as {@code ReleaseRequest.State} spells it. */
  static final String FINALIZED = "FINALIZED";

  /**
   * The {@code ReleaseRequestStateChanged} payload, as a local record bound by {@link
   * CanonicalJson} — a copy of qits-projects' wire shape ({@code
   * components/qits-projects/qits-projects-service/service/src/main/java/eu/wohlben/qits/projects/bus/ReleaseRequestStateChanged.java})
   * rather than a dependency on its module. Only the fields read or logged here; the mapper ignores
   * the rest. {@code changedAt} is the envelope's {@code occurredAt}, read off the frame. Public so
   * {@code bus/EventWireReflection} and the contract test can name it.
   */
  public record ReleaseRequestStateChangedPayload(
      String releaseRequestId,
      String releaseRequestQualifiedId,
      String repoId,
      String repoName,
      String state,
      String previousState,
      String version) {}

  @Inject MaintenanceStore store;

  @Inject UpstreamReplan upstream;

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(SIGNATURE);
  }

  /**
   * {@code selects} is left at its default, as in the other two listeners: the state is filtered
   * here, after a decode the predicate would otherwise have to make too.
   */
  @Override
  public void onFrame(EventFrame frame) {
    ReleaseRequestStateChangedPayload changed = decode(frame);
    if (changed == null) {
      return;
    }
    String state = trimmed(changed.state());
    if (state == null || !FINALIZED.equals(state.toUpperCase(Locale.ROOT))) {
      LOG.debugf(
          "%s %s moved %s to %s, which is not a finalization; it is settled",
          frame.name(), frame.id(), changed.releaseRequestQualifiedId(), changed.state());
      return;
    }
    String repository = trimmed(changed.repoId());
    String version = trimmed(changed.version());
    if (repository == null || version == null) {
      LOG.warnf(
          "%s %s finalized %s with no (repoId, version) to adopt under; it is skipped",
          frame.name(), frame.id(), changed.releaseRequestQualifiedId());
      return;
    }
    // Throws on a database that will not answer: the claim rolls back and the event stays owed.
    List<MaintenanceStore.Adoption> adopted =
        store.adoptFinalized(
            repository, version, "event:" + frame.id(), occurredAt(frame), Instant.now());
    if (adopted.isEmpty()) {
      LOG.debugf(
          "%s %s finalized %s (%s %s); nothing it published was waiting",
          frame.name(), frame.id(), changed.releaseRequestQualifiedId(), repository, version);
      return;
    }
    for (MaintenanceStore.Adoption adoption : adopted) {
      if (adoption.moved()) {
        LOG.infof(
            "%s %s finalized %s and moved the latest %s %s to %s",
            frame.name(),
            frame.id(),
            changed.releaseRequestQualifiedId(),
            adoption.ecosystem(),
            adoption.name(),
            adoption.version());
        // A queue submit, never a call: the claim transaction holds no HTTP open (qits-1133).
        upstream.latestMoved(adoption.ecosystem(), adoption.name());
      } else {
        LOG.debugf(
            "%s %s finalized %s %s at %s, which is not newer than what is recorded; nothing moved",
            frame.name(), frame.id(), adoption.ecosystem(), adoption.name(), adoption.version());
      }
    }
  }

  private static Instant occurredAt(EventFrame frame) {
    return frame.occurredAt() == null ? Instant.now() : frame.occurredAt();
  }

  /** Null on anything that will not read as this payload, warned about once, never thrown. */
  private static ReleaseRequestStateChangedPayload decode(EventFrame frame) {
    try {
      return CanonicalJson.payloadTo(frame.payload(), ReleaseRequestStateChangedPayload.class);
    } catch (RuntimeException unreadable) {
      LOG.warnf(
          "%s %s carried an unreadable payload: %s",
          frame.name(), frame.id(), unreadable.toString());
      return null;
    }
  }

  private static String trimmed(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
