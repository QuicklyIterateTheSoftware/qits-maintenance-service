package eu.wohlben.qits.maintenance.bump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * {@code request.conflict} as qits-projects answers it: an object with a {@code target} and the
 * conflicting paths, never text (qits-1149).
 */
class ReleaseRequestConflictTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static JsonNode request(String json) throws Exception {
    return JSON.readTree(json);
  }

  @Test
  void theObjectBecomesOneSentence() throws Exception {
    assertEquals(
        "the fold conflicted on release/r1: src/invoice.txt (refs/heads/fix/rounding), README.md",
        ReleaseRequestClient.conflict(
            request(
                "{\"conflict\":{\"target\":\"release/r1\",\"conflicts\":["
                    + "{\"path\":\"src/invoice.txt\",\"head\":\"refs/heads/fix/rounding\"},"
                    + "{\"path\":\"README.md\",\"head\":null}]}}")));
  }

  @Test
  void noConflictIsNull() throws Exception {
    assertNull(ReleaseRequestClient.conflict(request("{\"conflict\":null}")));
    assertNull(ReleaseRequestClient.conflict(request("{}")));
    assertNull(ReleaseRequestClient.conflict(null));
  }

  @Test
  void textIsNotTheShapeAndIsIgnored() throws Exception {
    assertNull(ReleaseRequestClient.conflict(request("{\"conflict\":\"a sentence\"}")));
  }
}
