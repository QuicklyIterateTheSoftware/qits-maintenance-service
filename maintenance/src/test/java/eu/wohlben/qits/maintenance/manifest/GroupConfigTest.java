package eu.wohlben.qits.maintenance.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.GroupSource;
import eu.wohlben.qits.maintenance.model.PinKind;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A repository's own {@code maintenance.yml}, and what happens when it is not one. */
class GroupConfigTest {

  @Test
  void noFileIsTheInternalExternalSplit() {
    GroupConfig.Parsed parsed = GroupConfig.fallback();
    assertTrue(parsed.ok());
    assertEquals(GroupSource.DEFAULT, parsed.source());
    assertEquals(
        List.of(GroupConfig.DEFAULT_GROUP, GroupConfig.EXTERNAL_GROUP),
        parsed.groups().stream().map(GroupConfig.Group::name).toList());
    assertEquals(
        List.of(PinKind.INTERNAL, PinKind.EXTERNAL),
        parsed.groups().stream().map(GroupConfig.Group::kind).toList());
    // A kind group claims by kind and by nothing else: its patterns are empty, not `*`.
    assertEquals(List.of(), parsed.groups().get(0).patterns());
    assertEquals(List.of(), parsed.groups().get(1).patterns());
  }

  @Test
  void theInternalHalfKeepsTheNameItAlreadyHad() {
    assertEquals("dependencies", GroupConfig.DEFAULT_GROUP);
    assertEquals("external", GroupConfig.EXTERNAL_GROUP);
  }

  // --- groups: retired (qits-1133 R5) -------------------------------------------------------------

  /**
   * <b>A {@code groups:} KEY IS IGNORED, NOT REFUSED.</b> The group branches are retired, so a group
   * a file declares decides nothing — and a file written for the old service must not turn its
   * repository into a CONFIG_ERROR, which would hide every pin it has.
   */
  @Test
  void aRetiredGroupsKeyIsIgnoredAndTheSplitStands() {
    GroupConfig.Parsed parsed =
        GroupConfig.parse(
            """
            groups:
              - name: angular
                deps: ["@angular/*", "@qits/angular"]
              - name: quarkus
                deps: ["io.quarkus:*", "io.quarkus.platform:*"]
            """);
    assertTrue(parsed.ok(), parsed.error());
    assertEquals(GroupSource.DEFAULT, parsed.source());
    assertEquals(
        List.of(GroupConfig.DEFAULT_GROUP, GroupConfig.EXTERNAL_GROUP),
        parsed.groups().stream().map(GroupConfig.Group::name).toList());
    assertEquals(
        List.of(PinKind.INTERNAL, PinKind.EXTERNAL),
        parsed.groups().stream().map(GroupConfig.Group::kind).toList());
  }

  /** Ignored whatever it says — a group that used to be refused is no reason to refuse the file. */
  @Test
  void aGroupsKeyThatUsedToBeInvalidIsIgnoredToo() {
    assertTrue(GroupConfig.parse("groups:\n  - name: has/slash\n    deps: [\"*\"]\n").ok());
    assertTrue(GroupConfig.parse("groups:\n  - name: angular\n    deps: []\n").ok());
    assertTrue(GroupConfig.parse("groups: not-a-list\n").ok());
    assertTrue(
        GroupConfig.parse(
                "groups:\n  - name: a\n    deps: [\"x\"]\n  - name: a\n    deps: [\"y\"]\n")
            .ok());
  }

  @Test
  void anEmptyFileSaysWhatAnAbsentOneSays() {
    assertEquals(GroupSource.DEFAULT, GroupConfig.parse("").source());
    assertEquals(GroupSource.DEFAULT, GroupConfig.parse("# only a comment\n").source());
  }

  @Test
  void brokenYamlIsAConfigErrorWithASentence() {
    GroupConfig.Parsed parsed = GroupConfig.parse("ignore: [ - unbalanced\n");
    assertFalse(parsed.ok());
    assertTrue(parsed.error().contains(GroupConfig.PATH));
  }

  // --- ignore: a whole ecosystem taken off the repository -------------------------------------

  /** THE WRAPPER'S CASE. Its gitlinks are bank markers the repository states are expected to lag. */
  @Test
  void ignoreTakesOneEcosystemOffTheRepository() {
    GroupConfig.Parsed parsed = GroupConfig.parse("ignore: [gitlink]\n");

    assertTrue(parsed.ok());
    assertEquals(Set.of(Ecosystem.GITLINK), parsed.ignored());
    assertTrue(parsed.ignores(Ecosystem.GITLINK));
    assertFalse(parsed.ignores(Ecosystem.MAVEN), "one ecosystem, not the repository");
    // It said nothing about grouping, so it gets the grouping a repository that said nothing gets.
    assertEquals(GroupSource.DEFAULT, parsed.source());
    assertEquals(
        List.of(GroupConfig.DEFAULT_GROUP, GroupConfig.EXTERNAL_GROUP),
        parsed.groups().stream().map(GroupConfig.Group::name).toList());
  }

  @Test
  void severalEcosystemsMayBeIgnoredAtOnce() {
    GroupConfig.Parsed parsed = GroupConfig.parse("ignore: [gitlink, docker]\n");

    assertTrue(parsed.ok());
    assertEquals(Set.of(Ecosystem.GITLINK, Ecosystem.DOCKER), parsed.ignored());
    assertFalse(parsed.ignores(Ecosystem.NPM));
  }

  /**
   * A TYPO IS NOT AN OPT-OUT. This file is this service's own configuration surface — unlike
   * {@code .gitmodules}, which git owns — so a name it does not know is the file being wrong, and
   * the repository is told so on its row. Dropping it silently would read as a working `ignore`
   * while the ecosystem the author meant to protect went on being bumped nightly.
   */
  @Test
  void anUnknownEcosystemNameIsAnInvalidFile() {
    GroupConfig.Parsed parsed = GroupConfig.parse("ignore: [gitlinks]\n");

    assertFalse(parsed.ok());
    assertTrue(parsed.error().contains("gitlinks"), "the sentence names what it could not read");
    assertTrue(parsed.ignored().isEmpty(), "an invalid file ignores nothing");
    // One good name beside one bad one does not rescue the file.
    assertFalse(GroupConfig.parse("ignore: [maven, npmm]\n").ok());
  }

  @Test
  void ignoreMustBeAListOfNonEmptyNames() {
    assertFalse(GroupConfig.parse("ignore: gitlink\n").ok());
    assertFalse(GroupConfig.parse("ignore: [\"\"]\n").ok());
    assertFalse(GroupConfig.parse("ignore: [{name: gitlink}]\n").ok());
  }

  /** `ignore` still applies beside a retired `groups` key, in whichever order they are written. */
  @Test
  void ignoreStillAppliesBesideARetiredGroupsKey() {
    for (String yaml :
        List.of(
            "ignore: [gitlink]\ngroups:\n  - name: angular\n    deps: [\"@angular/*\"]\n",
            "groups:\n  - name: has/slash\n    deps: [\"*\"]\nignore: [gitlink]\n")) {
      GroupConfig.Parsed parsed = GroupConfig.parse(yaml);
      assertTrue(parsed.ok(), parsed.error());
      assertEquals(Set.of(Ecosystem.GITLINK), parsed.ignored());
      assertEquals(GroupSource.DEFAULT, parsed.source());
      assertEquals(2, parsed.groups().size());
    }
  }

  @Test
  void aRepositoryThatSaysNothingIgnoresNothing() {
    assertTrue(GroupConfig.fallback().ignored().isEmpty());
    assertTrue(GroupConfig.parse("").ignored().isEmpty());
    assertTrue(
        GroupConfig.parse("groups:\n  - name: angular\n    deps: [\"@angular/*\"]\n")
            .ignored()
            .isEmpty());
  }

  // --- hold (qits-1133) -----------------------------------------------------------------------

  @Test
  void nothingIsHeldByDefault() {
    assertTrue(GroupConfig.fallback().held().isEmpty());
    assertFalse(GroupConfig.fallback().holds("@angular/core"));
    assertTrue(GroupConfig.parse("ignore: [gitlink]\n").held().isEmpty());
  }

  @Test
  void aHoldNamesDependenciesAndTakesTheGroupGlobs() {
    GroupConfig.Parsed parsed =
        GroupConfig.parse("hold:\n  - \"@angular/*\"\n  - io.quarkus.platform:quarkus-bom\n");
    assertTrue(parsed.ok(), parsed.error());
    assertEquals(List.of("@angular/*", "io.quarkus.platform:quarkus-bom"), parsed.held());
    assertTrue(parsed.holds("@angular/core"));
    assertTrue(parsed.holds("io.quarkus.platform:quarkus-bom"));
    assertFalse(parsed.holds("@qits/ui-components"));
    assertEquals(GroupSource.DEFAULT, parsed.source(), "a hold asks for no grouping");
  }

  @Test
  void aHoldSitsBesideIgnoreAndARetiredGroupsKey() {
    GroupConfig.Parsed parsed =
        GroupConfig.parse(
            """
            ignore: [docker]
            hold: ["eu.wohlben.qits:qits-eventstream"]
            groups:
              - name: angular
                deps: ["@angular/*"]
            """);
    assertTrue(parsed.ok(), parsed.error());
    assertEquals(GroupSource.DEFAULT, parsed.source());
    assertTrue(parsed.holds("eu.wohlben.qits:qits-eventstream"));
    assertEquals(Set.of(Ecosystem.DOCKER), parsed.ignored());
    assertEquals(2, parsed.groups().size());
  }

  @Test
  void aHoldThatDoesNotParseRefusesTheFile() {
    // A hold skipped is a dependency bumped that somebody wrote down to protect.
    assertFalse(GroupConfig.parse("hold: angular\n").ok());
    assertFalse(GroupConfig.parse("hold: [\"\"]\n").ok());
    assertFalse(GroupConfig.parse("hold: [{name: x}]\n").ok());
  }
}
