package eu.wohlben.qits.maintenance.bus;

import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.maintenance.automation.StrandedBumpReoffer;
import eu.wohlben.qits.maintenance.entity.MtBump;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * <b>A release request RELEASED a fold its {@code dependency-bump} had already committed past, so
 * the bump is owed again</b> (qits-2290). Consumes qits-projects' {@code ReleaseRequestStateChanged}
 * and acts on {@code state=RELEASED} alone.
 *
 * <p><b>The race it closes.</b> A {@code dependency-bump} row can go green and push a newer pin onto
 * the request's source branch while the request's fold F is already building. qits-projects
 * deliberately does not refold for that push and releases F, so the commit is not in the release —
 * and before this listener nothing said so: the repository waited hours for the ordinary
 * longest-owed queue. Live: qits-system-service-rr-252, row COMMITTED at f4eb5de, request RELEASED
 * with {@code mergedSha} f4eb5de. {@link StrandedBumpReoffer#releasedAt} marks every such row's
 * repository owed a re-offer, which the dispatcher sends first once the release is out of the way.
 *
 * <p><b>The join key is the request id</b> — {@code releaseRequestId}, which the automation rows
 * carry verbatim — and the fold is {@code mergedSha}. A RELEASED announced twice marks once: the
 * store keeps the first mark.
 *
 * <h2>Its own consumer id, and a NEW one</h2>
 *
 * <p>{@value #CONSUMER_ID} is a brand-new consumption, which initializes at the head of the log:
 * releases before the deploy are not replayed. It is not {@code maintenance-release-finalized},
 * whose watermark belongs to the FINALIZED adoption, and never the abandoned {@code
 * maintenance-release-trains} (see {@code ForeignEventContractTest}).
 *
 * <h2>Failure</h2>
 *
 * <p>The policy of every listener here: an unreadable payload, and a RELEASED naming no {@code
 * releaseRequestId} or no {@code mergedSha}, is a WARN and a settled event; every other state is a
 * DEBUG and a settled event; a database that will not answer throws, the claim rolls back and the
 * event stays owed.
 */
@ApplicationScoped
public class ReleaseReleasedListener implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(ReleaseReleasedListener.class);

  /** This consumption's storage key. <b>Never change it</b>: a consumer id is storage. */
  static final String CONSUMER_ID = "maintenance-release-released";

  /** qits-projects' event name — the simple class name of its record. */
  static final String SIGNATURE = "ReleaseRequestStateChanged";

  /** The one state this listener acts on, as {@code ReleaseRequest.State} spells it. */
  static final String RELEASED = "RELEASED";

  /**
   * The {@code ReleaseRequestStateChanged} payload as this listener reads it — the fields of {@link
   * ReleaseFinalizedListener.ReleaseRequestStateChangedPayload} it needs, plus {@code mergedSha},
   * the fold that released. Public so {@code bus/EventWireReflection} and the contract test can name
   * it.
   */
  public record ReleasedPayload(
      String releaseRequestId,
      String releaseRequestQualifiedId,
      String repoId,
      String repoName,
      String state,
      String mergedSha) {}

  @Inject StrandedBumpReoffer reoffers;

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(SIGNATURE);
  }

  @Override
  public void onFrame(EventFrame frame) {
    ReleasedPayload changed = decode(frame);
    if (changed == null) {
      return;
    }
    String state = trimmed(changed.state());
    if (state == null || !RELEASED.equals(state.toUpperCase(Locale.ROOT))) {
      LOG.debugf(
          "%s %s moved %s to %s, which is not a release; it is settled",
          frame.name(), frame.id(), changed.releaseRequestQualifiedId(), changed.state());
      return;
    }
    String request = trimmed(changed.releaseRequestId());
    String mergedSha = trimmed(changed.mergedSha());
    if (request == null || mergedSha == null) {
      LOG.warnf(
          "%s %s released %s with no (releaseRequestId, mergedSha) to look up; it is skipped",
          frame.name(), frame.id(), changed.releaseRequestQualifiedId());
      return;
    }
    // Throws on a database that will not answer: the claim rolls back and the event stays owed.
    List<MtBump> stranded =
        reoffers.releasedAt(request, trimmed(changed.releaseRequestQualifiedId()), mergedSha);
    if (stranded.isEmpty()) {
      LOG.debugf(
          "%s %s released %s at %s; no dependency bump was committed past it",
          frame.name(), frame.id(), changed.releaseRequestQualifiedId(), mergedSha);
    }
  }

  /** Null on anything that will not read as this payload, warned about once, never thrown. */
  private static ReleasedPayload decode(EventFrame frame) {
    try {
      return CanonicalJson.payloadTo(frame.payload(), ReleasedPayload.class);
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
