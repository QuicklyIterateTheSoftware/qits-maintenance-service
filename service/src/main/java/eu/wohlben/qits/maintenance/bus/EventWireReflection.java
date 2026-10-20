package eu.wohlben.qits.maintenance.bus;

import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.eventstream.control.EventFrame;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * What the event bus binds JSON to, told to native-image. A class with no code: the annotation is
 * the entire content, and this class exists so the annotation has somewhere to live that can say
 * why.
 *
 * <p><b>THIS MODULE COMPILES TO A GraalVM BINARY, and that is the whole stake.</b> Its sibling
 * {@code api/ApiWireReflection} exists for the same class of failure on the REST side — a type
 * reaching Jackson through {@code Response.entity(...)}, invisible to the build-time analysis, a 500
 * in the binary and green in every JVM test. This is that failure on the bus side, and it is worse
 * in one respect: a REST 500 is a request somebody made and can see, while a frame that will not
 * bind is a release nobody hears about.
 *
 * <p><b>Why nothing registers these automatically, and why that is deliberate on the library's
 * side.</b> Quarkus registers reflection for the classes IT knows are serialized — a REST resource's
 * parameters and return types, whatever the CDI {@code ObjectMapper} is handed. {@code CanonicalJson}
 * builds its OWN {@code ObjectMapper} by hand, permanently and on purpose, because the canonical form
 * is a wire contract another service compares byte for byte and must not be downstream of any
 * application's customizer. Correct, and this is the price: to the build step scanning for what needs
 * reflecting on, that mapper and everything it touches are invisible. <b>Do not "fix" a recurrence by
 * injecting the CDI mapper.</b>
 *
 * <p>qits-ci paid for this lesson on a deployed binary: every green build's publish died inside
 * {@code CanonicalJson} with Jackson's "no serializer found … you may need to configure reflection",
 * while its JVM suite was green and structurally had to be — on a JVM these types reflect whether
 * anyone registered them or not. This file is that fix applied here before it can happen, which is
 * why {@code EventWireReflectionTest} guards completeness rather than behaviour.
 *
 * <h2>Why each of these</h2>
 *
 * <ul>
 *   <li>{@link EventFrame} — a live frame off {@code /events/stream}, and every row of the catch-up
 *       log, which binds to the same record. Every listener here reads one.
 *   <li>{@code EventPage} — one page of {@code GET /events/api/events}, by string name because it is
 *       package-private in the library. <b>Without it the stream works in the binary and CATCH-UP
 *       ALONE fails</b> — the half that only matters after a cutover, which is the half a durable
 *       consumer exists for.
 *   <li>{@link SoftwareReleaseListener.SoftwareReleasePayload} and {@link ScmEventListener}'s three
 *       payload records — every type this service binds a frame's payload INTO. They are the whole
 *       of what {@code CanonicalJson.payloadTo} is asked for here.
 *   <li>{@link EventEnvelope} — the PUT body. This service publishes nothing today, and the
 *       registration is here for the day something does: qits-projects carried it for exactly that
 *       reason and then published, and the alternative is a first publish that dies inside
 *       {@code CanonicalJson} with a green suite behind it. It costs a metadata entry.
 *   <li>The {@code CanonicalJson$QitsEventMixin}, by string name because it is a private nested type
 *       inside the library — the mix-in that keeps {@code QitsEvent}'s declared accessors, {@code
 *       eventId} above all, out of a payload. Jackson finds its {@code @JsonIgnore}s by calling
 *       {@code getDeclaredMethods()}, which is reflection like any other. qits-ci measured what
 *       leaving it out costs: no crash, no log, {@code eventId} simply present in a payload that is
 *       supposed to carry no identity at all — a wire contract violation that breaks nothing visible,
 *       which is the worse of the two failure modes.
 * </ul>
 *
 * <p>Both string names are resolved by {@code EventWireReflectionTest} with {@code Class.forName}, so
 * a rename in the library goes red here rather than costing a binary its catch-up or its mix-in
 * silently.
 *
 * <p>It lives in {@code service/} because {@code service/} is the deployable, and the deployable is
 * what gets built into an image and therefore what tells the builder about itself.
 */
@RegisterForReflection(
    targets = {
      EventFrame.class,
      EventEnvelope.class,
      SoftwareReleaseListener.SoftwareReleasePayload.class,
      ReleaseFinalizedListener.ReleaseRequestStateChangedPayload.class,
      ScmEventListener.ScmReleasePayload.class,
      ScmEventListener.ScmDeleteBranchPayload.class,
      ScmEventListener.ScmPublishCommitPayload.class
    },
    classNames = {
      "eu.wohlben.qits.eventstream.control.EventPage",
      "eu.wohlben.qits.eventstream.control.CanonicalJson$QitsEventMixin"
    })
public final class EventWireReflection {

  private EventWireReflection() {}
}
