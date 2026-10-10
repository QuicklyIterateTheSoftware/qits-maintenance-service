package eu.wohlben.qits.maintenance.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * <b>V22 retires the group bumps (qits-1133 R5)</b>: the dispatch window's table is dropped, a GROUP
 * row still REQUESTED or RUNNING is closed NOTHING_TO_DO with the {@code converged} sentinel, a green
 * GROUP row still owed a release ask is closed with it too — and everything else, an automation row
 * still going included, is where it was.
 *
 * <p>Driven through Flyway directly against a database of its own, stopped at V21 so there are rows
 * for V22 to find.
 */
class GroupBumpRetirementMigrationTest {

  private static final String DATABASE = "maintenance_v22";

  private static Flyway flyway(String target) {
    return Flyway.configure()
        .dataSource(EmbeddedPg.url(DATABASE), EmbeddedPg.USER, EmbeddedPg.PASSWORD)
        .locations("classpath:db/maintenance/migration")
        .cleanDisabled(false)
        .target(target)
        .load();
  }

  @Test
  void theWindowIsDroppedAndEveryGroupRowStillGoingIsSettled() throws Exception {
    Flyway before = flyway("21");
    before.clean();
    before.migrate();

    UUID requested = UUID.randomUUID();
    UUID running = UUID.randomUUID();
    UUID owed = UUID.randomUUID();
    UUID released = UUID.randomUUID();
    UUID automation = UUID.randomUUID();
    try (Connection db = connect()) {
      insert(db, requested, "GROUP", "REQUESTED", null);
      insert(db, running, "GROUP", "RUNNING", null);
      insert(db, owed, "GROUP", "SUCCEEDED", null);
      insert(db, released, "GROUP", "SUCCEEDED", "rr-1");
      insert(db, automation, "AUTOMATION", "RUNNING", "rr-2");
      assertTrue(windowTableExists(db), "V10's table is there before");
    }

    flyway("22").migrate();

    try (Connection db = connect()) {
      assertFalse(windowTableExists(db), "mt_bump_window is gone");

      for (UUID id : new UUID[] {requested, running}) {
        String[] row = read(db, id);
        assertEquals("NOTHING_TO_DO", row[0]);
        assertEquals("converged", row[1]);
        assertTrue(row[2].contains("qits-1133"), row[2]);
        assertNotNull(row[3], "it is finished");
      }

      String[] wasOwed = read(db, owed);
      assertEquals("SUCCEEDED", wasOwed[0], "a green row keeps its verdict");
      assertEquals("converged", wasOwed[1], "and its release ask is closed");

      assertEquals("rr-1", read(db, released)[1], "an ask that was made is left alone");

      String[] stillGoing = read(db, automation);
      assertEquals("RUNNING", stillGoing[0], "an automation row is not the group path's");
      assertEquals("rr-2", stillGoing[1]);
      assertNull(stillGoing[3]);
    }
  }

  private static Connection connect() throws Exception {
    return DriverManager.getConnection(EmbeddedPg.url(DATABASE), EmbeddedPg.USER, EmbeddedPg.PASSWORD);
  }

  private static boolean windowTableExists(Connection db) throws Exception {
    try (PreparedStatement sql = db.prepareStatement("select to_regclass('mt_bump_window')");
        ResultSet row = sql.executeQuery()) {
      row.next();
      return row.getString(1) != null;
    }
  }

  private static void insert(Connection db, UUID id, String mode, String status, String request)
      throws Exception {
    try (PreparedStatement sql =
        db.prepareStatement(
            "insert into mt_bump (id, repository, group_name, branch, environment, trigger, status,"
                + " changes, started_at, mode, release_request_id)"
                + " values (?, 'qits-ci', 'dependencies', 'maintenance/dependencies', 'dev',"
                + " 'SCHEDULED', ?, '[]', ?, ?, ?)")) {
      sql.setObject(1, id);
      sql.setString(2, status);
      sql.setTimestamp(3, Timestamp.from(Instant.parse("2026-10-01T00:00:00Z")));
      sql.setString(4, mode);
      sql.setString(5, request);
      sql.executeUpdate();
    }
  }

  private static String[] read(Connection db, UUID id) throws Exception {
    try (PreparedStatement sql =
        db.prepareStatement(
            "select status, release_request_id, message, finished_at from mt_bump where id = ?")) {
      sql.setObject(1, id);
      try (ResultSet row = sql.executeQuery()) {
        row.next();
        return new String[] {row.getString(1), row.getString(2), row.getString(3), row.getString(4)};
      }
    }
  }
}
