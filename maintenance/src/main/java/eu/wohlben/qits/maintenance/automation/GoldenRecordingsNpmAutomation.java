package eu.wohlben.qits.maintenance.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.maintenance.githost.FileLookup;
import eu.wohlben.qits.maintenance.manifest.ParsedPin;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * <b>Golden recordings, npm</b> (qits-1516): an npm consumer's {@code pacts/*.json}, re-recorded
 * when the fold moved an {@code @qits/<app>-golden-masters} dependency — see {@link
 * GoldenRecordingsAutomation} for the whole of the kind.
 *
 * <p><b>Owns a fold with a root {@code package.json} and NO root {@code pom.xml}</b>: a root pom is
 * {@link GoldenRecordingsMavenAutomation}'s, whatever else the root holds, which is what keeps the
 * two kinds from both naming one fold's pacts. It applies when that {@code package.json} names an
 * {@code @qits/*-golden-masters} under {@code dependencies} or {@code devDependencies}: the pom
 * read (shared with the other kinds of the settle) and one {@code package.json} read.
 */
@ApplicationScoped
public class GoldenRecordingsNpmAutomation extends GoldenRecordingsAutomation {

  public static final String KIND = "golden-recordings-npm";

  /** The package a consumer pins a provider's golden masters as. */
  static final Pattern PACKAGE = Pattern.compile("@qits/.+-golden-masters");

  private static final List<String> BLOCKS = List.of("dependencies", "devDependencies");

  private static final ObjectMapper JSON = new ObjectMapper();

  @Override
  public String kind() {
    return KIND;
  }

  @Override
  public String label() {
    return "Golden recordings (npm)";
  }

  @Override
  Ecosystem ecosystem() {
    return Ecosystem.NPM;
  }

  @Override
  Applicability shape(FoldReader fold) {
    FileLookup pom = fold.file(POM);
    switch (pom.status()) {
      case FOUND -> {
        return Applicability.notApplicable(
            "a root pom.xml: a maven build records the pacts (" + GoldenRecordingsMavenAutomation.KIND
                + ")");
      }
      case ABSENT -> {}
      default -> {
        return Applicability.unknown(unread(POM, pom));
      }
    }
    FileLookup manifest = fold.file(PACKAGE_JSON);
    return switch (manifest.status()) {
      case FOUND -> Applicability.applies();
      case ABSENT ->
          Applicability.notApplicable("no root package.json, so no npm build records pacts");
      default -> Applicability.unknown(unread(PACKAGE_JSON, manifest));
    };
  }

  @Override
  Applicability pinsGoldenMasters(FoldReader fold) {
    JsonNode manifest;
    try {
      manifest = JSON.readTree(fold.file(PACKAGE_JSON).content());
    } catch (Exception e) {
      manifest = null;
    }
    if (manifest == null || !manifest.isObject()) {
      return Applicability.notApplicable("the root package.json does not parse");
    }
    for (String block : BLOCKS) {
      JsonNode dependencies = manifest.get(block);
      if (dependencies == null || !dependencies.isObject()) {
        continue;
      }
      Iterator<String> names = dependencies.fieldNames();
      while (names.hasNext()) {
        if (PACKAGE.matcher(names.next()).matches()) {
          return Applicability.applies();
        }
      }
    }
    return Applicability.notApplicable(
        "package.json pins no @qits/*-golden-masters, so it records no pacts");
  }

  @Override
  boolean goldenMasters(ParsedPin pin) {
    return pin.name() != null && PACKAGE.matcher(pin.name()).matches();
  }
}
