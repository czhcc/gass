package com.trs.simulation;

import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Model settings; API keys encrypted locally or referenced from environment bindings.
 * @author czhcc-trsdsj\codexsandboxoffline
 */
@Service
public class ModelService {
  /** Request-only secret fields are never echoed by the API. @author agent */
  public record Input(
      String name,
      String baseUrl,
      String modelName,
      String apiKey,
      String apiKeyEnv,
      double temperature,
      int maxTokens,
      int timeoutSeconds,
      int concurrency) {}

  /** Stored configuration holds ciphertext, never plaintext credentials. @author agent */
  public record Config(
      String id,
      String name,
      String baseUrl,
      String modelName,
      String keyCipher,
      String apiKeyEnv,
      double temperature,
      int maxTokens,
      int timeoutSeconds,
      int concurrency) {}

  /** Safe client view. @author agent */
  public record View(
      String id,
      String name,
      String baseUrl,
      String modelName,
      boolean keyConfigured,
      String apiKeyEnv,
      double temperature,
      int maxTokens,
      int timeoutSeconds,
      int concurrency) {}

  private final JdbcTemplate db;
  private final Environment env;
  private final Path keyFile;

  /** Injects persistence and the durable master-key location. @author agent */
  public ModelService(
      JdbcTemplate db,
      Environment env,
      @Value("${studio.model-key-file:./data/model.key}") String keyFile) {
    this.db = db;
    this.env = env;
    this.keyFile = Path.of(keyFile);
  }

  /** Saves a validated model, encrypting an optional key. @author agent */
  public synchronized View save(Input i) {
    return persist(UUID.randomUUID().toString(), i, null);
  }

  /** Updates an existing model without changing references or revealing its key. @author agent */
  public synchronized View update(String id, Input i) {
    return persist(id, i, get(id));
  }

  private View persist(String id, Input i, Config existing) {
    if (i == null
        || i.name() == null
        || i.name().isBlank()
        || i.modelName() == null
        || i.modelName().isBlank()) throw new IllegalArgumentException("Model name required");
    URI uri;
    try {
      uri = URI.create(i.baseUrl());
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid Base URL");
    }
    if (uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || (!List.of("http", "https").contains(uri.getScheme())
            && !i.baseUrl().equals("mock://local")))
      throw new IllegalArgumentException("Use an HTTP(S) Base URL without credentials/query");
    if (i.temperature() < 0
        || i.temperature() > 2
        || !Double.isFinite(i.temperature())
        || i.maxTokens() < 64
        || i.maxTokens() > 8192
        || i.timeoutSeconds() < 1
        || i.timeoutSeconds() > 60
        || i.concurrency() < 1
        || i.concurrency() > 8) throw new IllegalArgumentException("Invalid model limits");
    if (i.apiKeyEnv() != null
        && !i.apiKeyEnv().isBlank()
        && !i.apiKeyEnv().matches("[A-Z_][A-Z0-9_]*"))
      throw new IllegalArgumentException("Invalid credential variable name");
    String cipher;
    if (i.apiKey() != null && !i.apiKey().isBlank()) cipher = encrypt(i.apiKey());
    else if (i.apiKeyEnv() != null
        && !i.apiKeyEnv().isBlank()
        && (existing == null || !Objects.equals(i.apiKeyEnv(), existing.apiKeyEnv())))
      cipher = null;
    else cipher = existing == null ? null : existing.keyCipher();
    var c =
        new Config(
            id,
            i.name(),
            i.baseUrl().replaceAll("/+$", ""),
            i.modelName(),
            cipher,
            i.apiKeyEnv(),
            i.temperature(),
            i.maxTokens(),
            i.timeoutSeconds(),
            i.concurrency());
    if (existing == null) db.update("INSERT INTO model_config VALUES (?,?)", c.id(), Json.write(c));
    else db.update("UPDATE model_config SET config_json=? WHERE id=?", Json.write(c), c.id());
    return view(c);
  }

  /**
   * Creates or reuses an explicitly simulated model with no external network access. @author agent
   */
  public synchronized View mock() {
    return list().stream()
        .filter(v -> v.baseUrl().equals("mock://local"))
        .findFirst()
        .orElseGet(
            () ->
                save(
                    new Input(
                        "模拟模型 / Simulated model",
                        "mock://local",
                        "SIMULATED_MODEL",
                        null,
                        null,
                        0,
                        1024,
                        30,
                        1)));
  }

  /** Lists only safe fields. @author agent */
  public List<View> list() {
    return db.query(
        "SELECT config_json FROM model_config ORDER BY id",
        (r, n) -> view(Json.read(r.getString(1), Config.class)));
  }

  /** Loads internal settings for the SDK adapter only. @author agent */
  public Config get(String id) {
    return db
        .query(
            "SELECT config_json FROM model_config WHERE id=?",
            (r, n) -> Json.read(r.getString(1), Config.class),
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new NoSuchElementException("Model not found"));
  }

  /** Resolves credentials without logging them. @author agent */
  public String credential(Config c) {
    if (c.keyCipher() != null) return decrypt(c.keyCipher());
    if (c.apiKeyEnv() != null && !c.apiKeyEnv().isBlank()) {
      var key = env.getProperty(c.apiKeyEnv());
      if (key == null || key.isBlank())
        throw new IllegalStateException("Model credential binding is unavailable");
      return key;
    }
    return "local-no-key";
  }

  private View view(Config c) {
    return new View(
        c.id(),
        c.name(),
        c.baseUrl(),
        c.modelName(),
        c.keyCipher() != null || (c.apiKeyEnv() != null && env.containsProperty(c.apiKeyEnv())),
        c.apiKeyEnv(),
        c.temperature(),
        c.maxTokens(),
        c.timeoutSeconds(),
        c.concurrency());
  }

  private synchronized byte[] master() {
    try {
      if (!Files.exists(keyFile)) {
        Path parent = keyFile.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        if (Files.getFileStore(parent).supportsFileAttributeView(PosixFileAttributeView.class)) {
          Files.createFile(
              keyFile,
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
          // Windows uses native inherited ACLs rather than POSIX permission attributes.
          Files.createFile(keyFile);
        }
        Files.write(keyFile, key);
      }
      return Files.readAllBytes(keyFile);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot access durable model encryption key", e);
    }
  }

  private String encrypt(String value) {
    try {
      byte[] iv = new byte[12];
      new SecureRandom().nextBytes(iv);
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE, new SecretKeySpec(master(), "AES"), new GCMParameterSpec(128, iv));
      return Base64.getEncoder().encodeToString(iv)
          + ":"
          + Base64.getEncoder()
              .encodeToString(
                  cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("Cannot encrypt model credential");
    }
  }

  private String decrypt(String value) {
    try {
      var parts = value.split(":");
      var c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(master(), "AES"),
          new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
      return new String(
          c.doFinal(Base64.getDecoder().decode(parts[1])), java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot decrypt model credential");
    }
  }
}
