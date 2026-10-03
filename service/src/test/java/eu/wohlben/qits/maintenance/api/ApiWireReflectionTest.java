package eu.wohlben.qits.maintenance.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.dto.RepositoryDto;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * That {@link ApiWireReflection} is COMPLETE, which is the only half of it a JVM test can guard.
 *
 * <p>On a JVM every record below binds whether anyone registered it or not, so nothing here can fail
 * for the reason the registration exists — the failure it guards is a 500 in the GraalVM binary with
 * a green suite behind it, and only {@code PackagedSurfaceIT} against a native build can see that
 * one. What this CAN do is fail in the commit that adds a response type and forgets the list, which
 * is the only way the list has ever gone wrong.
 *
 * <p><b>The set is enumerated rather than written out</b>, deliberately: a second hand-kept list
 * would rot in exactly the same way as the first, on the same day. Every record in the domain's
 * {@code dto} package is a shape this API serves — that is what the package IS — so the package is
 * read off the classpath and each record checked against the annotation.
 *
 * <p>The controller-nested request and response records are not enumerated here. They ride in {@code
 * Response.entity(...)}, which is the case the packaged IT exercises for real.
 */
class ApiWireReflectionTest {

  private static final RegisterForReflection REGISTRATION =
      ApiWireReflection.class.getAnnotation(RegisterForReflection.class);

  /** The package every shape this API serves lives in, and any nested record inside one. */
  private static final String DTO_PACKAGE = "eu.wohlben.qits.maintenance.dto";

  /**
   * A floor under the enumeration itself. Reading a jar or a target directory can come back empty
   * for reasons that have nothing to do with the list — a packaging change, a classloader that hands
   * out a different code source — and an empty sweep would pass silently for ever.
   */
  private static final int AT_LEAST = 15;

  @Test
  void everyDtoRecordTheApiServesIsRegisteredForReflection() throws Exception {
    Set<Class<?>> targets = Set.of(REGISTRATION.targets());
    List<Class<?>> records = dtoRecords();

    assertTrue(
        records.size() >= AT_LEAST,
        "the dto package sweep found only " + records.size() + " records, so it is not sweeping");
    for (Class<?> shape : records) {
      assertTrue(
          targets.contains(shape),
          shape.getSimpleName()
              + " is served by this API and is not in ApiWireReflection; in a native binary it is a"
              + " 500 and nothing else fails");
    }
  }

  /**
   * {@link MaintenanceStore.TreePin} is not a {@code dto} record, so the sweep below never sees it
   * — it is written as a JSON column by {@code MaintenanceStore.recordGitlinkTree}, which is exactly
   * the Jackson-touches-it-outside-a-declared-return-type case this class exists for. Guarded on its
   * own because the sweep's completeness check cannot reach a type outside {@code dto}.
   */
  @Test
  void treePinJsonColumnTypeIsRegisteredForReflection() {
    assertTrue(
        Set.of(REGISTRATION.targets()).contains(MaintenanceStore.TreePin.class),
        "MaintenanceStore.TreePin is written as mt_gitlink_tree's JSON column and is not in"
            + " ApiWireReflection; in a native binary that write dies with \"could not write a json"
            + " column\" and nothing else fails");
  }

  /** Every record in the dto package, nested ones included, off whatever the domain jar is. */
  private static List<Class<?>> dtoRecords() throws Exception {
    List<String> binaryNames = new ArrayList<>();
    Path source = Paths.get(codeSource());
    String prefix = DTO_PACKAGE.replace('.', '/') + "/";
    if (Files.isDirectory(source)) {
      Path directory = source.resolve(prefix);
      if (Files.isDirectory(directory)) {
        try (Stream<Path> files = Files.list(directory)) {
          files
              .map(file -> file.getFileName().toString())
              .filter(name -> name.endsWith(".class"))
              .forEach(name -> binaryNames.add(binaryName(prefix + name)));
        }
      }
    } else {
      try (JarFile jar = new JarFile(source.toFile())) {
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
          String name = entries.nextElement().getName();
          // The package itself and nothing under it: dto has no sub-packages, and a filter that
          // recursed would start reporting a neighbour's records as this API's.
          if (name.startsWith(prefix)
              && name.endsWith(".class")
              && name.indexOf('/', prefix.length()) < 0) {
            binaryNames.add(binaryName(name));
          }
        }
      }
    }

    List<Class<?>> records = new ArrayList<>();
    for (String binaryName : binaryNames) {
      Class<?> found = Class.forName(binaryName);
      if (found.isRecord()) {
        records.add(found);
      }
    }
    return records;
  }

  private static java.net.URI codeSource() throws URISyntaxException, IOException {
    return RepositoryDto.class.getProtectionDomain().getCodeSource().getLocation().toURI();
  }

  private static String binaryName(String entry) {
    return entry.substring(0, entry.length() - ".class".length()).replace('/', '.');
  }
}
