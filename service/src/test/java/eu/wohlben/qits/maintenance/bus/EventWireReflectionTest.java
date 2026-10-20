package eu.wohlben.qits.maintenance.bus;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.eventstream.control.EventFrame;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * That the native-image registration is COMPLETE, which is the only half of it a JVM test can guard.
 *
 * <p>On a JVM every type below reflects whether anyone registered it or not, so nothing here can
 * fail for the reason the registration exists. What it can do is fail when a type joins the wire and
 * not the list — a new listener, a new payload record — and when one of the two string names stops
 * resolving, which is the failure mode a compiler cannot see at all: {@code classNames} compiles
 * whatever it says, and a stale package there costs the binary its catch-up or its payload's
 * {@code @JsonIgnore}s with no error anywhere.
 *
 * <p>The correctness proof is the binary, running, against a real qits-events. Read
 * {@link EventWireReflection}'s javadoc before adding a wire type.
 */
class EventWireReflectionTest {

  private static final RegisterForReflection REGISTRATION =
      EventWireReflection.class.getAnnotation(RegisterForReflection.class);

  /** Package-private in the library, so it can only be named as a string — and this is the string. */
  private static final String EVENT_PAGE = "eu.wohlben.qits.eventstream.control.EventPage";

  /** Private nested type in the library, same rule. */
  private static final String MIXIN =
      "eu.wohlben.qits.eventstream.control.CanonicalJson$QitsEventMixin";

  @Test
  void everyTypeThisServiceBindsAFrameIntoIsRegistered() {
    Set<Class<?>> targets = Set.of(REGISTRATION.targets());

    // The frame itself: what arrives on /events/stream and what every catch-up row binds to.
    assertTrue(targets.contains(EventFrame.class), "EventFrame is not registered");
    // The PUT body. Nothing publishes here yet; the registration is what keeps the first one that
    // does from dying inside CanonicalJson with a green suite behind it.
    assertTrue(targets.contains(EventEnvelope.class), "EventEnvelope is not registered");

    // One entry per payload record any listener in this package binds into. A new listener adds its
    // record to the annotation in the same commit, and this is what says so.
    List<Class<?>> payloads =
        List.of(
            SoftwareReleaseListener.SoftwareReleasePayload.class,
            ReleaseFinalizedListener.ReleaseRequestStateChangedPayload.class,
            ScmEventListener.ScmReleasePayload.class,
            ScmEventListener.ScmDeleteBranchPayload.class,
            ScmEventListener.ScmPublishCommitPayload.class);
    for (Class<?> payload : payloads) {
      assertTrue(targets.contains(payload), payload.getSimpleName() + " is not registered");
    }
  }

  @Test
  void theTwoTypesNamedAsStringsAreRegisteredAndStillResolve() {
    Set<String> names = Set.of(REGISTRATION.classNames());

    assertTrue(names.contains(EVENT_PAGE), "EventPage is not registered; catch-up alone would fail");
    assertTrue(names.contains(MIXIN), "the canonical mix-in is not registered");

    // The half a compiler cannot check: the strings above have to name classes that exist.
    assertDoesNotThrow(
        () -> Class.forName(EVENT_PAGE), EVENT_PAGE + " no longer resolves in qits-eventstream");
    assertDoesNotThrow(
        () -> Class.forName(MIXIN), MIXIN + " no longer resolves in qits-eventstream");
  }
}
