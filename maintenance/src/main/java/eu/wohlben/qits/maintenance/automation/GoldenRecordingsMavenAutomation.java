package eu.wohlben.qits.maintenance.automation;

import eu.wohlben.qits.maintenance.githost.FileLookup;
import eu.wohlben.qits.maintenance.manifest.ParsedPin;
import eu.wohlben.qits.maintenance.manifest.Xml;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * <b>Golden recordings, maven</b> (qits-1516): a maven consumer's {@code pacts/*.json}, re-recorded
 * when the fold moved a {@code qits.<app>-golden-masters.version} — see {@link
 * GoldenRecordingsAutomation} for the whole of the kind.
 *
 * <p><b>Owns a fold with a root {@code pom.xml}.</b> It applies when a reactor pom — the root, then
 * its {@code <modules>} two levels deep, read only until one answers — declares a property named
 * {@code qits.*-golden-masters.version} or a dependency whose {@code <artifactId>} ends in {@code
 * -golden-masters}. On this estate the root pom declares the property, so the answer is the one
 * root-pom read {@code entity-diagram} already made at the same fold.
 */
@ApplicationScoped
public class GoldenRecordingsMavenAutomation extends GoldenRecordingsAutomation {

  public static final String KIND = "golden-recordings-maven";

  /** The property a consumer pins a provider's golden masters at. */
  static final Pattern PROPERTY = Pattern.compile("qits\\..+-golden-masters\\.version");

  static final String ARTIFACT_SUFFIX = "-golden-masters";

  @Override
  public String kind() {
    return KIND;
  }

  @Override
  public String label() {
    return "Golden recordings (maven)";
  }

  @Override
  Ecosystem ecosystem() {
    return Ecosystem.MAVEN;
  }

  @Override
  Applicability shape(FoldReader fold) {
    FileLookup pom = fold.file(POM);
    return switch (pom.status()) {
      case FOUND -> Applicability.applies();
      case ABSENT -> Applicability.notApplicable("no root pom.xml, so no maven build records pacts");
      default -> Applicability.unknown(unread(POM, pom));
    };
  }

  @Override
  Applicability pinsGoldenMasters(FoldReader fold) {
    Element root = Xml.root(fold.file(POM).content());
    if (root == null) {
      return Applicability.notApplicable("the root pom.xml does not parse");
    }
    if (pins(root)) {
      return Applicability.applies();
    }
    for (String module : modules(root)) {
      String path = module + "/" + POM;
      FileLookup pom = fold.file(path);
      switch (pom.status()) {
        case FOUND -> {
          Element moduleRoot = Xml.root(pom.content());
          if (moduleRoot == null) {
            continue;
          }
          if (pins(moduleRoot)) {
            return Applicability.applies();
          }
          for (String subModule : modules(moduleRoot)) {
            String subPath = module + "/" + subModule + "/" + POM;
            FileLookup subPom = fold.file(subPath);
            switch (subPom.status()) {
              case FOUND -> {
                Element subRoot = Xml.root(subPom.content());
                if (subRoot != null && pins(subRoot)) {
                  return Applicability.applies();
                }
              }
              case ABSENT -> {
                // No pom where a module was declared: nothing to read there.
              }
              default -> {
                return Applicability.unknown(unread(subPath, subPom));
              }
            }
          }
        }
        case ABSENT -> {
          // No pom where a module was declared: nothing to read there.
        }
        default -> {
          return Applicability.unknown(unread(path, pom));
        }
      }
    }
    return Applicability.notApplicable("the reactor pins no *-golden-masters, so it records no pacts");
  }

  /**
   * A {@code qits.*-golden-masters.version} property, or a {@code *-golden-masters} pin of any
   * artifact through one, a {@code <version>} or {@code ${project.version}}.
   */
  @Override
  boolean goldenMasters(ParsedPin pin) {
    String location = pin.location() == null ? "" : pin.location();
    if (location.startsWith("property:")
        && PROPERTY.matcher(location.substring("property:".length())).matches()) {
      return true;
    }
    String name = pin.name() == null ? "" : pin.name();
    return name.substring(name.lastIndexOf(':') + 1).endsWith(ARTIFACT_SUFFIX);
  }

  /** Whether one parsed {@code <project>} declares the property or the dependency. */
  private static boolean pins(Element project) {
    Element properties = Xml.child(project, "properties");
    if (properties != null) {
      NodeList children = properties.getChildNodes();
      for (int i = 0; i < children.getLength(); i++) {
        Node node = children.item(i);
        if (node.getNodeType() == Node.ELEMENT_NODE
            && PROPERTY.matcher(node.getNodeName()).matches()) {
          return true;
        }
      }
    }
    List<Element> blocks = new ArrayList<>();
    blocks.add(Xml.child(project, "dependencies"));
    blocks.add(Xml.child(Xml.child(project, "dependencyManagement"), "dependencies"));
    for (Element block : blocks) {
      for (Element dependency : Xml.children(block, "dependency")) {
        String artifactId = Xml.childText(dependency, "artifactId");
        if (artifactId != null && artifactId.trim().endsWith(ARTIFACT_SUFFIX)) {
          return true;
        }
      }
    }
    return false;
  }

  /** The {@code <modules>} a parsed {@code <project>} lists, in file order. */
  private static List<String> modules(Element project) {
    List<String> out = new ArrayList<>();
    for (Element module : Xml.children(Xml.child(project, "modules"), "module")) {
      String text = Xml.text(module).trim();
      if (!text.isEmpty()) {
        out.add(text);
      }
    }
    return out;
  }
}
