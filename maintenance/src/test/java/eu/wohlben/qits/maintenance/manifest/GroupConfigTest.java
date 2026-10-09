package eu.wohlben.qits.maintenance.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.GroupSource;
import eu.wohlben.qits.maintenance.model.PinKind;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A repository's own grouping, and what happens when it is not one. */
class GroupConfigTest {

  @Test
  void noFileIsTheInternalExternalSplit() {
    // NOT one catch-all any more. The platform's own releases and everybody else's are found by
    // different schedules and reviewed by different eyes, so they are two branches.
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
  void theInternalHalfKeepsTheBranchNameItAlreadyHad() {
    // `maintenance/dependencies` is a branch the release door cleans and three repositories name.
    // The split changed what it carries, deliberately not what it is called.
    assertEquals("dependencies", GroupConfig.DEFAULT_GROUP);
    assertEquals("external", GroupConfig.EXTERNAL_GROUP);
  }

  @Test
  void declarationOrderIsKeptBecauseFirstMatchWins() {
    GroupConfig.Parsed parsed =
        GroupConfig.parse(
            """
            groups:
              - name: angular
                deps: ["@angular/*", "@qits/angular"]
              - name: quarkus
                deps: ["io.quarkus:*", "io.quarkus.platform:*"]
            """);
    assertTrue(parsed.ok());
    assertEquals(GroupSource.CONFIG, parsed.source());
    assertEquals(
        List.of("angular", "quarkus", GroupConfig.DEFAULT_GROUP, GroupConfig.EXTERNAL_GROUP),
        parsed.groups().stream().map(GroupConfig.Group::name).toList());
    assertEquals(List.of("@angular/*", "@qits/angular"), parsed.groups().get(0).patterns());
    // A configured group claims by its globs and carries no kind at all.
    assertNull(parsed.groups().get(0).kind());
  }

  @Test
  void theKindPairIsAppendedLastSoNoConfiguredGroupLosesAPin() {
    GroupConfig.Parsed parsed =
        GroupConfig.parse("groups:\n  - name: angular\n    deps: [\"@angular/*\"]\n");
    assertEquals(3, parsed.groups().size());
    assertEquals(
        List.of(GroupConfig.DEFAULT_GROUP, GroupConfig.EXTERNAL_GROUP),
        parsed.groups().subList(1, 3).stream().map(GroupConfig.Group::name).toList());
    assertEquals(
        List.of(PinKind.INTERNAL, PinKind.EXTERNAL),
        parsed.groups().subList(1, 3).stream().map(GroupConfig.Group::kind).toList());
  }

  @Test
  void aRepositoryThatDeclaresTheInternalHalfItselfKeepsItsOwnPatterns() {
    // The name is that repository's now, globs and all — and only the half it did NOT take is
    // appended, because two rows may not share a (repository, name).
    GroupConfig.Parsed parsed =
        GroupConfig.parse("groups:\n  - name: dependencies\n    deps: [\"io.quarkus:*\"]\n");
    assertEquals(
        List.of("dependencies", GroupConfig.EXTERNAL_GROUP),
        parsed.groups().stream().map(GroupConfig.Group::name).toList());
    assertEquals(List.of("io.quarkus:*"), parsed.groups().get(0).patterns());
    assertNull(parsed.groups().get(0).kind());
    assertEquals(PinKind.EXTERNAL, parsed.groups().get(1).kind());
  }

  @Test
  void aRepositoryThatDeclaresBothNamesGetsNoTailAtAll() {
    GroupConfig.Parsed parsed =
        GroupConfig.parse(
            """
            groups:
              - name: dependencies
                deps: ["eu.wohlben.qits:*"]
              - name: external
                deps: ["*"]
            """);
    assertEquals(2, parsed.groups().size());
    assertTrue(parsed.groups().stream().allMatch(group -> group.kind() == null));
  }

  @Test
  void anEmptyFileSaysWhatAnAbsentOneSays() {
    assertEquals(GroupSource.DEFAULT, GroupConfig.parse("").source());
    assertEquals(GroupSource.DEFAULT, GroupConfig.parse("# only a comment\n").source());
  }

  @Test
  void brokenYamlIsAConfigErrorWithASentence() {
    GroupConfig.Parsed parsed = GroupConfig.parse("groups: [ - unbalanced\n");
    assertFalse(parsed.ok());
    assertTrue(parsed.error().contains(GroupConfig.PATH));
  }

  @Test
  void aGroupNameThatCouldNotBeABranchIsRefused() {
    assertFalse(GroupConfig.parse("groups:\n  - name: has/slash\n    deps: [\"*\"]\n").ok());
    assertFalse(GroupConfig.parse("groups:\n  - name: \"\"\n    deps: [\"*\"]\n").ok());
  }

  @Test
  void aGroupWithNoDepsClaimsNothingAndIsRefused() {
    assertFalse(GroupConfig.parse("groups:\n  - name: angular\n    deps: []\n").ok());
    assertFalse(GroupConfig.parse("groups:\n  - name: angular\n").ok());
  }

  @Test
  void oneNameTwiceIsRefusedRatherThanSilentlyMerged() {
    assertFalse(
        GroupConfig.parse(
                "groups:\n  - name: a\n    deps: [\"x\"]\n  - name: a\n    deps: [\"y\"]\n")
            .ok());
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

  /** The two keys are independent questions and one file may answer both. */
  @Test
  void ignoreAndGroupsTravelInOneFile() {
    GroupConfig.Parsed parsed =
        GroupConfig.parse(
            """
            ignore: [gitlink]
            groups:
              - name: angular
                deps: ["@angular/*"]
            """);

    assertTrue(parsed.ok());
    assertEquals(Set.of(Ecosystem.GITLINK), parsed.ignored());
    assertEquals(GroupSource.CONFIG, parsed.source());
    assertEquals(
        List.of("angular", GroupConfig.DEFAULT_GROUP, GroupConfig.EXTERNAL_GROUP),
        parsed.groups().stream().map(GroupConfig.Group::name).toList());
    // The order the keys are written in is not the meaning: only the groups' own order is.
    assertEquals(
        parsed.groups().stream().map(GroupConfig.Group::name).toList(),
        GroupConfig.parse(
                """
                groups:
                  - name: angular
                    deps: ["@angular/*"]
                ignore: [gitlink]
                """)
            .groups()
            .stream()
            .map(GroupConfig.Group::name)
            .toList());
  }

  /** A broken `groups` is still a broken file, whatever `ignore` said beside it. */
  @Test
  void aFileWithAGoodIgnoreAndABadGroupIsInvalid() {
    assertFalse(GroupConfig.parse("ignore: [gitlink]\ngroups:\n  - name: has/slash\n    deps: [\"*\"]\n").ok());
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

  /** {@code hold:} names dependencies the dependency-bump automation leaves alone (qits-1133). */
  @Test
  void holdNamesDependenciesTheBumpLeavesAlone() {
    GroupConfig.Parsed parsed =
        GroupConfig.parse("hold: [\"@qits/ui-components\", \"eu.wohlben.qits:*\"]\n");
    assertTrue(parsed.ok(), parsed.error());
    assertTrue(parsed.holds("@qits/ui-components"));
    assertTrue(parsed.holds("eu.wohlben.qits:qits-eventstream"));
    assertFalse(parsed.holds("@qits/other"));
    assertEquals(GroupSource.DEFAULT, parsed.source(), "hold asks for no grouping");
    assertTrue(GroupConfig.fallback().held().isEmpty());
    assertFalse(GroupConfig.parse("hold: nope\n").ok());
    assertFalse(GroupConfig.parse("hold: [\"\"]\n").ok());
  }
}
