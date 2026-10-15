package eu.wohlben.qits.maintenance.latest;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.maintenance.manifest.Xml;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.peer.PeerAnswer;
import eu.wohlben.qits.maintenance.peer.PeerClient;
import eu.wohlben.qits.maintenance.peer.PeerExchange;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.w3c.dom.Element;

/**
 * "What is the newest version of this dependency" — one question, five addresses.
 *
 * <p><b>Which address is decided by the pin's KIND, not by the ecosystem alone.</b> An internal
 * maven artifact lives in qits-artifacts' hosted repository and an external one is read through
 * qits-platform-mirror's Central cache; asking the wrong one for either answers 404 and reports no
 * latest at all, which reads exactly like "up to date".
 *
 * <p><b>Docker is internal-only in v1.</b> Ordering tags across vendors — a temurin tag beside an
 * alpine one — is a later decision, and the mirror's OCI proxies would answer with an upstream's
 * whole tag history with no rule to rank it by. An external image pin is recorded in the inventory
 * and never looked up.
 *
 * <p><b>Nothing here throws.</b> A registry that is down costs one row's {@code error} column.
 */
@ApplicationScoped
public class LatestResolver {

  private static final Logger LOG = Logger.getLogger(LatestResolver.class);

  /** How many pages of an OCI tag listing are followed. A page is 1000 tags; ten is far past any
   * image on this platform, and a bound is what keeps one runaway repository from being an
   * unbounded read. */
  private static final int MAX_TAG_PAGES = 10;

  private static final int TAG_PAGE_SIZE = 1000;

  /**
   * What a dependency name may contain before it is allowed to reach a URL.
   *
   * <p>Every character here occurs in a real name: the colon in {@code groupId:artifactId} and in a
   * registry host's port, the at-sign and the slash in {@code @scope/name}, the slashes in an image
   * path. Anything else is refused as an error ROW rather than sent — the platform's first live
   * scan died on {@code ${project.groupId}} reaching {@code URI.create}, and a name that never
   * became a name must never become a request.
   */
  private static final java.util.regex.Pattern ADDRESSABLE =
      java.util.regex.Pattern.compile("[A-Za-z0-9._:@/-]+");

  @Inject PeerClient peers;

  /**
   * The newest version of one dependency, or why there is none.
   *
   * <p><b>NOTHING THROWS OUT OF HERE.</b> A scan asks this hundreds of times and one bad answer
   * must cost one row, not the run — which is exactly what the first live scan over 49 repositories
   * did when an unresolved expression reached {@code URI.create}. A malformed url, a timeout, a
   * refusal, a document that does not parse: each becomes this dependency's {@code error} column,
   * where the UI shows it beside the pin.
   */
  public LatestLookup resolve(Ecosystem ecosystem, PinKind kind, String name) {
    if (name == null || name.isBlank() || !ADDRESSABLE.matcher(name).matches()) {
      return LatestLookup.failed(
          null, "'" + name + "' is not a name this service can address; nothing was asked");
    }
    try {
      return switch (ecosystem) {
        case MAVEN -> maven(kind, name);
        case NPM -> npm(kind, name);
        case DOCKER -> docker(kind, name);
        // Unreachable through a scan, which asks `resolvable` first. It is an answer rather than a
        // throw because this method's whole contract is that nothing escapes it: a caller that
        // forgets the gate gets one row's error column, not a dead scan.
        case GITLINK ->
            LatestLookup.failed(
                null, "a gitlink has no registry; its latest arrives as SCMRelease on the bus");
      };
    } catch (RuntimeException e) {
      LOG.warnf("The latest of %s %s could not be looked up: %s", ecosystem.wireName(), name, e.toString());
      return LatestLookup.failed(null, "the lookup failed: " + e);
    }
  }

  /**
   * Whether this service looks a pin of that shape up at all.
   *
   * <p>A REACTOR or UNRESOLVED pin is never looked up: the first is this repository's own artifact
   * and the second has no name to address. External images are not ordered in v1.
   *
   * <p><b>A GITLINK is never looked up, and the daily scan therefore neither refreshes nor CLEARS
   * one.</b> There is no registry to ask — a submodule is a git repository and nothing publishes
   * it — so its {@code mt_latest} row is written by one rule and never by this resolver: {@code
   * latest/MainHeadLatest}, the release the repository's {@code main} head carries (qits-1257),
   * applied on a push to main, on every scan at main and once after every boot. A scan will never
   * write an {@code error} column over one, which is what keeps that row from being reset to "we
   * could not find out" every night.
   */
  public boolean resolvable(Ecosystem ecosystem, PinKind kind) {
    if (!kind.actionable()) {
      return false;
    }
    if (ecosystem == Ecosystem.GITLINK) {
      return false;
    }
    return ecosystem != Ecosystem.DOCKER || kind == PinKind.INTERNAL;
  }

  /**
   * Every version a registry lists for one dependency — maven only, the one ecosystem whose
   * properties are shared between artifacts.
   *
   * <p>Asked by the bump planners for a {@code ${property}} more than one artifact of the same
   * manifest reads (qits-1206): moving that property moves every one of them, so the target has to
   * be a version that EACH of them was published at, and only the full list says that. Asked only
   * for such a property, never per pin — a property one artifact reads is decided by its latest
   * row exactly as before.
   *
   * <p><b>Nothing throws out of here either</b>, for the reason {@link #resolve} gives.
   */
  public PublishedLookup published(Ecosystem ecosystem, PinKind kind, String name) {
    if (ecosystem != Ecosystem.MAVEN) {
      return PublishedLookup.failed(
          null, "only maven versions are listed here; " + ecosystem.wireName() + " was asked");
    }
    if (name == null || name.isBlank() || !ADDRESSABLE.matcher(name).matches()) {
      return PublishedLookup.failed(
          null, "'" + name + "' is not a name this service can address; nothing was asked");
    }
    try {
      return publishedMaven(kind, name);
    } catch (RuntimeException e) {
      LOG.warnf("The published versions of %s could not be read: %s", name, e.toString());
      return PublishedLookup.failed(null, "the lookup failed: " + e);
    }
  }

  /**
   * {@code maven-metadata.xml}, and the versions list rather than the {@code <latest>} element.
   *
   * <p>The document's own {@code <latest>} is whatever the last deploy wrote and can name a
   * SNAPSHOT; {@code <release>} is closer but is absent on a repository that has only ever held
   * prereleases. Ranking the published list is one rule for both, and it is the same rule a
   * resolver would apply.
   */
  private LatestLookup maven(PinKind kind, String name) {
    PublishedLookup published = publishedMaven(kind, name);
    if (!published.ok()) {
      return LatestLookup.failed(published.sourceUrl(), published.error());
    }
    return pick(Ecosystem.MAVEN, published.versions(), published.sourceUrl());
  }

  /**
   * The {@code <versions>} of one artifact's {@code maven-metadata.xml}, from qits-artifacts for an
   * INTERNAL artifact and through the mirror for an external one. The one reader of that document,
   * shared by {@link #maven} and {@link #published}.
   */
  private PublishedLookup publishedMaven(PinKind kind, String name) {
    int colon = name.indexOf(':');
    if (colon <= 0 || colon == name.length() - 1) {
      return PublishedLookup.failed(null, "'" + name + "' is not a groupId:artifactId");
    }
    String groupPath = name.substring(0, colon).replace('.', '/');
    String artifactId = name.substring(colon + 1);
    PeerTarget target = kind == PinKind.INTERNAL ? PeerTarget.MAVEN_REGISTRY : PeerTarget.MAVEN_MIRROR;
    String path = "/" + groupPath + "/" + artifactId + "/maven-metadata.xml";
    PeerExchange exchange = peers.get(target, path);
    String url = exchange.call().url();
    PeerAnswer answer = exchange.answer();
    if (!answer.ok()) {
      return PublishedLookup.failed(url, answer.failure());
    }
    Element metadata = Xml.root(answer.body());
    if (metadata == null) {
      return PublishedLookup.failed(url, "the metadata document did not parse");
    }
    Element versioning = Xml.child(metadata, "versioning");
    Element versions = versioning == null ? null : Xml.child(versioning, "versions");
    if (versions == null) {
      return PublishedLookup.failed(url, "the metadata document carries no versions");
    }
    List<String> published = new ArrayList<>();
    for (Element version : Xml.children(versions, "version")) {
      String value = Xml.text(version).trim();
      if (!value.isEmpty()) {
        published.add(value);
      }
    }
    return PublishedLookup.found(published, url);
  }

  /**
   * The packument, and the versions it lists rather than {@code dist-tags.latest} alone.
   *
   * <p>{@code dist-tags.latest} is a POINTER a publisher moves and can point at anything — a
   * patch of an old line, deliberately. The version set is the fact; the tag is included in the
   * candidates so a registry that lists nothing useful still answers.
   */
  private LatestLookup npm(PinKind kind, String name) {
    PeerTarget target = kind == PinKind.INTERNAL ? PeerTarget.NPM_REGISTRY : PeerTarget.NPM_MIRROR;
    PeerExchange exchange = peers.get(target, "/" + encodePackage(name));
    String url = exchange.call().url();
    PeerAnswer answer = exchange.answer();
    if (!answer.ok()) {
      return LatestLookup.failed(url, answer.failure());
    }
    JsonNode packument = answer.json();
    if (packument == null || !packument.isObject()) {
      return LatestLookup.failed(url, "the packument did not parse");
    }
    Set<String> published = new LinkedHashSet<>();
    JsonNode versions = packument.get("versions");
    if (versions != null && versions.isObject()) {
      Iterator<String> names = versions.fieldNames();
      while (names.hasNext()) {
        published.add(names.next());
      }
    }
    JsonNode distTags = packument.get("dist-tags");
    if (distTags != null && distTags.isObject()) {
      JsonNode latest = distTags.get("latest");
      if (latest != null && latest.isTextual()) {
        published.add(latest.asText());
      }
    }
    if (published.isEmpty()) {
      return LatestLookup.failed(url, "the packument lists no versions");
    }
    return pick(Ecosystem.NPM, published, url);
  }

  /** The OCI tag listing, paged. */
  private LatestLookup docker(PinKind kind, String name) {
    if (kind != PinKind.INTERNAL) {
      return LatestLookup.failed(
          null, "external base images are not ordered in v1; only " + "internal images are");
    }
    Set<String> tags = new LinkedHashSet<>();
    String last = null;
    String url = null;
    for (int page = 0; page < MAX_TAG_PAGES; page++) {
      String path =
          "/" + name + "/tags/list?n=" + TAG_PAGE_SIZE
              + (last == null ? "" : "&last=" + URLEncoder.encode(last, StandardCharsets.UTF_8));
      PeerExchange exchange = peers.get(PeerTarget.OCI_REGISTRY, path);
      url = exchange.call().url();
      PeerAnswer answer = exchange.answer();
      if (!answer.ok()) {
        return LatestLookup.failed(url, answer.failure());
      }
      JsonNode body = answer.json();
      if (body == null || !body.hasNonNull("tags") || !body.get("tags").isArray()) {
        return LatestLookup.failed(url, "the tag listing carries no tags array");
      }
      int before = tags.size();
      String lastOfPage = null;
      for (JsonNode tag : body.get("tags")) {
        if (tag.isTextual()) {
          tags.add(tag.asText());
          lastOfPage = tag.asText();
        }
      }
      // A short page is the end, and a page that added nothing new is a registry that is not
      // paging the way the header says — either way, stop rather than ask forever.
      if (tags.size() == before || lastOfPage == null || tags.size() - before < TAG_PAGE_SIZE) {
        break;
      }
      last = lastOfPage;
    }
    if (tags.isEmpty()) {
      return LatestLookup.failed(url, "the image has no tags");
    }
    return pick(Ecosystem.DOCKER, tags, url);
  }

  private static LatestLookup pick(Ecosystem ecosystem, java.util.Collection<String> published, String url) {
    Optional<String> highest = VersionOrder.highest(ecosystem, published);
    return highest
        .map(latest -> LatestLookup.found(latest, url))
        .orElseGet(() -> LatestLookup.failed(url, "nothing published there is a readable version"));
  }

  /**
   * A scoped package's slash is encoded.
   *
   * <p>The registry route takes {@code @scope%2fname} as ONE path segment; an unencoded slash would
   * be read as a repository plus a package and answer 404.
   */
  static String encodePackage(String name) {
    return name.replace("/", "%2f");
  }
}
