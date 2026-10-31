package eu.wohlben.qits.maintenance.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A repository's {@code .config/qits/repository.yml}, of which only {@code repository.archived} is
 * read (qits-1784) — and the effective rule it feeds with {@code dependency-bumps}.
 */
class RepositoryConfigTest {

  @Test
  void archivedTrueAndFalseAreRead() {
    RepositoryConfig.Parsed archived =
        RepositoryConfig.parse("version: 1\nrepository:\n  archived: true\n");
    assertTrue(archived.ok(), archived.error());
    assertTrue(archived.archived());

    RepositoryConfig.Parsed live =
        RepositoryConfig.parse("version: 1\nrepository:\n  archived: false\n");
    assertTrue(live.ok(), live.error());
    assertFalse(live.archived());
  }

  /** Archiving is something a repository says, never something it is assumed to have said. */
  @Test
  void anAbsentKeyOrSectionIsNotArchived() {
    assertFalse(RepositoryConfig.fallback().archived());
    assertTrue(RepositoryConfig.fallback().ok());
    for (String yaml :
        new String[] {
          "version: 1\n", "version: 1\nrepository:\n", "repository:\n  description: x\n"
        }) {
      RepositoryConfig.Parsed parsed = RepositoryConfig.parse(yaml);
      assertTrue(parsed.ok(), yaml + ": " + parsed.error());
      assertFalse(parsed.archived(), yaml);
    }
  }

  @Test
  void anEmptyFileIsNotArchived() {
    assertTrue(RepositoryConfig.parse("").ok());
    assertFalse(RepositoryConfig.parse("").archived());
    assertFalse(RepositoryConfig.parse("# nothing yet\n").archived());
  }

  @Test
  void yamlThatDoesNotParseIsAConfigErrorNamingTheFile() {
    RepositoryConfig.Parsed parsed = RepositoryConfig.parse("repository: [unclosed\n");
    assertFalse(parsed.ok());
    assertTrue(parsed.error().contains(RepositoryConfig.PATH), parsed.error());

    RepositoryConfig.Parsed duplicate =
        RepositoryConfig.parse("repository:\n  archived: true\n  archived: false\n");
    assertFalse(duplicate.ok(), "two answers are no answer");
  }

  @Test
  void aRootOrSectionThatIsNotAMappingIsAConfigError() {
    assertFalse(RepositoryConfig.parse("- archived\n").ok());
    assertTrue(RepositoryConfig.parse("- archived\n").error().contains(RepositoryConfig.PATH));
    RepositoryConfig.Parsed section = RepositoryConfig.parse("repository: archived\n");
    assertFalse(section.ok());
    assertTrue(section.error().contains(RepositoryConfig.PATH), section.error());
  }

  /** A typo is not an opt-out, in either direction. */
  @Test
  void anArchivedThatIsNotABooleanIsAConfigError() {
    for (String value : new String[] {"\"true\"", "maybe", "1", "[true]"}) {
      RepositoryConfig.Parsed parsed =
          RepositoryConfig.parse("repository:\n  archived: " + value + "\n");
      assertFalse(parsed.ok(), value);
      assertTrue(parsed.error().contains(RepositoryConfig.PATH), parsed.error());
      assertTrue(parsed.error().contains("repository.archived"), parsed.error());
    }
  }

  /** The file is the workspace tooling's: every key but the one is somebody else's business. */
  @Test
  void everyOtherKeyIsIgnored() {
    RepositoryConfig.Parsed parsed =
        RepositoryConfig.parse(
            """
            version: 1
            tooling:
              anything: [1, 2, 3]
            repository:
              archived: true
              description: retired with the runner daemons
              labels: {a: b}
            """);
    assertTrue(parsed.ok(), parsed.error());
    assertTrue(parsed.archived());
  }

  // --- the effective rule ---------------------------------------------------------------------------

  @Test
  void theSwitchDecidesWhenPresentAndTheArchiveOtherwise() {
    assertEquals(DependencyBumps.ON, DependencyBumps.of(null, false));
    assertEquals(
        new DependencyBumps(false, DependencyBumps.ARCHIVED), DependencyBumps.of(null, true));
    assertEquals(DependencyBumps.ON, DependencyBumps.of(true, true), "true overrides the archive");
    assertEquals(
        new DependencyBumps(false, DependencyBumps.SWITCH), DependencyBumps.of(false, false));
    assertEquals(
        new DependencyBumps(false, DependencyBumps.SWITCH), DependencyBumps.of(false, true));
  }
}
