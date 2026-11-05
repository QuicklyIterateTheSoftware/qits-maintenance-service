package eu.wohlben.qits.maintenance.sbom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.bootstrap.graph.Ecosystem;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A real-shaped CycloneDX document into the graph this service stores.
 *
 * <p><b>The claim these tests are about is DIRECT.</b> Everything else here — the names, the
 * versions, the edges — is bookkeeping; whether a component is direct or transitive is the whole
 * reason the document is read at all, because it is the difference between something a manifest
 * could hold a line for and something no line anywhere names. A parser that called every listed
 * component direct would produce a graph in which nothing was ever transitive, and the repository
 * page's whole new section would be empty for a reason nobody could see.
 *
 * <p><b>And the second claim is that nothing here throws.</b> These documents come from other
 * people's build plugins, across four package worlds and three spec versions. A parser that refused
 * one would put an artifact permanently in FAILED over a field this service does not even read.
 */
class CycloneDxParserTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * The document a maven release of this platform produces: the artifact itself as
   * {@code metadata.component}, three components, and a graph two levels deep.
   *
   * <p>{@code jackson-annotations} is reachable ONLY through {@code jackson-databind} — it is the
   * transitive the assertions below are really about.
   */
  private static final String RELEASE =
      """
      {"bomFormat":"CycloneDX","specVersion":"1.5","version":1,
       "metadata":{"component":{"bom-ref":"self","type":"library",
                                "name":"qits-eventstream","version":"2026.901.1",
                                "purl":"pkg:maven/eu.wohlben.qits/qits-eventstream@2026.901.1"}},
       "components":[
         {"bom-ref":"c-databind","type":"library","name":"jackson-databind","version":"2.18.2",
          "purl":"pkg:maven/com.fasterxml.jackson.core/jackson-databind@2.18.2"},
         {"bom-ref":"c-annotations","type":"library","name":"jackson-annotations","version":"2.18.2",
          "purl":"pkg:maven/com.fasterxml.jackson.core/jackson-annotations@2.18.2"},
         {"bom-ref":"c-postgres","type":"library","name":"postgresql","version":"42.7.4",
          "purl":"pkg:maven/org.postgresql/postgresql@42.7.4"}],
       "dependencies":[
         {"ref":"self","dependsOn":["c-databind","c-postgres"]},
         {"ref":"c-databind","dependsOn":["c-annotations"]},
         {"ref":"c-annotations","dependsOn":[]},
         {"ref":"c-postgres","dependsOn":[]}]}
      """;

  private static JsonNode document(String json) {
    try {
      return JSON.readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static ParsedSbom parse(String json) {
    return CycloneDxParser.parse(document(json));
  }

  private static Optional<ParsedSbom.Component> named(ParsedSbom parsed, String name) {
    return parsed.components().stream().filter(component -> name.equals(component.name())).findFirst();
  }

  @Test
  void theRootIsTheMetadataComponentAndItIsNotItselfAComponentRow() {
    ParsedSbom parsed = parse(RELEASE);

    assertEquals("self", parsed.rootRef());
    assertEquals(3, parsed.components().size());
    assertTrue(
        named(parsed, "eu.wohlben.qits:qits-eventstream").isEmpty(),
        "the artifact is the mt_artifact row, never one of its own components");
    assertTrue(parsed.problems().isEmpty(), parsed.problems().toString());
  }

  /** THE CLAIM: direct is the ROOT's own dependsOn list and nothing else. */
  @Test
  void directIsTheRootsOwnDependsOnListAndEverythingElseIsTransitive() {
    ParsedSbom parsed = parse(RELEASE);

    assertTrue(named(parsed, "com.fasterxml.jackson.core:jackson-databind").orElseThrow().direct());
    assertTrue(named(parsed, "org.postgresql:postgresql").orElseThrow().direct());
    assertFalse(
        named(parsed, "com.fasterxml.jackson.core:jackson-annotations").orElseThrow().direct(),
        "nothing declares it; it arrived behind jackson-databind");
  }

  @Test
  void everyPurlIsReadIntoTheSpellingMtPinUsesAndTheStringIsKept() {
    ParsedSbom parsed = parse(RELEASE);
    ParsedSbom.Component databind =
        named(parsed, "com.fasterxml.jackson.core:jackson-databind").orElseThrow();

    assertEquals(Ecosystem.MAVEN, databind.ecosystem());
    assertEquals("com.fasterxml.jackson.core:jackson-databind", databind.name());
    assertEquals("2.18.2", databind.version());
    assertEquals(
        "pkg:maven/com.fasterxml.jackson.core/jackson-databind@2.18.2",
        databind.purl(),
        "the purl is the evidence and is stored verbatim");
  }

  /** The adjacency, with the root spelled -1 because it is the artifact rather than a component. */
  @Test
  void theEdgesAreTheDocumentsAdjacencyWithTheRootAsMinusOne() {
    ParsedSbom parsed = parse(RELEASE);
    int databind = indexOf(parsed, "com.fasterxml.jackson.core:jackson-databind");
    int annotations = indexOf(parsed, "com.fasterxml.jackson.core:jackson-annotations");
    int postgres = indexOf(parsed, "org.postgresql:postgresql");

    assertTrue(parsed.edges().contains(new ParsedSbom.Edge(-1, databind)));
    assertTrue(parsed.edges().contains(new ParsedSbom.Edge(-1, postgres)));
    assertTrue(
        parsed.edges().contains(new ParsedSbom.Edge(databind, annotations)),
        "the transitive's parent is what 'via' is read from");
    assertEquals(3, parsed.edges().size());
  }

  /**
   * A {@code dependencies[]} entry naming a ref no component declares is SKIPPED and COUNTED.
   * Inventing a component for it would put a name in the graph the document never listed.
   */
  @Test
  void aDependencyEntryNamingAnUnknownRefIsSkippedAndCounted() {
    ParsedSbom parsed =
        parse(
            """
            {"bomFormat":"CycloneDX","specVersion":"1.6",
             "metadata":{"component":{"bom-ref":"self","name":"thing","version":"1.0.0"}},
             "components":[
               {"bom-ref":"c-one","name":"one","version":"1.0.0","purl":"pkg:npm/one@1.0.0"}],
             "dependencies":[
               {"ref":"self","dependsOn":["c-one","c-ghost"]},
               {"ref":"c-ghost","dependsOn":["c-one"]}]}
            """);

    assertEquals(1, parsed.components().size());
    assertEquals(1, parsed.edges().size(), "only the edge to the component that exists");
    assertEquals(new ParsedSbom.Edge(-1, 0), parsed.edges().get(0));
    assertTrue(
        parsed.problems().stream().anyMatch(problem -> problem.contains("ref no component")),
        parsed.problems().toString());
    assertTrue(
        parsed.problems().stream().anyMatch(problem -> problem.contains("dependsOn refs")),
        parsed.problems().toString());
  }

  /**
   * A component with no purl falls back to its own name and version, with a NULL ecosystem — it is
   * real, it belongs in the listing, and what it has lost is the ability to join.
   */
  @Test
  void aComponentWithNoPurlFallsBackToItsOwnNameWithNoEcosystem() {
    ParsedSbom parsed =
        parse(
            """
            {"bomFormat":"CycloneDX","specVersion":"1.4",
             "metadata":{"component":{"bom-ref":"self","name":"thing","version":"1.0.0"}},
             "components":[{"bom-ref":"c-one","name":"a-vendored-blob","version":"7"}],
             "dependencies":[{"ref":"self","dependsOn":["c-one"]}]}
            """);

    ParsedSbom.Component only = parsed.components().get(0);
    assertNull(only.ecosystem(), "a null ecosystem is how 'never matched' is spelled");
    assertNull(only.purl());
    assertEquals("a-vendored-blob", only.name());
    assertEquals("7", only.version());
    assertTrue(only.direct());
  }

  /** And a purl of a type this service does not inventory reads the same way — kept, not dropped. */
  @Test
  void anUnmappablePurlIsKeptWithANullEcosystemAndTheStringSurvives() {
    ParsedSbom parsed =
        parse(
            """
            {"bomFormat":"CycloneDX","specVersion":"1.5",
             "metadata":{"component":{"bom-ref":"self","name":"thing","version":"1.0.0"}},
             "components":[{"bom-ref":"c-go","name":"cobra","version":"1.8.0",
                            "purl":"pkg:golang/github.com/spf13/cobra@1.8.0"}],
             "dependencies":[{"ref":"self","dependsOn":["c-go"]}]}
            """);

    ParsedSbom.Component only = parsed.components().get(0);
    assertNull(only.ecosystem());
    assertEquals("cobra", only.name(), "the component's own name, since the purl gave none we use");
    assertEquals(
        "pkg:golang/github.com/spf13/cobra@1.8.0",
        only.purl(),
        "the string is kept so a wrong parse is reproducible");
  }

  /** The npm world's own document, so the scoped spelling is exercised end to end. */
  @Test
  void anNpmDocumentLandsUnderTheScopedSpelling() {
    ParsedSbom parsed =
        parse(
            """
            {"bomFormat":"CycloneDX","specVersion":"1.5",
             "metadata":{"component":{"bom-ref":"self","name":"@qits/ui-components","version":"2026.8.4",
                                      "purl":"pkg:npm/%40qits%2Fui-components@2026.8.4"}},
             "components":[{"bom-ref":"c-core","name":"core","version":"21.1.0",
                            "purl":"pkg:npm/%40angular%2Fcore@21.1.0"}],
             "dependencies":[{"ref":"self","dependsOn":["c-core"]}]}
            """);

    ParsedSbom.Component only = parsed.components().get(0);
    assertEquals(Ecosystem.NPM, only.ecosystem());
    assertEquals("@angular/core", only.name());
    assertTrue(only.direct());
  }

  // --- and nothing throws -----------------------------------------------------------------------

  @Test
  void aDocumentWithNoRootReadsItsComponentsWithNothingMarkedDirect() {
    ParsedSbom parsed =
        parse(
            """
            {"bomFormat":"CycloneDX","specVersion":"1.5",
             "components":[{"bom-ref":"c-one","name":"one","version":"1.0.0","purl":"pkg:npm/one@1.0.0"}]}
            """);

    assertNull(parsed.rootRef());
    assertEquals(1, parsed.components().size());
    assertFalse(parsed.components().get(0).direct());
    assertTrue(
        parsed.problems().stream().anyMatch(problem -> problem.contains("metadata.component")),
        parsed.problems().toString());
  }

  @Test
  void aDocumentThatIsNotADocumentIsEmptyRatherThanAThrow() {
    assertEquals(List.of(), CycloneDxParser.parse(null).components());
    assertNotNull(CycloneDxParser.parse(null).problems().get(0));
    assertEquals(List.of(), parse("[]").components());
    assertEquals(List.of(), parse("{}").components());
    assertEquals(List.of(), parse("{\"components\":\"not an array\"}").components());
    assertEquals(
        List.of(),
        parse("{\"components\":[1,2,3],\"dependencies\":\"nope\"}").components(),
        "entries that are not objects are skipped, not thrown on");
  }

  /** A cycle is somebody's document and not a reason to hang or to refuse the rest of it. */
  @Test
  void aCycleInTheAdjacencyIsRecordedRatherThanRefused() {
    ParsedSbom parsed =
        parse(
            """
            {"bomFormat":"CycloneDX","specVersion":"1.5",
             "metadata":{"component":{"bom-ref":"self","name":"thing","version":"1.0.0"}},
             "components":[
               {"bom-ref":"a","name":"a","version":"1","purl":"pkg:npm/a@1"},
               {"bom-ref":"b","name":"b","version":"1","purl":"pkg:npm/b@1"}],
             "dependencies":[
               {"ref":"self","dependsOn":["a"]},
               {"ref":"a","dependsOn":["b"]},
               {"ref":"b","dependsOn":["a"]}]}
            """);

    assertEquals(2, parsed.components().size());
    assertEquals(3, parsed.edges().size());
  }

  private static int indexOf(ParsedSbom parsed, String name) {
    for (int index = 0; index < parsed.components().size(); index++) {
      if (name.equals(parsed.components().get(index).name())) {
        return index;
      }
    }
    throw new AssertionError(name + " is not among " + parsed.components());
  }
}
