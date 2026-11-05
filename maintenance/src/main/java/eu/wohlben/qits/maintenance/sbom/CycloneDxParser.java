package eu.wohlben.qits.maintenance.sbom;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.bootstrap.graph.Ecosystem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A CycloneDX document into the graph this service stores.
 *
 * <p><b>Three things are read and nothing else</b>: {@code metadata.component} (which component IS
 * this artifact), {@code components[]} (what it contains) and {@code dependencies[]} (who pulled in
 * whom). Every other field a producer writes — licences, hashes, evidence, the tool that made the
 * document — is a real fact about the release and not one this inventory answers questions about.
 *
 * <p><b>DIRECT is the root's own {@code dependsOn} list and nothing else.</b> That is the whole
 * value of reading the document at all: a direct component is something a manifest could hold a
 * line for, and a transitive one is something no line anywhere names. A parser that called every
 * listed component direct would produce an inventory in which nothing was ever transitive.
 *
 * <p><b>Nothing here throws, at any spelling of "weird".</b> These documents are produced by other
 * people's build plugins across four package worlds and three spec versions; a parser that refused
 * one would put an artifact permanently in FAILED for a field this service does not read. Every
 * refusal is a line in {@link ParsedSbom#problems} beside whatever WAS readable — including a
 * {@code dependencies[]} entry whose {@code ref} names no component, which is skipped and counted
 * rather than invented.
 *
 * <p><b>An absent or unmappable purl falls back to the component's own {@code name} and {@code
 * version}</b>, with a null ecosystem. The component is real and belongs in the listing; what it
 * has lost is the ability to JOIN, and a null ecosystem is exactly how that is spelled.
 */
public final class CycloneDxParser {

  private CycloneDxParser() {}

  /** The document, as much of it as reads. Never throws and never returns null. */
  public static ParsedSbom parse(JsonNode document) {
    if (document == null || !document.isObject()) {
      return ParsedSbom.empty("the sbom document is not a json object");
    }
    List<String> problems = new ArrayList<>();

    String rootRef = text(path(document, "metadata", "component"), "bom-ref");
    if (rootRef == null) {
      // Not fatal: without it nothing is direct, which is a poorer reading of a real document
      // rather than a reason to record none of it.
      problems.add("the document names no metadata.component bom-ref; nothing is marked direct");
    }

    Set<String> directRefs = directRefs(document, rootRef);

    JsonNode components = document.get("components");
    if (components == null || !components.isArray()) {
      problems.add("the document lists no components array");
      return new ParsedSbom(rootRef, List.of(), List.of(), List.copyOf(problems));
    }

    List<ParsedSbom.Component> parsed = new ArrayList<>();
    // The document's own ref onto the position it landed at. A duplicate ref keeps the FIRST: the
    // adjacency refers to one component and there is no rule that picks a later homonym.
    Map<String, Integer> byRef = new LinkedHashMap<>();
    for (JsonNode component : components) {
      if (!component.isObject()) {
        problems.add("a components[] entry is not an object and was skipped");
        continue;
      }
      String bomRef = text(component, "bom-ref");
      String purl = text(component, "purl");
      Optional<Purl> read = Purl.parse(purl);
      String name;
      String version;
      Ecosystem ecosystem;
      if (read.isPresent()) {
        ecosystem = read.get().ecosystem();
        name = read.get().name();
        version = read.get().version() != null ? read.get().version() : text(component, "version");
      } else {
        // Stored, shown, never matched. See MtArtifactComponent.
        ecosystem = null;
        name = text(component, "name");
        version = text(component, "version");
      }
      if (name == null) {
        problems.add("a components[] entry carries neither a readable purl nor a name");
        continue;
      }
      int index = parsed.size();
      parsed.add(
          new ParsedSbom.Component(
              bomRef, purl, ecosystem, name, version, bomRef != null && directRefs.contains(bomRef)));
      if (bomRef != null) {
        byRef.putIfAbsent(bomRef, index);
      }
    }

    List<ParsedSbom.Edge> edges = edges(document, rootRef, byRef, problems);
    return new ParsedSbom(rootRef, List.copyOf(parsed), List.copyOf(edges), List.copyOf(problems));
  }

  /**
   * The root's own {@code dependsOn} list — the direct set.
   *
   * <p>Read before the components are, because {@code direct} is a property of each one and a
   * second pass over the list to set it would be a second place for the rule to live.
   */
  private static Set<String> directRefs(JsonNode document, String rootRef) {
    Set<String> direct = new LinkedHashSet<>();
    if (rootRef == null) {
      return direct;
    }
    JsonNode dependencies = document.get("dependencies");
    if (dependencies == null || !dependencies.isArray()) {
      return direct;
    }
    for (JsonNode entry : dependencies) {
      if (entry.isObject() && rootRef.equals(text(entry, "ref"))) {
        for (String child : dependsOn(entry)) {
          direct.add(child);
        }
      }
    }
    return direct;
  }

  /** The adjacency, by component index, with the root spelled {@code -1}. */
  private static List<ParsedSbom.Edge> edges(
      JsonNode document, String rootRef, Map<String, Integer> byRef, List<String> problems) {
    List<ParsedSbom.Edge> edges = new ArrayList<>();
    JsonNode dependencies = document.get("dependencies");
    if (dependencies == null || !dependencies.isArray()) {
      return edges;
    }
    int unknownParents = 0;
    int unknownChildren = 0;
    Set<String> seen = new LinkedHashSet<>();
    for (JsonNode entry : dependencies) {
      if (!entry.isObject()) {
        continue;
      }
      String ref = text(entry, "ref");
      if (ref == null) {
        unknownParents++;
        continue;
      }
      Integer parent;
      if (ref.equals(rootRef)) {
        parent = -1;
      } else {
        parent = byRef.get(ref);
        if (parent == null) {
          // A dependencies[] entry whose ref names no component[] entry. It is SKIPPED and counted:
          // inventing a component for it would put a name in the graph the document never listed.
          unknownParents++;
          continue;
        }
      }
      for (String child : dependsOn(entry)) {
        Integer target = byRef.get(child);
        if (target == null) {
          unknownChildren++;
          continue;
        }
        // A document may repeat an edge across two entries; the graph is a set.
        if (seen.add(parent + ">" + target)) {
          edges.add(new ParsedSbom.Edge(parent, target));
        }
      }
    }
    if (unknownParents > 0) {
      problems.add(unknownParents + " dependencies[] entries name a ref no component declares");
    }
    if (unknownChildren > 0) {
      problems.add(unknownChildren + " dependsOn refs name no component and were skipped");
    }
    return edges;
  }

  private static List<String> dependsOn(JsonNode entry) {
    JsonNode list = entry.get("dependsOn");
    if (list == null || !list.isArray()) {
      return List.of();
    }
    List<String> refs = new ArrayList<>();
    for (JsonNode child : list) {
      if (child.isTextual() && !child.asText().isBlank()) {
        refs.add(child.asText());
      }
    }
    return refs;
  }

  private static JsonNode path(JsonNode node, String... names) {
    JsonNode current = node;
    for (String name : names) {
      if (current == null) {
        return null;
      }
      current = current.get(name);
    }
    return current;
  }

  /** One string field, trimmed, or null when it is absent, blank or not a string. */
  private static String text(JsonNode node, String field) {
    if (node == null || !node.isObject()) {
      return null;
    }
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) {
      return null;
    }
    String text = value.asText().trim();
    return text.isEmpty() ? null : text;
  }
}
