package eu.wohlben.qits.maintenance.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.maintenance.testdb.EmbeddedPg;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * <b>V18 maps the two old modes onto the automations</b> (qits-978): a BASELINES row becomes an
 * AUTOMATION row of kind {@code screenshot-baselines}, a TARGETED row one of kind {@code
 * estate-pins}, and a GROUP row is untouched.
 *
 * <p>Driven through Flyway directly against a database of its own, stopped at V17 so there are rows
 * written the old way for V18 to find — the application's own boot migrates an empty schema, where
 * an {@code update} has nothing to say.
 */
class ReleaseRequestAutomationsMigrationTest {

  private static final String DATABASE = "maintenance_v18";

  private static Flyway flyway(String target) {
    return Flyway.configure()
        .dataSource(EmbeddedPg.url(DATABASE), EmbeddedPg.USER, EmbeddedPg.PASSWORD)
        .locations("classpath:db/maintenance/migration")
        .cleanDisabled(false)
        .target(target)
        .load();
  }

  @Test
  void theOldModesBecomeAutomationKindsAndAGroupBumpStaysAsItWas() throws Exception {
    Flyway before = flyway("17");
    before.clean();
    before.migrate();

    UUID baselines = UUID.randomUUID();
    UUID targeted = UUID.randomUUID();
    UUID group = UUID.randomUUID();
    try (Connection db =
        DriverManager.getConnection(
            EmbeddedPg.url(DATABASE), EmbeddedPg.USER, EmbeddedPg.PASSWORD)) {
      insert(db, baselines, "baselines", "maintenance/baselines/rr-1", "BASELINES", "rr-1");
      insert(db, targeted, "targeted", "workspace/ws-7", "TARGETED", null);
      insert(db, group, "dependencies", "maintenance/dependencies", "GROUP", null);
    }

    flyway("18").migrate();

    try (Connection db =
        DriverManager.getConnection(
            EmbeddedPg.url(DATABASE), EmbeddedPg.USER, EmbeddedPg.PASSWORD)) {
      String[] mapped = read(db, baselines);
      assertEquals("AUTOMATION", mapped[0]);
      assertEquals("screenshot-baselines", mapped[1]);
      assertEquals("rr-1", mapped[2], "the request a baselines row named is where it was");
      assertNull(mapped[3], "a row from before the column has no fold");

      String[] pins = read(db, targeted);
      assertEquals("AUTOMATION", pins[0]);
      assertEquals("estate-pins", pins[1]);
      assertNull(pins[2]);

      String[] untouched = read(db, group);
      assertEquals("GROUP", untouched[0]);
      assertNull(untouched[1], "a group bump is no automation");
    }
  }

  private static void insert(
      Connection db, UUID id, String group, String branch, String mode, String request)
      throws Exception {
    try (PreparedStatement sql =
        db.prepareStatement(
            "insert into mt_bump (id, repository, group_name, branch, environment, trigger, status,"
                + " changes, started_at, mode, release_request_id)"
                + " values (?, 'qits-ci', ?, ?, 'dev', 'MANUAL', 'SUCCEEDED', '[]', ?, ?, ?)")) {
      sql.setObject(1, id);
      sql.setString(2, group);
      sql.setString(3, branch);
      sql.setTimestamp(4, Timestamp.from(Instant.parse("2026-10-01T00:00:00Z")));
      sql.setString(5, mode);
      sql.setString(6, request);
      sql.executeUpdate();
    }
  }

  private static String[] read(Connection db, UUID id) throws Exception {
    try (PreparedStatement sql =
        db.prepareStatement(
            "select mode, automation_kind, release_request_id, fold_sha from mt_bump where id = ?")) {
      sql.setObject(1, id);
      try (ResultSet row = sql.executeQuery()) {
        row.next();
        return new String[] {row.getString(1), row.getString(2), row.getString(3), row.getString(4)};
      }
    }
  }
}
