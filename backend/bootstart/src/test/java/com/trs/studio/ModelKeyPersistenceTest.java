package com.trs.studio;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.trs.simulation.Json;
import com.trs.simulation.ModelService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies durable credential encryption on the host filesystem.
 * @author czhcc-trsdsj\codexsandboxoffline
 */
class ModelKeyPersistenceTest {
  @TempDir Path directory;

  @Test
  void savesEncryptedCredentialsAndReusesKeyAfterRestart() throws Exception {
    var db = mock(JdbcTemplate.class);
    var keyFile = directory.resolve("nested/model.key");
    var environment = new StandardEnvironment();
    var models = new ModelService(db, environment, keyFile.toString());
    var view = models.save(new ModelService.Input(
        "Provider", "https://example.com/v1", "model", "test-secret", null,
        0.2, 512, 10, 1));

    var json = ArgumentCaptor.forClass(String.class);
    verify(db).update(eq("INSERT INTO model_config VALUES (?,?)"), eq(view.id()), json.capture());
    assertTrue(view.keyConfigured());
    assertFalse(json.getValue().contains("test-secret"));
    var stored = Json.read(json.getValue(), ModelService.Config.class);
    assertEquals("test-secret", models.credential(stored));
    byte[] originalKey = Files.readAllBytes(keyFile);
    assertEquals(32, originalKey.length);

    var restarted = new ModelService(db, environment, keyFile.toString());
    assertEquals("test-secret", restarted.credential(stored));
    assertArrayEquals(originalKey, Files.readAllBytes(keyFile));
    if (Files.getFileStore(keyFile).supportsFileAttributeView(PosixFileAttributeView.class)) {
      assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(keyFile));
    }
  }
}
