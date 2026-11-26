package com.trs.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One-time catalog reset removes legacy simulations but preserves accounts and models. @author czhcc-trsdsj\codexsandboxoffline */
class WorldCatalogMigrationTest {
    @TempDir
    Path directory;

    @Test
    void v10PreservesExistingV9RunsAndTheirSnapshotAndAuditBytes() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("v9.db");
        Flyway.configure().dataSource(url, null, null).target("9").load().migrate();
        try (var connection = DriverManager.getConnection(url);
                var sql = connection.createStatement()) {
            sql.executeUpdate("""
                    INSERT INTO simulation_run(id,scenario_json,status,state_json,created_at)
                    VALUES ('existing','{}','PAUSED','{"version":0,"entities":[],"relations":[]}',0)
                    """);
            sql.executeUpdate("""
                    INSERT INTO simulation_snapshot
                    VALUES ('existing',0,'{"version":0,"entities":[],"relations":[]}')
                    """);
            sql.executeUpdate("""
                    INSERT INTO simulation_event VALUES ('existing',1,'ACTION_ACCEPTED',0,'legacy','{"targetId":"root"}')
                    """);
        }
        assertEquals(
                1,
                Flyway.configure()
                        .dataSource(url, null, null)
                        .target("10")
                        .load()
                        .migrate()
                        .migrationsExecuted);
        try (var connection = DriverManager.getConnection(url);
                var sql = connection.createStatement();
                var snapshot = sql.executeQuery("SELECT state_json FROM simulation_snapshot WHERE run_id='existing'")) {
            assertTrue(snapshot.next());
            assertEquals("{\"version\":0,\"entities\":[],\"relations\":[]}", snapshot.getString(1));
            try (var audit = connection.createStatement();
                    var event =
                            audit.executeQuery("SELECT payload_json FROM simulation_event WHERE run_id='existing'")) {
                assertTrue(event.next());
                assertEquals("{\"targetId\":\"root\"}", event.getString(1));
            }
        }
    }

    @Test
    void v11KeepsTheLegacyScenarioAndDeliveryMarkersOnDisk() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("v10.db");
        Flyway.configure().dataSource(url, null, null).target("10").load().migrate();
        try (var connection = DriverManager.getConnection(url);
                var sql = connection.createStatement()) {
            sql.executeUpdate("""
                    INSERT INTO simulation_run(id,scenario_json,status,state_json,created_at)
                    VALUES ('legacy','{"name":"old"}','PAUSED','{}',0)
                    """);
            sql.executeUpdate("""
                    INSERT INTO simulation_scheduled_event
                        (run_id,event_id,simulation_time,payload_json,delivered,created_sequence)
                    VALUES ('legacy','done',1,'{}',1,4),('legacy','waiting',2,'{}',0,5)
                    """);
        }
        var migration = Flyway.configure().dataSource(url, null, null).load();
        assertEquals(1, migration.migrate().migrationsExecuted);
        assertEquals(0, migration.migrate().migrationsExecuted);
        try (var connection = DriverManager.getConnection(url);
                var sql = connection.createStatement();
                var rows = sql.executeQuery("""
                        SELECT scenario_json,event_id,delivered,work_status,enqueue_sequence
                        FROM simulation_run JOIN simulation_scheduled_event ON id=run_id
                        ORDER BY enqueue_sequence
                        """)) {
            assertTrue(rows.next());
            assertEquals("{\"name\":\"old\"}", rows.getString(1));
            assertEquals("done", rows.getString(2));
            assertEquals(1, rows.getInt(3));
            assertEquals("COMPLETED", rows.getString(4));
            assertEquals(4, rows.getLong(5));
            assertTrue(rows.next());
            assertEquals("waiting", rows.getString(2));
            assertEquals(0, rows.getInt(3));
            assertEquals("PENDING", rows.getString(4));
            assertEquals(5, rows.getLong(5));
        }
    }

    @Test
    void resetIsOneTimeAndRetainsAccountsAndCredentials() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("migration.db");
        Flyway.configure().dataSource(url, null, null).target("5").load().migrate();
        try (var c = DriverManager.getConnection(url);
                var s = c.createStatement()) {
            s.executeUpdate("INSERT INTO user_account(username,password_hash) VALUES ('kept','not-a-real-hash')");
            s.executeUpdate("INSERT INTO model_config VALUES ('kept-model','{}')");
            s.executeUpdate("INSERT INTO world_definition VALUES ('old','{}')");
            s.executeUpdate("INSERT INTO scenario VALUES ('old-scenario','{}')");
            s.executeUpdate(
                    "INSERT INTO simulation_run(id,scenario_json,status,state_json,created_at) VALUES ('old-run','{}','PAUSED','{}',0)");
        }
        var migration = Flyway.configure().dataSource(url, null, null).load();
        assertTrue(migration.migrate().migrationsExecuted > 0);
        try (var c = DriverManager.getConnection(url);
                var s = c.createStatement()) {
            for (var table : java.util.List.of("world_definition", "scenario", "simulation_run")) {
                try (var r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    r.next();
                    assertEquals(0, r.getInt(1));
                }
            }
            for (var table : java.util.List.of("user_account", "model_config")) {
                try (var r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    r.next();
                    assertEquals(1, r.getInt(1));
                }
            }
            s.executeUpdate("INSERT INTO world_definition VALUES ('new-world','{}')");
        }
        assertEquals(0, migration.migrate().migrationsExecuted);
        try (var c = DriverManager.getConnection(url);
                var s = c.createStatement();
                var r = s.executeQuery("SELECT id FROM world_definition")) {
            assertTrue(r.next());
            assertEquals("new-world", r.getString(1));
        }
    }
}
