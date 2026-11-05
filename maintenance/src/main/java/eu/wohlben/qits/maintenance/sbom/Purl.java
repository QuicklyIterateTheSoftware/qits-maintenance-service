package eu.wohlben.qits.maintenance.sbom;

import eu.wohlben.qits.bootstrap.graph.Ecosystem;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * A package url, read into the three values this service can do anything with.
 *
 * <p><b>The name it produces is {@code mt_pin}'s spelling and that is the entire point.</b> A
 * component is only useful here if it can be joined to the inventory: {@code
 * pkg:maven/eu.wohlben.qits/qits-eventstream@2026.9.1} has to become {@code
 * eu.wohlben.qits:qits-eventstream}, because that is what a pom parser records and what {@code
 * mt_latest} is keyed by. A purl kept in its own spelling would be a column that joins to nothing.
 *
 * <p><b>An unknown type is EMPTY rather than a guess.</b> {@code pkg:golang/…} and {@code
 * pkg:generic/…} are real entries in real documents; the component row is still written, with a
 * null ecosystem, and it is shown and never matched. Inventing a mapping would put a name into a
 * join key that means something else.
 *
 * <p><b>Qualifiers and the subpath are stripped before anything else.</b> {@code ?type=jar} and
 * {@code ?repository_url=…} are how a build records where a component came from — they are not part
 * of its identity, and a name carrying one would match no pin.
 *
 * <p><b>The percent-decoding covers the WHOLE name.</b> npm scopes arrive spelled both ways in the
 * wild — {@code pkg:npm/%40qits%2Fui-components@1.0.0} and {@code pkg:npm/@qits/ui-components@1.0.0}
 * — and the two are one package. Decoding after the split, over the joined name, gets both.
 *
 * @param ecosystem which world it belongs to
 * @param name the dependency in that world's own spelling, as {@code mt_pin} records it
 * @param version the pinned version, or null when the purl carries none
 */
public record Purl(Ecosystem ecosystem, String name, String version) {

  private static final String SCHEME = "pkg:";

  public Purl {
    if (ecosystem == null || name == null || name.isBlank()) {
      throw new IllegalArgumentException("a parsed purl carries an ecosystem and a name");
    }
  }

  /**
   * One purl, or empty when there is nothing here this service can use.
   *
   * <p>Empty means one of three things and the caller treats them alike: the string is not a purl,
   * its type is one this platform does not inventory, or it names no package at all. Each leaves a
   * component row with a null ecosystem — stored, shown, never matched.
   */
  public static Optional<Purl> parse(String purl) {
    if (purl == null) {
      return Optional.empty();
    }
    String value = purl.trim();
    if (!value.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
      return Optional.empty();
    }
    value = value.substring(SCHEME.length());

    // The subpath and the qualifiers first, and in that order: a `#` may follow a `?`, and both are
    // provenance rather than identity.
    int subpath = value.indexOf('#');
    if (subpath >= 0) {
      value = value.substring(0, subpath);
    }
    int qualifiers = value.indexOf('?');
    if (qualifiers >= 0) {
      value = value.substring(0, qualifiers);
    }

    int slash = value.indexOf('/');
    if (slash <= 0) {
      return Optional.empty();
    }
    String type = value.substring(0, slash).toLowerCase(Locale.ROOT);
    String rest = value.substring(slash + 1);
    if (rest.isBlank()) {
      return Optional.empty();
    }

    // THE LAST at-sign, not the first: an npm scope starts with one, so `@scope/name@1.0.0` has two
    // and only the second is a version separator.
    String coordinate = rest;
    String version = null;
    int at = rest.lastIndexOf('@');
    if (at > 0) {
      coordinate = rest.substring(0, at);
      String tail = rest.substring(at + 1);
      version = tail.isBlank() ? null : decode(tail);
    }
    coordinate = decode(coordinate);
    if (coordinate.isBlank()) {
      return Optional.empty();
    }

    return switch (type) {
      case "maven" -> maven(coordinate, version);
      case "npm" -> Optional.of(new Purl(Ecosystem.NPM, coordinate, version));
      // `oci` is what a container build usually emits and `docker` is what the spec's own examples
      // use; both name the same thing on this platform.
      case "docker", "oci" -> docker(coordinate, version);
      default -> Optional.empty();
    };
  }

  /** {@code pkg:maven/<groupId>/<artifactId>@<version>} — the coordinate mt_pin joins on. */
  private static Optional<Purl> maven(String coordinate, String version) {
    int slash = coordinate.lastIndexOf('/');
    if (slash <= 0 || slash == coordinate.length() - 1) {
      // No namespace: a maven artifact with no groupId is not a coordinate anything can look up.
      return Optional.empty();
    }
    String groupId = coordinate.substring(0, slash);
    String artifactId = coordinate.substring(slash + 1);
    return Optional.of(new Purl(Ecosystem.MAVEN, groupId + ":" + artifactId, version));
  }

  /**
   * An image, without its tag or digest.
   *
   * <p><b>A digest is not a version and never becomes one.</b> {@code DockerParser} refuses a
   * digest-pinned {@code FROM} for the same reason: {@code sha256:…} has no order, so a component
   * pinned by one carries a name and no version rather than a version nothing can rank.
   */
  private static Optional<Purl> docker(String coordinate, String version) {
    String name = coordinate;
    String tag = version;
    if (tag != null && tag.startsWith("sha256:")) {
      tag = null;
    }
    int digest = name.indexOf('@');
    if (digest > 0) {
      name = name.substring(0, digest);
    }
    // A tag written into the name rather than after an at-sign, which some producers do.
    int colon = name.lastIndexOf(':');
    int lastSlash = name.lastIndexOf('/');
    if (colon > lastSlash && colon > 0) {
      if (tag == null) {
        tag = name.substring(colon + 1);
      }
      name = name.substring(0, colon);
    }
    if (name.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new Purl(Ecosystem.DOCKER, name, tag == null || tag.isBlank() ? null : tag));
  }

  /**
   * Percent-decoding, by hand and never throwing.
   *
   * <p><b>Not {@code URLDecoder}</b>, which is a FORM decoder: it turns {@code +} into a space, and
   * {@code +} is a real character in a version — {@code 1.0.0+build.7} would come back as {@code
   * 1.0.0 build.7} and match nothing. A purl is percent-encoding and nothing else.
   *
   * <p>A malformed escape leaves the text exactly as the document wrote it, which is the same
   * stance the rest of this package takes: what could not be read is kept rather than corrected.
   */
  private static String decode(String value) {
    if (value.indexOf('%') < 0) {
      return value;
    }
    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(value.length());
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character == '%' && index + 2 < value.length()) {
        int high = Character.digit(value.charAt(index + 1), 16);
        int low = Character.digit(value.charAt(index + 2), 16);
        if (high >= 0 && low >= 0) {
          bytes.write((high << 4) + low);
          index += 2;
          continue;
        }
      }
      // Anything that is not a well-formed escape is written through as its own utf-8 bytes.
      byte[] raw = String.valueOf(character).getBytes(StandardCharsets.UTF_8);
      bytes.write(raw, 0, raw.length);
    }
    return bytes.toString(StandardCharsets.UTF_8);
  }
}
