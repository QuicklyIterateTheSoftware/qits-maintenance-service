package eu.wohlben.qits.maintenance.manifest;

import eu.wohlben.qits.maintenance.model.Ecosystem;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reads the {@code FROM} lines of one Dockerfile — and the {@code ARG} defaults a
 * {@code FROM ${…}} resolves from.
 *
 * <p><b>A FROM line's location is a LINE NUMBER</b>, because that is the only address a FROM has.
 * There is no key, no element and no property: a file with two stages built from the same image has
 * two lines, and each is bumped on its own.
 *
 * <p><b>An ARG line's location is {@code arg:<name>}, and that is the better address of the two.</b>
 * Three repositories on this platform pin their base image in an {@code ARG} default rather than on
 * the FROM itself — {@code ARG WORKSPACE_IMAGE=…/qits/workspace:2026.902.181302} followed by
 * {@code FROM ${WORKSPACE_IMAGE}} — because a build argument is what {@code docker build
 * --build-arg} can override and a literal FROM is not. The build argument's NAME is stable across
 * every edit made above it, where a line number goes stale on the first commit that adds a comment.
 *
 * <p><b>An image is recorded WITHOUT its registry host, whichever instruction wrote it</b>, and that
 * is what lets an ARG pin be the same kind of name a FROM pin is. An ARG default has to be an
 * address a {@code docker build} can pull on its own — a bare {@code qits/workspace} would resolve
 * to Docker Hub — so it carries the host by construction, while a FROM inside qits-ci resolves
 * through a configured mirror and does not. The two spell one image, and one image is one row:
 * {@code qits/workspace}, which is the spelling {@code mt_latest} is keyed by (qits-ci's
 * {@code SoftwareRelease} publishes {@code qits/workspace}) and the spelling the OCI tag listing is
 * addressed with. {@code bus/SoftwareReleaseListener} states that contract and names this parser as
 * the side that keeps it; see {@link #unqualified}.
 *
 * <p><b>A digest pin is not read.</b> {@code FROM image@sha256:…} names a content address rather
 * than a version, and there is no ordering over content addresses — a "newer" one is a decision
 * somebody else made.
 *
 * <p><b>A tagless {@code FROM} is not read either.</b> It means {@code :latest}, which is a moving
 * reference; there is nothing to compare and nothing to write.
 *
 * <p><b>An ARG value is held to MORE than a FROM's word, because an ARG says less.</b> A FROM line
 * declares outright that its word is an image; an ARG declares only a default, and the Dockerfiles
 * on this platform put ports, download URLs and plain version numbers in them beside the one base
 * image. So a value becomes a pin only when nothing else can explain it — see {@link #argument}.
 *
 * <p><b>Which images survive is not decided here.</b> This parser reports every reference it can
 * read and the scanner keeps only the ones the internal image prefixes claim — v1 does not order
 * external base tags, because tag order across vendors is a later decision.
 */
public final class DockerParser {

  /** What a location of an ARG pin is spelled with. The applier anchors the edit on it. */
  public static final String ARG_LOCATION = "arg:";

  /**
   * A build argument's name, which docker holds to the same shape an environment variable has. A
   * value under any other name would not build, and the name reaches an {@code awk -v} in the bump
   * step — so a line this does not recognise is not a pin rather than a pin nothing can apply.
   */
  private static final Pattern ARG_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  private DockerParser() {}

  /**
   * The image pins of one Dockerfile.
   *
   * @param manifestPath the file's repository-relative path
   * @param dockerfile the file
   */
  public static List<ParsedPin> parse(String manifestPath, String dockerfile) {
    if (dockerfile == null || dockerfile.isBlank()) {
      return List.of();
    }
    List<ParsedPin> pins = new ArrayList<>();
    String[] lines = dockerfile.split("\r?\n", -1);
    for (int index = 0; index < lines.length; index++) {
      String line = lines[index].trim();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      String[] words = line.split("\\s+");
      switch (words[0].toUpperCase(Locale.ROOT)) {
        case "FROM" -> from(pins, manifestPath, words, index + 1);
        case "ARG" -> arg(pins, manifestPath, words);
        default -> {
          // Every other instruction. A Dockerfile is mostly RUN and COPY.
        }
      }
    }
    return List.copyOf(pins);
  }

  /** One FROM line, addressed by the line it sits on. */
  private static void from(List<ParsedPin> pins, String manifestPath, String[] words, int line) {
    String reference = reference(words);
    if (reference == null) {
      return;
    }
    ParsedPin pin = image(reference, manifestPath, "line:" + line);
    if (pin != null) {
      pins.add(pin);
    }
  }

  /**
   * One ARG line, addressed by the argument's name.
   *
   * <p>Every {@code <name>=<value>} on the line is looked at: buildkit takes several per
   * instruction, and a {@code ARG NAME} with no value declares an argument rather than defaulting
   * one — there is nothing pinned in it to read.
   */
  private static void arg(List<ParsedPin> pins, String manifestPath, String[] words) {
    for (int i = 1; i < words.length; i++) {
      int equals = words[i].indexOf('=');
      if (equals <= 0) {
        continue;
      }
      String name = words[i].substring(0, equals);
      if (!ARG_NAME.matcher(name).matches()) {
        continue;
      }
      ParsedPin pin = argument(words[i].substring(equals + 1), manifestPath, ARG_LOCATION + name);
      if (pin != null) {
        pins.add(pin);
      }
    }
  }

  /**
   * An ARG default that is an image reference, or null for one that is anything else.
   *
   * <p><b>Two exclusions beyond a FROM's, and each has a live case behind it.</b> A value carrying
   * {@code ://} is a URL — {@code ARG OPENVSCODE_URL=https://…/openvscode.tar.gz} — and a scheme's
   * own colon is not a tag. And the reference has to carry a {@code /} before its tag, which is
   * what keeps {@code ARG OPENVSCODE_VERSION=1.109.5} and {@code ARG PORT=8080:3000} out: every
   * image this platform builds against is written {@code [host/]namespace/name}, and a
   * single-segment value is a Docker Hub official image nobody here pins in an ARG.
   */
  private static ParsedPin argument(String value, String manifestPath, String location) {
    if (value.contains("://") || !value.contains("/")) {
      return null;
    }
    return image(value, manifestPath, location);
  }

  /**
   * One image reference as a pin, or null when it is not a version this service can move.
   *
   * <p>The exclusions are the same whichever instruction the reference was written on, because they
   * are facts about the reference rather than about the line.
   */
  private static ParsedPin image(String reference, String manifestPath, String location) {
    if (reference.contains("@")) {
      return null;
    }
    int tagSeparator = tagSeparator(reference);
    if (tagSeparator < 0) {
      return null;
    }
    String image = unqualified(reference.substring(0, tagSeparator));
    String tag = reference.substring(tagSeparator + 1);
    // A build-arg reference resolves at build time, not here; there is no version to record. The
    // registry HOST is stripped first, because a host-only build-arg is the platform's domain
    // (${QITS_DOMAIN}), which the record drops anyway — only a $ surviving past the host rejects.
    if (image.contains("$") || tag.contains("$")) {
      return null;
    }
    if (image.isEmpty() || tag.isEmpty()) {
      return null;
    }
    return ParsedPin.of(Ecosystem.DOCKER, manifestPath, image, tag, null, location);
  }

  /**
   * The image reference of a FROM line: the first word that is neither the verb nor a flag, and the
   * stage alias after {@code AS} is dropped.
   */
  private static String reference(String[] words) {
    for (int i = 1; i < words.length; i++) {
      String word = words[i];
      if (word.startsWith("--")) {
        continue;
      }
      return word;
    }
    return null;
  }

  /**
   * Where the tag starts, or -1 when there is none.
   *
   * <p>The colon has to be AFTER the last slash: {@code mirror.dev.localhost:8080/quay/image} has a
   * colon in its registry host, and reading that as a tag would name an image nobody wrote.
   */
  private static int tagSeparator(String reference) {
    int lastSlash = reference.lastIndexOf('/');
    int colon = reference.lastIndexOf(':');
    return colon > lastSlash ? colon : -1;
  }

  /**
   * An image name without the registry host it was written against.
   *
   * <p><b>Docker's own test decides what a host is</b>, and it is a test on the FIRST path segment
   * alone: a segment carrying a {@code .} or a {@code :}, or spelled {@code localhost}, is a
   * registry — everything else is a namespace. That is exactly why
   * {@code registry.dev.localhost:8080/qits/workspace} is one image and {@code qits/workspace} is
   * the same one, while {@code qits/build-images/maven-base} keeps all three of its segments.
   *
   * <p><b>Only a HOST is dropped, never a namespace</b>, so the name still says who publishes the
   * image — which is what the internal/external rule reads.
   */
  private static String unqualified(String image) {
    int slash = image.indexOf('/');
    if (slash < 0) {
      return image;
    }
    String first = image.substring(0, slash);
    boolean host = first.indexOf('.') >= 0 || first.indexOf(':') >= 0 || first.equals("localhost");
    return host ? image.substring(slash + 1) : image;
  }
}
