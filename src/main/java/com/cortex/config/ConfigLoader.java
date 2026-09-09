package com.cortex.config;

import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ConfigLoader {

    private static final Set<String> VALID_PROTOCOLS = Set.of("anthropic", "openai", "openai-compat");

    public static AppConfig load(String path) {
        Path configPath = Path.of(path);
        if (!Files.exists(configPath)) {
            throw new ConfigException("配置文件不存在: " + path);
        }
        if (!Files.isReadable(configPath)) {
            throw new ConfigException("配置文件不可读: " + path);
        }

        Map<String, Object> raw;
        try (InputStream in = new FileInputStream(configPath.toFile())) {
            raw = new Yaml().load(in);
        } catch (FileNotFoundException e) {
            throw new ConfigException("配置文件不存在: " + path, e);
        } catch (Exception e) {
            throw new ConfigException("YAML 解析失败: " + e.getMessage(), e);
        }

        if (raw == null || raw.isEmpty()) {
            throw new ConfigException("配置文件为空");
        }

        Object providersObj = raw.get("providers");
        if (!(providersObj instanceof List<?> providersList)) {
            throw new ConfigException("配置缺少 'providers' 列表");
        }

        List<ProviderConfig> providers = new ArrayList<>();
        for (int i = 0; i < providersList.size(); i++) {
            Object item = providersList.get(i);
            if (!(item instanceof Map<?, ?> providerMap)) {
                throw new ConfigException("providers[" + i + "] 格式错误，期望为对象");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) providerMap;
            providers.add(parseProvider(map, i));
        }

        if (providers.isEmpty()) {
            throw new ConfigException("providers 列表不能为空");
        }

        AppConfig config = new AppConfig();
        config.setProviders(providers);
        // 阶段12（N6）：SubAgent 后台开关（缺省 true——缺键时保持 null）
        config.setEnableSubAgentBackground(getBooleanOrNull(raw, "enable_sub_agent_background"));
        return config;
    }

    /** 缺键返回 null（与 getBoolean 的 false 缺省区分：N6 默认 true 依赖 null 语义）。 */
    private static Boolean getBooleanOrNull(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s && !s.isBlank()) return Boolean.parseBoolean(s.trim());
        return null;
    }

    private static ProviderConfig parseProvider(Map<String, Object> map, int index) {
        ProviderConfig pc = new ProviderConfig();

        String name = getString(map, "name");
        if (name == null || name.isBlank()) {
            throw new ConfigException("providers[" + index + "].name 不能为空");
        }
        pc.setName(name);

        String protocol = getString(map, "protocol");
        if (protocol == null || protocol.isBlank()) {
            throw new ConfigException("providers[" + index + "].protocol 不能为空");
        }
        if (!VALID_PROTOCOLS.contains(protocol)) {
            throw new ConfigException("providers[" + index + "].protocol 非法: '" + protocol
                    + "'，允许值: " + VALID_PROTOCOLS);
        }
        pc.setProtocol(protocol);

        String apiKey = getString(map, "api_key");
        if (apiKey == null || apiKey.isBlank()) {
            throw new ConfigException("providers[" + index + "].api_key 不能为空");
        }
        pc.setApiKey(apiKey);

        String model = getString(map, "model");
        if (model == null || model.isBlank()) {
            throw new ConfigException("providers[" + index + "].model 不能为空");
        }
        pc.setModel(model);

        pc.setBaseUrl(getString(map, "base_url"));
        pc.setThinking(getBoolean(map, "thinking"));
        pc.setContextWindow(getInt(map, "context_window"));

        return pc;
    }

    private static String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String s ? s : null;
    }

    private static boolean getBoolean(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return false;
    }

    private static int getInt(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Integer i) return i;
        if (v instanceof Long l) return l.intValue();
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }
}