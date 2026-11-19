package com.trs.studio;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One-time catalog reset removes legacy simulations but preserves accounts and models. @author czhcc-trsdsj\codexsandboxoffline */
class WorldCatalogMigrationTest {
  @TempDir Path directory;

  @Test
  void resetIsOneTimeAndRetainsAccountsAndCredentials() throws Exception {
    String url = "jdbc:sqlite:" + directory.resolve("migration.db");
    Flyway.configure().dataSource(url, null, null).target("5").load().migrate();
    try (var c = DriverManager.getConnection(url); var s = c.createStatement()) {
      s.executeUpdate("INSERT INTO user_account(username,password_hash) VALUES ('kept','not-a-real-hash')");
      s.executeUpdate("INSERT INTO model_config VALUES ('kept-model','{}')");
      s.executeUpdate("INSERT INTO world_definition VALUES ('old','{}')");
      s.executeUpdate("INSERT INTO scenario VALUES ('old-scenario','{}')");
      s.executeUpdate("INSERT INTO simulation_run(id,scenario_json,status,state_json,created_at) VALUES ('old-run','{}','PAUSED','{}',0)");
    }
    var migration = Flyway.configure().dataSource(url, null, null).load();
    assertTrue(migration.migrate().migrationsExecuted > 0);
    try (var c = DriverManager.getConnection(url); var s = c.createStatement()) {
      for (var table : java.util.List.of("world_definition", "scenario", "simulation_run")) {
        try (var r = s.executeQuery("SELECT COUNT(*) FROM " + table)) { r.next(); assertEquals(0, r.getInt(1)); }
      }
      for (var table : java.util.List.of("user_account", "model_config")) {
        try (var r = s.executeQuery("SELECT COUNT(*) FROM " + table)) { r.next(); assertEquals(1, r.getInt(1)); }
      }
      s.executeUpdate("INSERT INTO world_definition VALUES ('new-world','{}')");
    }
    assertEquals(0, migration.migrate().migrationsExecuted);
    try (var c = DriverManager.getConnection(url); var s = c.createStatement(); var r = s.executeQuery("SELECT id FROM world_definition")) {
      assertTrue(r.next()); assertEquals("new-world", r.getString(1));
    }
  }
}
