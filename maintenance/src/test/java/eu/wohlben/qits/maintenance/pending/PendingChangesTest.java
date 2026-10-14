package eu.wohlben.qits.maintenance.pending;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.maintenance.entity.MtGroup;
import eu.wohlben.qits.maintenance.latest.GitlinkSha;
import eu.wohlben.qits.maintenance.entity.MtLatest;
import eu.wohlben.qits.maintenance.entity.MtPin;
import eu.wohlben.qits.maintenance.latest.PublishedLookup;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.PinKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * The join of pins and latest versions, read through the groups.
 *
 * <p>No database: the computation reads fields off three row shapes and writes nothing, which is
 * exactly why it is worth having as a pure function.
 */
class PendingChangesTest {

  private static MtPin pin(Ecosystem ecosystem, String name, String version, String manifest) {
    MtPin pin = new MtPin();
    pin.repository = "qits-ci";
    pin.manifestPath = manifest;
    pin.ecosystem = ecosystem.wireName();
    pin.name = name;
    pin.version = version;
    pin.kind = "INTERNAL";
    pin.location = "dependency:" + name;
    return pin;
  }

  private static MtLatest latest(Ecosystem ecosystem, String name, String version) {
    MtLatest row = new MtLatest();
    row.ecosystem = ecosystem.wireName();
    row.name = name;
    row.latest = version;
    row.checkedAt = Instant.now();
    return row;
  }

  private static MtPin pin(Ecosystem ecosystem, String name, String version, String manifest, PinKind kind) {
    MtPin pin = pin(ecosystem, name, version, manifest);
    pin.kind = kind.name();
    return pin;
  }

  private static MtGroup group(String name, int ordinal, String... patterns) {
    MtGroup group = new MtGroup();
    group.name = name;
    group.ordinal = ordinal;
    group.patterns = "[" + String.join(",", java.util.Arrays.stream(patterns).map(p -> "\"" + p + "\"").toList()) + "]";
    group.source = "CONFIG";
    return group;
  }

  /** A group that claims by KIND: no globs at all, which is what the store writes for one. */
  private static MtGroup kindGroup(String name, int ordinal, PinKind kind) {
    MtGroup group = new MtGroup();
    group.name = name;
    group.ordinal = ordinal;
    group.patterns = "[]";
    group.kind = kind.name();
    group.source = "DEFAULT";
    return group;
  }

  private static final List<MtGroup> GROUPS =
      List.of(group("angular", 0, "@angular/*"), group("dependencies", 1, "*"));

  /** What a repository with no configuration gets: the two halves, internal first. */
  private static final List<MtGroup> SPLIT =
      List.of(
          kindGroup("dependencies", 0, PinKind.INTERNAL), kindGroup("external", 1, PinKind.EXTERNAL));

  /** A registry nobody may ask: a property one artifact reads is decided without one. */
  private static final PublishedVersions NO_REGISTRY =
      (ecosystem, kind, name) -> fail("no registry may be asked, and " + name + " was");

  @Test
  void aNewerVersionIsPendingAndCarriesTheFileAndTheLocation() {
    MtPin pin = pin(Ecosystem.MAVEN, "eu.wohlben.qits:qits-eventstream", "2026.811.1", "pom.xml");
    Map<String, MtLatest> latest =
        PendingChanges.index(
            List.of(latest(Ecosystem.MAVEN, "eu.wohlben.qits:qits-eventstream", "2026.821.3")));

    List<PendingChanges.Pending> pending = PendingChanges.of(List.of(pin), latest, GROUPS);
    assertEquals(1, pending.size());
    Change change = pending.get(0).change();
    assertEquals("dependencies", pending.get(0).group());
    assertEquals("maven", change.ecosystem());
    assertEquals("pom.xml", change.manifestPath());
    assertEquals("2026.811.1", change.from());
    assertEquals("2026.821.3", change.to());
    assertEquals("dependency:eu.wohlben.qits:qits-eventstream", change.location());
  }

  @Test
  void anUpToDatePinIsNotPending() {
    MtPin pin = pin(Ecosystem.MAVEN, "g:a", "1.2.3", "pom.xml");
    Map<String, MtLatest> latest =
        PendingChanges.index(List.of(latest(Ecosystem.MAVEN, "g:a", "1.2.3")));
    assertTrue(PendingChanges.of(List.of(pin), latest, GROUPS).isEmpty());
  }

  @Test
  void aPrereleaseIsNotOfferedToAReleasedPin() {
    MtPin pin = pin(Ecosystem.NPM, "@angular/core", "21.0.4", "package.json");
    Map<String, MtLatest> latest =
        PendingChanges.index(List.of(latest(Ecosystem.NPM, "@angular/core", "22.0.0-rc.1")));
    assertTrue(PendingChanges.of(List.of(pin), latest, GROUPS).isEmpty());
  }

  @Test
  void aPrereleaseIsOfferedWhenThePinIsOneToo() {
    MtPin pin = pin(Ecosystem.NPM, "@angular/core", "22.0.0-rc.1", "package.json");
    Map<String, MtLatest> latest =
        PendingChanges.index(List.of(latest(Ecosystem.NPM, "@angular/core", "22.0.0-rc.2")));
    List<PendingChanges.Pending> pending = PendingChanges.of(List.of(pin), latest, GROUPS);
    assertEquals(1, pending.size());
    assertEquals("angular", pending.get(0).group());
  }

  @Test
  void aLatestThatCouldNotBeReadOffersNothingAndIsNotUpToDate() {
    MtLatest row = latest(Ecosystem.MAVEN, "g:a", null);
    row.error = "the registry could not be reached";
    MtPin pin = pin(Ecosystem.MAVEN, "g:a", "1.0.0", "pom.xml");
    assertTrue(PendingChanges.of(List.of(pin), PendingChanges.index(List.of(row)), GROUPS).isEmpty());
    assertFalse(PendingChanges.newerVersion(pin, PendingChanges.index(List.of(row))).isPresent());
  }

  @Test
  void thisRepositorysOwnArtifactIsNeverPendingWhateverTheRegistrySays() {
    // qits-ci depending on qits-ci-domain at ${project.version}. The registry HAS a newer release —
    // this repository published it — and offering it would be offering to overwrite what the
    // release door stamps on the next build.
    MtPin pin = pin(Ecosystem.MAVEN, "eu.wohlben.qits:qits-ci-domain", "2026.821.1", "pom.xml");
    pin.kind = "REACTOR";
    Map<String, MtLatest> latest =
        PendingChanges.index(
            List.of(latest(Ecosystem.MAVEN, "eu.wohlben.qits:qits-ci-domain", "2026.822.9")));
    assertTrue(PendingChanges.of(List.of(pin), latest, GROUPS).isEmpty());
    assertTrue(PendingChanges.newerVersion(pin, latest).isEmpty());
  }

  @Test
  void aPinStillCarryingAnExpressionIsNeverPending() {
    MtPin pin = pin(Ecosystem.MAVEN, "${project.groupId}:qits-arch-rules", "1.0.0", "pom.xml");
    pin.kind = "UNRESOLVED";
    Map<String, MtLatest> latest =
        PendingChanges.index(
            List.of(latest(Ecosystem.MAVEN, "${project.groupId}:qits-arch-rules", "2.0.0")));
    assertTrue(PendingChanges.of(List.of(pin), latest, GROUPS).isEmpty());
  }

  @Test
  void aKindThisBuildDoesNotKnowIsReadAsUnresolvedRatherThanCrashing() {
    // A row written by a newer build, read by an older one. Refusing to act on it is the safe
    // reading; throwing would take the whole page down.
    MtPin pin = pin(Ecosystem.MAVEN, "g:a", "1.0.0", "pom.xml");
    pin.kind = "SOMETHING_LATER";
    assertEquals(
        eu.wohlben.qits.maintenance.model.PinKind.UNRESOLVED, PendingChanges.kindOf(pin));
  }

  @Test
  void firstMatchWinsInDeclarationOrder() {
    assertEquals(
        "angular",
        PendingChanges.groupOf(pin(Ecosystem.NPM, "@angular/core", "21.0.0", "package.json"), GROUPS)
            .orElseThrow());
    assertEquals(
        "dependencies",
        PendingChanges.groupOf(pin(Ecosystem.NPM, "rxjs", "7.0.0", "package.json"), GROUPS)
            .orElseThrow());
  }

  @Test
  void aPinNoGroupClaimsHasNoBranchAndIsNotPending() {
    List<MtGroup> onlyAngular = List.of(group("angular", 0, "@angular/*"));
    MtPin pin = pin(Ecosystem.NPM, "rxjs", "7.0.0", "package.json");
    Map<String, MtLatest> latest =
        PendingChanges.index(List.of(latest(Ecosystem.NPM, "rxjs", "8.0.0")));
    assertTrue(PendingChanges.of(List.of(pin), latest, onlyAngular).isEmpty());
    assertTrue(PendingChanges.groupOf(pin, onlyAngular).isEmpty());
  }

  // --- the split --------------------------------------------------------------------------------

  @Test
  void theFallbackSplitsPinsByKindWhateverTheirEcosystem() {
    // FOUR ECOSYSTEMS' WORTH OF THE SAME RULE. A group claims by the pin's KIND, not by its name
    // and not by where it is published from, so an internal image and an internal npm package go
    // the same way an internal maven artifact does.
    List<MtPin> pins =
        List.of(
            pin(Ecosystem.MAVEN, "eu.wohlben.qits:qits-eventstream", "2026.811.1", "pom.xml", PinKind.INTERNAL),
            pin(Ecosystem.MAVEN, "io.quarkus.platform:quarkus-bom", "3.34.5", "pom.xml", PinKind.EXTERNAL),
            pin(Ecosystem.DOCKER, "qits/build-images/maven-base", "2026.813.1", "Dockerfile", PinKind.INTERNAL),
            pin(Ecosystem.NPM, "@angular/core", "21.0.4", "package.json", PinKind.EXTERNAL));
    Map<String, MtLatest> latest =
        PendingChanges.index(
            List.of(
                latest(Ecosystem.MAVEN, "eu.wohlben.qits:qits-eventstream", "2026.821.3"),
                latest(Ecosystem.MAVEN, "io.quarkus.platform:quarkus-bom", "3.34.6"),
                latest(Ecosystem.DOCKER, "qits/build-images/maven-base", "2026.821.2"),
                latest(Ecosystem.NPM, "@angular/core", "21.1.0")));

    assertEquals(
        List.of("eu.wohlben.qits:qits-eventstream", "qits/build-images/maven-base"),
        PendingChanges.forGroup(pins, latest, SPLIT, "dependencies", NO_REGISTRY).stream()
            .map(Change::name)
            .toList());
    assertEquals(
        List.of("io.quarkus.platform:quarkus-bom", "@angular/core"),
        PendingChanges.forGroup(pins, latest, SPLIT, "external", NO_REGISTRY).stream().map(Change::name).toList());
    assertEquals(Map.of("dependencies", 2, "external", 2), PendingChanges.countByGroup(pins, latest, SPLIT));
  }

  @Test
  void aKindGroupClaimsNothingByItsPatternsBecauseItHasNone() {
    // The patterns column of a kind group is `[]`. A pin of the other kind falls through it to the
    // next group rather than being caught by a leftover `*`.
    MtPin external = pin(Ecosystem.NPM, "rxjs", "7.0.0", "package.json", PinKind.EXTERNAL);
    assertEquals("external", PendingChanges.groupOf(external, SPLIT).orElseThrow());
    assertEquals(
        "dependencies",
        PendingChanges.groupOf(
                pin(Ecosystem.NPM, "@qits/ui-components", "2026.8.1", "package.json", PinKind.INTERNAL),
                SPLIT)
            .orElseThrow());
  }

  @Test
  void aConfiguredGroupClaimsBeforeTheKindPairAndTheRestStillSplits() {
    // The shape a repository with a maintenance.yml gets: its own globs first, the two halves
    // appended after them. @angular/core is EXTERNAL and would fall to `external` — the configured
    // group is written first, so it does not.
    List<MtGroup> configured =
        List.of(
            group("angular", 0, "@angular/*"),
            kindGroup("dependencies", 1, PinKind.INTERNAL),
            kindGroup("external", 2, PinKind.EXTERNAL));
    List<MtPin> pins =
        List.of(
            pin(Ecosystem.NPM, "@angular/core", "21.0.4", "package.json", PinKind.EXTERNAL),
            pin(Ecosystem.NPM, "rxjs", "7.0.0", "package.json", PinKind.EXTERNAL),
            pin(Ecosystem.MAVEN, "eu.wohlben.qits:qits-parent", "2026.800.1", "pom.xml", PinKind.INTERNAL));
    Map<String, MtLatest> latest =
        PendingChanges.index(
            List.of(
                latest(Ecosystem.NPM, "@angular/core", "21.1.0"),
                latest(Ecosystem.NPM, "rxjs", "8.0.0"),
                latest(Ecosystem.MAVEN, "eu.wohlben.qits:qits-parent", "2026.820.1")));
    assertEquals(
        Map.of("angular", 1, "dependencies", 1, "external", 1),
        PendingChanges.countByGroup(pins, latest, configured));
  }

  @Test
  void noKindGroupClaimsAPinThatCanNeverBeBumped() {
    // REACTOR and UNRESOLVED are not kinds a group carries: there is no line to edit, so there is
    // no branch to put one on. `of` never asks — the pin is filtered a step earlier — and the
    // detail page shows no group rather than a branch that would never carry it.
    MtPin reactor =
        pin(Ecosystem.MAVEN, "eu.wohlben.qits:qits-ci-domain", "2026.821.1", "pom.xml", PinKind.REACTOR);
    MtPin unresolved =
        pin(Ecosystem.MAVEN, "g:mystery", "${nobody.declared.this}", "pom.xml", PinKind.UNRESOLVED);
    assertTrue(PendingChanges.groupOf(reactor, SPLIT).isEmpty());
    assertTrue(PendingChanges.groupOf(unresolved, SPLIT).isEmpty());
    // A glob group that names one explicitly still claims it — the page reads what the file says.
    assertEquals(
        "everything",
        PendingChanges.groupOf(reactor, List.of(group("everything", 0, "*"))).orElseThrow());
  }

  @Test
  void aGroupKindThisBuildDoesNotKnowClaimsNothingRatherThanEverything() {
    MtGroup group = kindGroup("later", 0, PinKind.INTERNAL);
    group.kind = "SOMETHING_LATER";
    assertTrue(PendingChanges.kindOf(group).isEmpty());
    assertTrue(
        PendingChanges.groupOf(
                pin(Ecosystem.MAVEN, "g:a", "1.0.0", "pom.xml", PinKind.INTERNAL), List.of(group))
            .isEmpty());
  }

  @Test
  void everyDeclaredGroupIsCountedEvenAtZero() {
    Map<String, Integer> counts = PendingChanges.countByGroup(List.of(), Map.of(), GROUPS);
    assertEquals(Map.of("angular", 0, "dependencies", 0), counts);
  }

  // --- gitlinks: a difference between two shas, never a comparison of two versions ---------------

  private static final String PINNED_SHA = "aa11bb22cc33dd44ee55ff6677889900aabbccdd";
  private static final String RELEASED_SHA = "0011223344556677889900aabbccddeeff001122";

  private static MtPin gitlinkPin(String sha) {
    MtPin pin = pin(Ecosystem.GITLINK, "qits-artifacts-frontend", sha, "service/src/main/webui");
    pin.location = "gitlink:service/src/main/webui";
    return pin;
  }

  private static MtLatest gitlinkLatest(String version, String sourceUrl) {
    MtLatest row = latest(Ecosystem.GITLINK, "qits-artifacts-frontend", version);
    row.sourceUrl = sourceUrl;
    return row;
  }

  /**
   * The whole gitlink rule: the submodule is pinned at a commit that is not the one the newest
   * release was cut from, so it moves — and it moves to the VERSION, because the step fetches a tag.
   */
  @Test
  void aGitlinkPinnedAtAnotherCommitThanTheReleaseIsPendingAtTheVersion() {
    Map<String, MtLatest> latest =
        PendingChanges.index(
            List.of(gitlinkLatest("2026.901.1", GitlinkSha.of(RELEASED_SHA))));

    List<PendingChanges.Pending> pending =
        PendingChanges.of(List.of(gitlinkPin(PINNED_SHA)), latest, SPLIT);

    assertEquals(1, pending.size());
    assertEquals("dependencies", pending.get(0).group(), "a gitlink is INTERNAL, so it rides the internal half");
    Change change = pending.get(0).change();
    assertEquals("gitlink", change.ecosystem());
    assertEquals(PINNED_SHA, change.from(), "from is the commit the tree holds now");
    assertEquals("2026.901.1", change.to(), "to is the tag the step fetches, not a sha");
    assertEquals("service/src/main/webui", change.manifestPath());
  }

  @Test
  void aGitlinkAlreadyAtTheReleasesCommitIsNotPending() {
    Map<String, MtLatest> latest =
        PendingChanges.index(List.of(gitlinkLatest("2026.901.1", GitlinkSha.of(PINNED_SHA))));

    assertTrue(PendingChanges.of(List.of(gitlinkPin(PINNED_SHA)), latest, SPLIT).isEmpty());
  }

  /**
   * A release nothing could tie to a commit offers NOTHING. The version alone would be a guess, and
   * the step would move the gitlink to a commit this service never compared.
   */
  @Test
  void aGitlinkLatestCarryingNoShaOffersNothing() {
    Map<String, MtLatest> noSha = PendingChanges.index(List.of(gitlinkLatest("2026.901.1", null)));
    Map<String, MtLatest> otherWriter =
        PendingChanges.index(List.of(gitlinkLatest("2026.901.1", "http://a-registry/somewhere")));
    Map<String, MtLatest> notHex =
        PendingChanges.index(List.of(gitlinkLatest("2026.901.1", "sha:not-a-commit")));

    assertTrue(PendingChanges.of(List.of(gitlinkPin(PINNED_SHA)), noSha, SPLIT).isEmpty());
    assertTrue(PendingChanges.of(List.of(gitlinkPin(PINNED_SHA)), otherWriter, SPLIT).isEmpty());
    assertTrue(PendingChanges.of(List.of(gitlinkPin(PINNED_SHA)), notHex, SPLIT).isEmpty());
  }

  /**
   * NEITHER OF THE TWO VERSION RULES REACHES A GITLINK, and this is what would break if one did: a
   * sha reading as a lower "version" than another would make an up-to-date submodule look behind
   * for ever, and a release version's shape would decide whether a commit is a prerelease.
   */
  @Test
  void theShaComparisonIsADifferenceAndNotAnOrder() {
    // ff… would rank ABOVE 00… in every version order there is, and 00… below it. Both are pending,
    // because both differ from the released commit; neither is "older".
    Map<String, MtLatest> latest =
        PendingChanges.index(List.of(gitlinkLatest("2026.901.1", GitlinkSha.of(RELEASED_SHA))));
    String high = "ff" + PINNED_SHA.substring(2);
    String low = "00" + PINNED_SHA.substring(2);

    assertEquals(1, PendingChanges.of(List.of(gitlinkPin(high)), latest, SPLIT).size());
    assertEquals(1, PendingChanges.of(List.of(gitlinkPin(low)), latest, SPLIT).size());
  }

  /** An abbreviation is git's own way of naming the same commit, and it is not a difference. */
  @Test
  void anAbbreviatedShaIsTheSameCommit() {
    Map<String, MtLatest> latest =
        PendingChanges.index(
            List.of(gitlinkLatest("2026.901.1", GitlinkSha.of(PINNED_SHA.substring(0, 12)))));

    assertTrue(PendingChanges.of(List.of(gitlinkPin(PINNED_SHA)), latest, SPLIT).isEmpty());
  }

  @Test
  void oneGroupsChangesAreOnlyItsOwn() {
    List<MtPin> pins =
        List.of(
            pin(Ecosystem.NPM, "@angular/core", "21.0.0", "package.json"),
            pin(Ecosystem.NPM, "rxjs", "7.0.0", "package.json"));
    Map<String, MtLatest> latest =
        PendingChanges.index(
            List.of(
                latest(Ecosystem.NPM, "@angular/core", "21.1.0"),
                latest(Ecosystem.NPM, "rxjs", "7.1.0")));
    List<Change> angular = PendingChanges.forGroup(pins, latest, GROUPS, "angular", NO_REGISTRY);
    assertEquals(1, angular.size());
    assertEquals("@angular/core", angular.get(0).name());
  }

  // --- a property several artifacts read (qits-1206) ----------------------------------------------

  private static final String ARCH_RULES = "eu.wohlben.qits:qits-arch-rules";
  private static final String DB_CORE = "eu.wohlben.qits:qits-db-core";
  private static final String PROPERTY = "property:qits.integrations-quarkus.version";
  private static final Predicate<String> NOTHING_HELD = name -> false;

  /** One reader of the shared property, at its current value. */
  private static MtPin reader(String name, String version) {
    MtPin pin = pin(Ecosystem.MAVEN, name, version, "pom.xml", PinKind.INTERNAL);
    pin.location = PROPERTY;
    return pin;
  }

  /** A registry answering from a map, recording every name it was asked about. */
  private static final class Registry implements PublishedVersions {
    private final Map<String, List<String>> versions;
    final List<String> asked = new ArrayList<>();

    Registry(Map<String, List<String>> versions) {
      this.versions = versions;
    }

    @Override
    public PublishedLookup lookup(Ecosystem ecosystem, PinKind kind, String name) {
      asked.add(name);
      List<String> listed = versions.get(name);
      return listed == null
          ? PublishedLookup.failed("http://registry/" + name, "HTTP 503")
          : PublishedLookup.found(listed, "http://registry/" + name);
    }
  }

  private static List<MtPin> sharedReaders() {
    return List.of(reader(ARCH_RULES, "1.0.1"), reader(DB_CORE, "1.0.1"));
  }

  /**
   * THE REPRODUCTION. arch-rules was released at 1.0.2 and db-core's content did not change, so
   * the content-addressed publish never uploaded db-core at 1.0.2. Moving the property would move
   * db-core to a version that does not exist — so the property does not move, and says why.
   */
  @Test
  void aSharedPropertyDoesNotMoveToAVersionOneOfItsReadersWasNeverPublishedAt() {
    Map<String, MtLatest> latest =
        PendingChanges.index(
            List.of(latest(Ecosystem.MAVEN, ARCH_RULES, "1.0.2"), latest(Ecosystem.MAVEN, DB_CORE, "1.0.1")));
    Registry registry =
        new Registry(Map.of(ARCH_RULES, List.of("1.0.1", "1.0.2"), DB_CORE, List.of("1.0.1")));

    PendingChanges.Resolution resolution =
        PendingChanges.resolve(sharedReaders(), latest, NOTHING_HELD, registry);

    assertTrue(resolution.planned().isEmpty(), resolution.toString());
    assertEquals(
        List.of(
            "property qits.integrations-quarkus.version: no version newer than 1.0.1 is published"
                + " for every artifact reading it (" + ARCH_RULES + ", " + DB_CORE + ")"),
        resolution.blocked());
    assertTrue(PendingChanges.forGroup(sharedReaders(), latest, SPLIT, "dependencies", registry).isEmpty());
  }

  @Test
  void aSharedPropertyEveryReaderHasAtTheNewVersionMovesOnceForAll() {
    Map<String, MtLatest> latest =
        PendingChanges.index(
            List.of(latest(Ecosystem.MAVEN, ARCH_RULES, "1.0.2"), latest(Ecosystem.MAVEN, DB_CORE, "1.0.2")));
    Registry registry =
        new Registry(
            Map.of(ARCH_RULES, List.of("1.0.1", "1.0.2"), DB_CORE, List.of("1.0.1", "1.0.2")));

    List<Change> changes =
        PendingChanges.forGroup(sharedReaders(), latest, SPLIT, "dependencies", registry);

    assertEquals(
        List.of(new Change("maven", "pom.xml", ARCH_RULES, "1.0.1", "1.0.2", PROPERTY)), changes,
        "one change for the one line, keyed to the first reader by name");
  }

  /**
   * The target is the highest version COMMON to the readers, not the newest of any one — and
   * db-core, behind by its own latest or not, constrains it all the same.
   */
  @Test
  void aSharedPropertyMovesToTheHighestVersionEveryReaderHasPublished() {
    Map<String, MtLatest> latest =
        PendingChanges.index(
            List.of(latest(Ecosystem.MAVEN, ARCH_RULES, "1.0.3"), latest(Ecosystem.MAVEN, DB_CORE, "1.0.2")));
    Registry registry =
        new Registry(
            Map.of(
                ARCH_RULES, List.of("1.0.1", "1.0.2", "1.0.3"),
                DB_CORE, List.of("1.0.1", "1.0.2")));

    PendingChanges.Resolution resolution =
        PendingChanges.resolve(sharedReaders(), latest, NOTHING_HELD, registry);

    assertEquals(
        List.of(new Change("maven", "pom.xml", ARCH_RULES, "1.0.1", "1.0.2", PROPERTY)),
        resolution.changes());
    assertTrue(resolution.blocked().isEmpty());
  }

  @Test
  void aSharedPropertyIsNotMovedToAPrereleaseEveryReaderHas() {
    Map<String, MtLatest> latest =
        PendingChanges.index(
            List.of(latest(Ecosystem.MAVEN, ARCH_RULES, "1.0.2"), latest(Ecosystem.MAVEN, DB_CORE, "1.0.1")));
    Registry registry =
        new Registry(
            Map.of(
                ARCH_RULES, List.of("1.0.1", "1.0.2", "1.1.0-SNAPSHOT"),
                DB_CORE, List.of("1.0.1", "1.1.0-SNAPSHOT")));

    PendingChanges.Resolution resolution =
        PendingChanges.resolve(sharedReaders(), latest, NOTHING_HELD, registry);

    assertTrue(resolution.planned().isEmpty(), resolution.toString());
    assertEquals(1, resolution.blocked().size());
  }

  @Test
  void aSharedPropertyWhosePublishedVersionsCannotBeReadIsNotMoved() {
    Map<String, MtLatest> latest =
        PendingChanges.index(
            List.of(latest(Ecosystem.MAVEN, ARCH_RULES, "1.0.2"), latest(Ecosystem.MAVEN, DB_CORE, "1.0.2")));
    Registry registry = new Registry(Map.of(ARCH_RULES, List.of("1.0.1", "1.0.2")));

    PendingChanges.Resolution resolution =
        PendingChanges.resolve(sharedReaders(), latest, NOTHING_HELD, registry);

    assertTrue(resolution.planned().isEmpty());
    assertEquals(
        List.of(
            "property qits.integrations-quarkus.version: the published versions of " + DB_CORE
                + " could not be read: HTTP 503"),
        resolution.blocked());
  }

  @Test
  void holdingOneReaderOfASharedPropertyHoldsTheProperty() {
    Map<String, MtLatest> latest =
        PendingChanges.index(
            List.of(latest(Ecosystem.MAVEN, ARCH_RULES, "1.0.2"), latest(Ecosystem.MAVEN, DB_CORE, "1.0.2")));
    Registry registry =
        new Registry(
            Map.of(ARCH_RULES, List.of("1.0.1", "1.0.2"), DB_CORE, List.of("1.0.1", "1.0.2")));

    PendingChanges.Resolution resolution =
        PendingChanges.resolve(sharedReaders(), latest, DB_CORE::equals, registry);

    assertTrue(resolution.planned().isEmpty());
    assertEquals(List.of(ARCH_RULES, DB_CORE), resolution.held());
    assertTrue(registry.asked.isEmpty(), "a held property asks no registry");
  }

  @Test
  void aSharedPropertyNoReaderIsBehindOnAsksNothing() {
    Map<String, MtLatest> latest =
        PendingChanges.index(
            List.of(latest(Ecosystem.MAVEN, ARCH_RULES, "1.0.1"), latest(Ecosystem.MAVEN, DB_CORE, "1.0.1")));

    PendingChanges.Resolution resolution =
        PendingChanges.resolve(sharedReaders(), latest, NOTHING_HELD, NO_REGISTRY);

    assertTrue(resolution.planned().isEmpty());
    assertTrue(resolution.blocked().isEmpty());
  }

  /** A property ONE artifact reads is the per-pin rule exactly, and asks no registry. */
  @Test
  void aPropertyOneArtifactReadsIsDecidedAsBeforeWithoutARegistry() {
    MtPin pin = reader(ARCH_RULES, "1.0.1");
    Map<String, MtLatest> latest =
        PendingChanges.index(List.of(latest(Ecosystem.MAVEN, ARCH_RULES, "1.0.2")));

    assertEquals(
        List.of(new Change("maven", "pom.xml", ARCH_RULES, "1.0.1", "1.0.2", PROPERTY)),
        PendingChanges.forGroup(List.of(pin), latest, SPLIT, "dependencies", NO_REGISTRY));
    // Two modules repeating the same reader are still one reader, and still ask nothing.
    assertFalse(
        PendingChanges.resolve(List.of(pin, reader(ARCH_RULES, "1.0.1")), latest, NOTHING_HELD, NO_REGISTRY)
            .planned()
            .isEmpty());
  }

  /** The same property name in two manifests is two lines, and neither is shared. */
  @Test
  void theSamePropertyInTwoManifestsIsNotShared() {
    MtPin other = reader(DB_CORE, "1.0.1");
    other.manifestPath = "other/pom.xml";
    Map<String, MtLatest> latest =
        PendingChanges.index(
            List.of(latest(Ecosystem.MAVEN, ARCH_RULES, "1.0.2"), latest(Ecosystem.MAVEN, DB_CORE, "1.0.2")));

    assertEquals(
        2,
        PendingChanges.forGroup(
                List.of(reader(ARCH_RULES, "1.0.1"), other), latest, SPLIT, "dependencies", NO_REGISTRY)
            .size());
  }
}
