package com.trs.simulation.knowledge;

import com.trs.simulation.common.Json;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 装配可信部署注册表，场景和智能体只使用代号。 @author czhcc-trsdsj\codexsandboxoffline */
@Component
public final class KnowledgeProviders {
    private final Map<String, KnowledgeGraphProvider> providers;

    /** 支持程序化测试 Provider 和管理员设置的只读 MCP 源。 @author czhcc-trsdsj\codexsandboxoffline */
    public KnowledgeProviders(
            List<KnowledgeGraphProvider> registered, @Value("${studio.knowledge.provider-config:}") String configPath) {
        var configured = new HashMap<String, KnowledgeGraphProvider>();
        registered.forEach(provider -> add(configured, provider));
        if (!configPath.isBlank()) {
            var config = load(Path.of(configPath));
            for (var source : config.sources()) {
                add(configured, source.provider());
            }
        }
        this.providers = Map.copyOf(configured);
    }

    /** 从服务器注册表按代号选择 Provider。 @author czhcc-trsdsj\codexsandboxoffline */
    public KnowledgeGraphProvider get(String providerCode) {
        return providers.get(providerCode);
    }

    private static void add(Map<String, KnowledgeGraphProvider> providers, KnowledgeGraphProvider provider) {
        if (providers.putIfAbsent(provider.providerCode(), provider) != null) {
            throw new IllegalArgumentException("Duplicate knowledge provider");
        }
    }

    private static Config load(Path path) {
        try {
            if (Files.size(path) > 65_536) {
                throw new IllegalArgumentException();
            }
            return Json.read(Files.readString(path), Config.class);
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid knowledge provider configuration");
        }
    }

    /** 固定部署配置。 @author czhcc-trsdsj\codexsandboxoffline */
    private record Config(List<Source> sources) {}

    /** 读取环境变量的名称而不保留实际认证值。 @author czhcc-trsdsj\codexsandboxoffline */
    private record Source(
            String providerCode,
            URI endpoint,
            Map<KnowledgeGraphProvider.Operation, String> readOnlyTools,
            int timeoutMilliseconds,
            int maximumBytes,
            String credentialEnvironment) {
        KnowledgeGraphProvider provider() {
            if (credentialEnvironment != null && !credentialEnvironment.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) {
                throw new IllegalArgumentException("Invalid knowledge provider credential reference");
            }
            return new McpKnowledgeGraphProvider(
                    providerCode,
                    endpoint,
                    readOnlyTools,
                    Duration.ofMillis(timeoutMilliseconds),
                    maximumBytes,
                    this::credential);
        }

        private String credential() {
            if (credentialEnvironment == null) {
                return null;
            }
            var value = System.getenv(credentialEnvironment);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("Knowledge provider credential unavailable");
            }
            return value;
        }
    }
}
