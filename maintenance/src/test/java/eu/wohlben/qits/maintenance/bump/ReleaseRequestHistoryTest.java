package eu.wohlben.qits.maintenance.bump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * qits-projects' release-request history as {@link ReleaseRequestClient#history} reads it, and the
 * rule that says a cut release published nothing (qits-1156).
 */
class ReleaseRequestHistoryTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** The shape qits-projects answered for qits-maintenance-service on 2026-10-10, trimmed. */
  @Test
  void aListingBecomesTheCutRequestsWithTheirPublishState() throws Exception {
    List<ReleaseRequestClient.Cut> cuts =
        ReleaseRequestClient.cuts(
            JSON.readTree(
                "{\"requests\":["
                    + "{\"id\":\"52d6d856\",\"state\":\"FINALIZED\",\"version\":\"2026.1010.124627\","
                    + "\"pipeline\":{\"phases\":[{\"phase\":\"QA\",\"state\":\"SUCCESS\"},"
                    + "{\"phase\":\"PUBLISH\",\"state\":\"SUCCESS\"}]}},"
                    + "{\"id\":\"d9d80cb3\",\"state\":\"OBSOLETE\",\"version\":\"2026.1010.120523\","
                    + "\"pipeline\":{\"phases\":[{\"phase\":\"QA\",\"state\":\"SUCCESS\"},"
                    + "{\"phase\":\"PUBLISH\",\"state\":\"CANCELLED\"}]}},"
                    + "{\"id\":\"w1\",\"state\":\"WITHDRAWN\",\"version\":null,\"pipeline\":null},"
                    + "{\"id\":\"old\",\"state\":\"FINALIZED\",\"version\":\"2026.901.1\"}]}"));

    assertEquals(
        List.of(
            new ReleaseRequestClient.Cut("52d6d856", "2026.1010.124627", "FINALIZED", "SUCCESS"),
            new ReleaseRequestClient.Cut("d9d80cb3", "2026.1010.120523", "OBSOLETE", "CANCELLED"),
            new ReleaseRequestClient.Cut("old", "2026.901.1", "FINALIZED", null)),
        cuts,
        "a request that cut no version is not a cut");
  }

  @Test
  void aBodyWithNoRequestsArrayIsNoListing() throws Exception {
    assertNull(ReleaseRequestClient.cuts(JSON.readTree("{\"requests\":null}")));
    assertNull(ReleaseRequestClient.cuts(null));
  }

  @Test
  void aClosedRequestWhosePublishNeverFinishedPublishedNothing() {
    assertTrue(new ReleaseRequestClient.Cut("a", "v", "OBSOLETE", "CANCELLED").publishedNothing());
    assertTrue(new ReleaseRequestClient.Cut("a", "v", "WITHDRAWN", null).publishedNothing());
    assertTrue(new ReleaseRequestClient.Cut("a", "v", "OBSOLETE", null).publishedNothing());
  }

  @Test
  void aFinishedPublishOrAnOpenRequestIsNotNothing() {
    assertFalse(new ReleaseRequestClient.Cut("a", "v", "OBSOLETE", "FAILED").publishedNothing());
    assertFalse(new ReleaseRequestClient.Cut("a", "v", "OBSOLETE", "SUCCESS").publishedNothing());
    assertFalse(new ReleaseRequestClient.Cut("a", "v", "FINALIZED", "FAILED").publishedNothing());
    assertFalse(new ReleaseRequestClient.Cut("a", "v", "RELEASED", "CANCELLED").publishedNothing());
    assertFalse(new ReleaseRequestClient.Cut("a", "v", "RELEASED", null).publishedNothing());
    assertFalse(new ReleaseRequestClient.Cut("a", "v", "OBSOLETE", "RUNNING").publishedNothing());
    assertFalse(new ReleaseRequestClient.Cut("a", "v", null, null).publishedNothing());
  }

  /**
   * FINALIZED is a release that completed, phases or not: the requests finalized before the
   * pipeline recorded phases answer none, and a missing changelog after one is a broken publish.
   */
  @Test
  void aFinalizedRequestNeverPublishedNothing() {
    assertFalse(new ReleaseRequestClient.Cut("a", "v", "FINALIZED", null).publishedNothing());
    assertFalse(new ReleaseRequestClient.Cut("a", "v", "FINALIZED", "CANCELLED").publishedNothing());
  }
}
