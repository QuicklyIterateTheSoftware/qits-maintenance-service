package eu.wohlben.qits.maintenance.sbomcheck;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.maintenance.error.SbomCheckFailedException;
import eu.wohlben.qits.maintenance.manifest.Xml;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.peer.PeerAnswer;
import eu.wohlben.qits.maintenance.peer.PeerClient;
import eu.wohlben.qits.maintenance.peer.PeerExchange;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import org.w3c.dom.Element;

/**
 * <b>Is this released version still in qits-artifacts?</b> The SBOM check's presence probe: a
 * version the GC has collected is no longer anybody's problem, so it is never counted.
 *
 * <p><b>One LISTING per artifact, never a per-version fetch — and that is about the GC, not about
 * the request count.</b> qits-artifacts records an ACCESS on every version-addressed read, HEAD
 * included: the maven stored-file serve, the npm tarball, the OCI manifest ({@code HEAD
 * /v2/<name>/manifests/<v>}) and the daemon download all move {@code accessed_at}, which is what a
 * type's GC window ages from. A daily probe that touched each version it asked about would keep
 * alive exactly the versions this check reports, for ever — and "the GC collects it and the ticket
 * closes itself" would never happen. So every probe here is a read the store derives from its rows
 * and records nothing about (confirmed against qits-registries-javalib's MavenRoutes, NpmRoutes and
 * RegistryRoutes and qits-artifacts' DaemonRoutes / DaemonBrowseController):
 *
 * <ul>
 *   <li><b>maven</b> — {@code maven-metadata.xml}, whose {@code <versions>} is derived per request
 *       from the surviving rows ({@link PeerTarget#MAVEN_REGISTRY}). The {@code .pom} GET would
 *       touch.
 *   <li><b>npm</b> — the packument's {@code versions} object ({@link PeerTarget#NPM_REGISTRY}); only
 *       the tarball touches.
 *   <li><b>docker</b> — {@code /v2/<name>/tags/list}, paged ({@link PeerTarget#OCI_REGISTRY}); a
 *       collected version's tag row is gone from it. The manifest HEAD would touch.
 *   <li><b>daemon</b> — qits-artifacts' browse listing {@code
 *       /artifacts/api/repositories/daemons/daemons/<name>/versions} (on the bare host, {@link
 *       PeerTarget#ARTIFACTS_SBOM}); the {@code /artifacts/daemons/<name>/<version>} HEAD would touch.
 * </ul>
 *
 * <p><b>A 404 means collected; anything else that is not a listing is a FAILURE.</b> A 404 on any
 * of the four says the store holds no version of that name at all, which is the GC having taken the
 * last of them. Every other answer — a 5xx, a timeout, a body that does not parse — throws {@link
 * SbomCheckFailedException}: "could not ask" must never be read as either answer.
 */
@ApplicationScoped
public class ArtifactPresence {

  /** The daemon browse route on qits-artifacts' JAX-RS surface ({@code quarkus.rest.path}). */
  static final String DAEMON_VERSIONS = "/artifacts/api/repositories/daemons/daemons/";

  private static final int TAG_PAGE_SIZE = 1000;
  private static final int MAX_TAG_PAGES = 10;

  @Inject PeerClient peers;

  /**
   * Every version of one artifact the store still holds.
   *
   * @param type the stored wire type — maven, npm, docker or daemon
   * @return the versions present; EMPTY when the store answered 404 (nothing of that name is left)
   * @throws SbomCheckFailedException on any answer that is neither a listing nor a 404
   */
  public Set<String> versions(String type, String name) {
    if (type == null || name == null || name.isBlank()) {
      throw new SbomCheckFailedException("cannot probe an artifact with no type or name");
    }
    return switch (type) {
      case "maven" -> maven(name.trim());
      case "npm" -> npm(name.trim());
      case "docker" -> docker(name.trim());
      case Ecosystem.DAEMON_WIRE_NAME -> daemon(name.trim());
      default -> throw new SbomCheckFailedException("'" + type + "' has no presence probe");
    };
  }

  private Set<String> maven(String name) {
    int colon = name.indexOf(':');
    if (colon <= 0 || colon == name.length() - 1) {
      throw new SbomCheckFailedException("'" + name + "' is not a groupId:artifactId");
    }
    String path =
        "/"
            + name.substring(0, colon).replace('.', '/')
            + "/"
            + name.substring(colon + 1)
            + "/maven-metadata.xml";
    PeerAnswer answer = listing(PeerTarget.MAVEN_REGISTRY, path);
    Set<String> versions = new LinkedHashSet<>();
    if (answer == null) {
      return versions;
    }
    Element metadata = Xml.root(answer.body());
    Element versioning = metadata == null ? null : Xml.child(metadata, "versioning");
    Element list = versioning == null ? null : Xml.child(versioning, "versions");
    if (list == null) {
      throw new SbomCheckFailedException(
          "the maven metadata of " + name + " did not parse into a versions list");
    }
    for (Element version : Xml.children(list, "version")) {
      String value = Xml.text(version).trim();
      if (!value.isEmpty()) {
        versions.add(value);
      }
    }
    return versions;
  }

  private Set<String> npm(String name) {
    PeerAnswer answer = listing(PeerTarget.NPM_REGISTRY, "/" + name.replace("/", "%2f"));
    Set<String> versions = new LinkedHashSet<>();
    if (answer == null) {
      return versions;
    }
    JsonNode packument = answer.json();
    if (packument == null || !packument.isObject()) {
      throw new SbomCheckFailedException("the packument of " + name + " did not parse");
    }
    JsonNode listed = packument.get("versions");
    if (listed != null && listed.isObject()) {
      Iterator<String> names = listed.fieldNames();
      while (names.hasNext()) {
        versions.add(names.next());
      }
    }
    return versions;
  }

  private Set<String> docker(String name) {
    Set<String> tags = new LinkedHashSet<>();
    String last = null;
    for (int page = 0; page < MAX_TAG_PAGES; page++) {
      String path =
          "/"
              + name
              + "/tags/list?n="
              + TAG_PAGE_SIZE
              + (last == null ? "" : "&last=" + URLEncoder.encode(last, StandardCharsets.UTF_8));
      PeerAnswer answer = listing(PeerTarget.OCI_REGISTRY, path);
      if (answer == null) {
        return tags;
      }
      JsonNode body = answer.json();
      if (body == null || !body.isObject()) {
        throw new SbomCheckFailedException("the tag listing of " + name + " did not parse");
      }
      JsonNode listed = body.get("tags");
      if (listed == null || listed.isNull()) {
        // The spec allows a null tags array for a repository with none left.
        return tags;
      }
      if (!listed.isArray()) {
        throw new SbomCheckFailedException("the tag listing of " + name + " carries no tags array");
      }
      int before = tags.size();
      String lastOfPage = null;
      for (JsonNode tag : listed) {
        if (tag.isTextual()) {
          tags.add(tag.asText());
          lastOfPage = tag.asText();
        }
      }
      if (lastOfPage == null || tags.size() - before < TAG_PAGE_SIZE) {
        return tags;
      }
      last = lastOfPage;
    }
    return tags;
  }

  private Set<String> daemon(String name) {
    PeerAnswer answer =
        listing(
            PeerTarget.ARTIFACTS_SBOM,
            DAEMON_VERSIONS + URLEncoder.encode(name, StandardCharsets.UTF_8) + "/versions");
    Set<String> versions = new LinkedHashSet<>();
    if (answer == null) {
      return versions;
    }
    JsonNode body = answer.json();
    JsonNode listed = body == null ? null : body.get("versions");
    if (listed == null || !listed.isArray()) {
      throw new SbomCheckFailedException(
          "the daemon version listing of " + name + " carries no versions array");
    }
    for (JsonNode entry : listed) {
      JsonNode version = entry.get("version");
      if (version != null && version.isTextual()) {
        versions.add(version.asText());
      }
    }
    return versions;
  }

  /** The answer of one listing read: the 2xx, or null for a 404. Anything else throws. */
  private PeerAnswer listing(PeerTarget target, String path) {
    PeerExchange exchange = peers.get(target, path);
    PeerAnswer answer = exchange.answer();
    if (answer.notFound()) {
      return null;
    }
    if (!answer.ok()) {
      throw new SbomCheckFailedException(
          "qits-artifacts could not be asked what it holds: "
              + exchange.call().url()
              + " answered "
              + answer.failure());
    }
    return answer;
  }
}
