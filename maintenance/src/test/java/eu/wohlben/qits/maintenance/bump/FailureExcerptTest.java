package eu.wohlben.qits.maintenance.bump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The excerpt rule and the failing-step pick (qits-1116). */
class FailureExcerptTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static String resource(String name) throws IOException {
    try (InputStream in = FailureExcerptTest.class.getResourceAsStream(name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /**
   * A real run's step (qits-ci run 30a2b36a): a dependency that did not download. The three lines
   * that say so, and none of Maven's advice after them.
   */
  @Test
  void aRealMavenFailureNamesTheGoalTheDependencyAndTheTransfer() throws IOException {
    String excerpt = FailureExcerpt.of(resource("/ci/run-30a2b36a-step0.txt"));

    List<String> lines = excerpt.lines().toList();
    assertEquals(3, lines.size(), excerpt);
    assertEquals(
        "[ERROR] Failed to execute goal on project qits-artifacts-service: Could not resolve"
            + " dependencies for project eu.wohlben.qits:qits-artifacts-service:quarkus:2026.1008.30440",
        lines.get(0));
    assertEquals(
        "[ERROR] dependency: com.microsoft.playwright:driver-bundle:jar:1.61.0 (test)",
        lines.get(1));
    assertTrue(lines.get(2).startsWith("[ERROR] \tCould not transfer artifact"), lines.get(2));
    assertTrue(
        lines.get(2).contains("Premature end of Content-Length delimited message body"),
        lines.get(2));
    assertTrue(lines.get(2).length() <= FailureExcerpt.LINE_CAP);
  }

  @Test
  void boilerplateIsDropped() {
    String output =
        String.join(
            "\n",
            "[ERROR] ",
            "[ERROR] -> [Help 1]",
            "[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.",
            "[ERROR] Re-run Maven using the -X switch to enable full debug logging.",
            "[ERROR] For more information about the errors and possible solutions, please read",
            "[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/X",
            "[ERROR] After correcting the problems, you can resume the build with the command",
            "[ERROR]   mvn <args> -rf :qits-artifacts-service",
            "Error: the real one");
    assertEquals("Error: the real one", FailureExcerpt.of(output));
  }

  @Test
  void noMarkerFallsBackToTheLastThreeNonBlankLines() {
    String output = "one\ntwo\n\nthree\nfour\n   \n";
    assertEquals("two\nthree\nfour", FailureExcerpt.of(output));
  }

  @Test
  void ansiEscapesAreStripped() {
    String output = "\u001B[1;31m[ERROR]\u001B[m Something \u001B[36mbroke\u001B[0m\n";
    assertEquals("[ERROR] Something broke", FailureExcerpt.of(output));
  }

  @Test
  void eachLineAndTheWholeAreCapped() {
    String longLine = "error: " + "x".repeat(500);
    String excerpt = FailureExcerpt.of(longLine + "\n" + longLine + "\n" + longLine + "\n" + longLine);
    List<String> lines = excerpt.lines().toList();
    assertEquals(3, lines.size());
    lines.forEach(line -> assertEquals(FailureExcerpt.LINE_CAP, line.length()));
    assertTrue(excerpt.length() <= FailureExcerpt.TOTAL_CAP);
  }

  @Test
  void nothingToQuoteIsNull() {
    assertNull(FailureExcerpt.of(null));
    assertNull(FailureExcerpt.of("  \n\n "));
  }

  private static ObjectNode step(int index, String status, Integer exitCode, String output) {
    ObjectNode step = JSON.createObjectNode();
    step.put("stepIndex", index);
    step.put("image", "image-" + index);
    step.put("status", status);
    if (exitCode == null) {
      step.putNull("exitCode");
    } else {
      step.put("exitCode", exitCode);
    }
    step.put("output", output);
    return step;
  }

  private static JsonNode body(ObjectNode... steps) {
    ObjectNode body = JSON.createObjectNode();
    body.put("status", "FAILED");
    ArrayNode array = body.putArray("steps");
    for (ObjectNode step : steps) {
      array.add(step);
    }
    return body;
  }

  @Test
  void theLowestIndexFailedStepIsTheFailure() {
    CiClient.Failure failure =
        CiClient.failure(
            body(
                step(2, "FAILED", 3, "error: later"),
                step(0, "SUCCESS", 0, "fine"),
                step(1, "FAILED", null, "error: first")));
    assertEquals(new CiClient.Failure(1, "image-1", null, "error: first"), failure);
  }

  @Test
  void withNoFailedStepTheLastNonZeroExitIsTheFailure() {
    CiClient.Failure failure =
        CiClient.failure(
            body(
                step(0, "SUCCESS", 0, "fine"),
                step(1, "SKIPPED", 4, "Error: one"),
                step(2, "CANCELLED", 137, "killed")));
    assertEquals(new CiClient.Failure(2, "image-2", 137, "killed"), failure);
  }

  @Test
  void stepsThatAllPassedNameNoFailure() {
    assertNull(CiClient.failure(body(step(0, "SUCCESS", 0, "fine"))));
    assertNull(CiClient.failure(JSON.createObjectNode().put("status", "FAILED")));
  }

  @Test
  void theRealRunBodyNamesStepZero() throws IOException {
    ObjectNode step = step(0, "FAILED", 1, resource("/ci/run-30a2b36a-step0.txt"));
    step.put("image", "registry.dev.localhost:8080/qits/build-images/maven-base:latest");
    CiClient.Failure failure = CiClient.failure(body(step));
    assertEquals(0, failure.stepIndex());
    assertEquals(1, failure.exitCode());
    assertEquals(
        "registry.dev.localhost:8080/qits/build-images/maven-base:latest", failure.image());
    assertTrue(failure.firstLine().startsWith("[ERROR] Failed to execute goal"), failure.firstLine());
  }
}
