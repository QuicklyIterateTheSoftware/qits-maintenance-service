package eu.wohlben.qits.maintenance.contracts.eventstream;

import eu.wohlben.qits.eventstream.contracts.EventstreamPact;
import eu.wohlben.qits.maintenance.bus.ScmEventListener;
import eu.wohlben.qits.maintenance.bus.SoftwareReleaseListener;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * <b>What qits-eventstream asks qits-events on this service's behalf</b> (qits-1163): the catch-up
 * reads of the two durable listeners in {@code bus/}. This service publishes nothing. The rows come
 * from the pinned library's kit and are committed as {@code
 * pacts/qits-maintenance-service_qits-eventstream-javalib_qits-events-service.json}; the guard fails
 * naming any listener or publisher in {@code target/classes} not passed here.
 */
class EventstreamPactTest {

  static final EventstreamPact PACT =
      EventstreamPact.forConsumer("qits-maintenance-service")
          .listeners(new SoftwareReleaseListener(), new ScmEventListener())
          .publishes(false)
          .guard(Path.of("target/classes"));

  @TestFactory
  Stream<DynamicTest> rows() {
    return PACT.tests();
  }

  @Test
  void pactFile() {
    PACT.compareOrWritePactFile();
  }
}
