package eu.wohlben.qits.maintenance.sbom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.bootstrap.graph.Ecosystem;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A purl into the three values that can be joined to something.
 *
 * <p><b>The name this produces is {@code mt_pin}'s spelling, and that is what every assertion here
 * is really about.</b> A component whose name came out as {@code eu.wohlben.qits/qits-eventstream}
 * would be stored, listed, and matched by nothing — the reverse query, the "behind" verdict and the
 * pin exclusion all join on that string. A wrong reading here is not a wrong label; it is a row
 * that silently answers no question.
 */
class PurlTest {

  private static Purl parsed(String purl) {
    Optional<Purl> read = Purl.parse(purl);
    assertTrue(read.isPresent(), purl + " should have parsed");
    return read.get();
  }

  @Test
  void aMavenPurlBecomesTheGroupIdColonArtifactIdMtPinRecords() {
    Purl purl = parsed("pkg:maven/eu.wohlben.qits/qits-eventstream@2026.901.1");

    assertEquals(Ecosystem.MAVEN, purl.ecosystem());
    assertEquals("eu.wohlben.qits:qits-eventstream", purl.name());
    assertEquals("2026.901.1", purl.version());
  }

  /**
   * The scoped npm spelling producers most often emit: the scope's at-sign and its slash both
   * percent-encoded. It is the SAME package as the plain form below, and a reading that kept the
   * escapes would join to nothing.
   */
  @Test
  void aPercentEncodedNpmScopeIsDecodedWholeAndIsTheSamePackageAsThePlainForm() {
    Purl encoded = parsed("pkg:npm/%40qits%2Fui-components@2026.8.4");
    Purl plain = parsed("pkg:npm/@qits/ui-components@2026.8.4");

    assertEquals(Ecosystem.NPM, encoded.ecosystem());
    assertEquals("@qits/ui-components", encoded.name());
    assertEquals("2026.8.4", encoded.version());
    assertEquals(plain, encoded);
  }

  /** An unscoped package keeps its bare name — there is no scope to reconstruct. */
  @Test
  void aPlainNpmPackageKeepsItsBareName() {
    Purl purl = parsed("pkg:npm/lodash@4.17.21");

    assertEquals(Ecosystem.NPM, purl.ecosystem());
    assertEquals("lodash", purl.name());
    assertEquals("4.17.21", purl.version());
  }

  /** The at-sign is read from the RIGHT, or a scope's own would be taken for a version separator. */
  @Test
  void theVersionSeparatorIsTheLastAtSignSoAScopeSurvives() {
    Purl purl = parsed("pkg:npm/@angular/core@21.1.0");

    assertEquals("@angular/core", purl.name());
    assertEquals("21.1.0", purl.version());
  }

  @Test
  void aTaggedImageKeepsItsNameAndTakesTheTagAsItsVersion() {
    Purl purl = parsed("pkg:docker/qits/build-images/maven-base@2026.821.2");

    assertEquals(Ecosystem.DOCKER, purl.ecosystem());
    assertEquals("qits/build-images/maven-base", purl.name());
    assertEquals("2026.821.2", purl.version());
  }

  /**
   * A DIGEST IS NOT A VERSION and must never become one. {@code DockerParser} refuses a
   * digest-pinned {@code FROM} for the same reason: {@code sha256:…} has no order, so nothing can
   * be said about whether it is behind.
   */
  @Test
  void aDigestPinnedImageCarriesANameAndNoVersion() {
    Purl purl =
        parsed("pkg:docker/qits/qits-ci@sha256:1111111111111111111111111111111111111111111111111111111111111111");

    assertEquals(Ecosystem.DOCKER, purl.ecosystem());
    assertEquals("qits/qits-ci", purl.name());
    assertNull(purl.version(), "a digest has no order and must not be ranked as a version");
  }

  /** {@code oci} is what a container build usually emits; on this platform it is the same thing. */
  @Test
  void anOciPurlIsReadAsTheSameWorldAsADockerOne() {
    Purl purl = parsed("pkg:oci/qits-ci@2026.901.2");

    assertEquals(Ecosystem.DOCKER, purl.ecosystem());
    assertEquals("qits-ci", purl.name());
    assertEquals("2026.901.2", purl.version());
  }

  /**
   * A type this platform does not inventory is EMPTY rather than a guess: the component is still
   * stored, with a null ecosystem, and it is never matched. Inventing a mapping would put a name
   * into a join key that means something else.
   */
  @Test
  void anUnknownTypeIsEmptyRatherThanAGuess() {
    assertTrue(Purl.parse("pkg:golang/github.com/spf13/cobra@1.8.0").isEmpty());
    assertTrue(Purl.parse("pkg:generic/openssl@3.0.8").isEmpty());
    assertTrue(Purl.parse("pkg:cargo/serde@1.0.0").isEmpty());
  }

  @Test
  void anythingThatIsNotAPurlAtAllIsEmpty() {
    assertTrue(Purl.parse(null).isEmpty());
    assertTrue(Purl.parse("").isEmpty());
    assertTrue(Purl.parse("eu.wohlben.qits:qits-eventstream:2026.901.1").isEmpty());
    assertTrue(Purl.parse("pkg:maven").isEmpty());
    // A maven coordinate with no namespace is not a coordinate anything can look up.
    assertTrue(Purl.parse("pkg:maven/qits-eventstream@1.0.0").isEmpty());
  }

  /**
   * Qualifiers are PROVENANCE — where a build got the component — and are not part of its identity.
   * A name carrying {@code ?type=jar} would match no pin.
   */
  @Test
  void qualifiersAreStrippedBeforeAnythingElseIsRead() {
    Purl purl =
        parsed(
            "pkg:maven/eu.wohlben.qits/qits-eventstream@2026.901.1"
                + "?type=jar&repository_url=https%3A%2F%2Fartifacts.example");

    assertEquals("eu.wohlben.qits:qits-eventstream", purl.name());
    assertEquals("2026.901.1", purl.version());
  }

  /** And the subpath with them, including when it follows a qualifier. */
  @Test
  void theSubpathIsStrippedToo() {
    Purl bare = parsed("pkg:npm/lodash@4.17.21#packages/core");
    assertEquals("lodash", bare.name());
    assertEquals("4.17.21", bare.version());

    Purl both = parsed("pkg:npm/lodash@4.17.21?type=module#packages/core");
    assertEquals("lodash", both.name());
    assertEquals("4.17.21", both.version());
  }

  /** A purl may name no version at all, and that is a component with an unknown one. */
  @Test
  void aPurlWithNoVersionIsReadWithANullOne() {
    Purl purl = parsed("pkg:maven/io.quarkus/quarkus-core");

    assertEquals("io.quarkus:quarkus-core", purl.name());
    assertNull(purl.version());
  }

  /**
   * A registry host carries a colon and a port, and it is part of the image NAME. The colon-as-tag
   * reading only applies after the last slash.
   */
  @Test
  void aRegistryPortIsPartOfTheImageNameRatherThanATag() {
    Purl purl = parsed("pkg:docker/mirror.dev.localhost:8080/quay/quarkus/builder@jdk-25");

    assertEquals("mirror.dev.localhost:8080/quay/quarkus/builder", purl.name());
    assertEquals("jdk-25", purl.version());
  }

  /**
   * A tag written into the name rather than after an at-sign, which some producers do. The two
   * spellings are one image.
   */
  @Test
  void aTagInsideTheNameIsReadAsTheVersion() {
    Purl purl = parsed("pkg:docker/qits/qits-ci:2026.901.2");

    assertEquals("qits/qits-ci", purl.name());
    assertEquals("2026.901.2", purl.version());
  }

  /**
   * Percent-decoding is percent-decoding and not form decoding. {@code URLDecoder} would turn the
   * plus into a space and the version would match nothing.
   */
  @Test
  void aPlusInAVersionSurvivesTheDecoding() {
    Purl purl = parsed("pkg:maven/io.example/thing@1.0.0%2Bbuild.7");

    assertEquals("1.0.0+build.7", purl.version());
  }
}
