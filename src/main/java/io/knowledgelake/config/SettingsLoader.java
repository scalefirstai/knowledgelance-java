package io.knowledgelake.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Loads {@link Settings} from YAML, then applies {@code KL_*} environment overrides:
 * {@code KL_EMBEDDING__PROVIDER=onnx} sets {@code embedding.provider}. Values parse as YAML
 * scalars, so {@code KL_RANKING__RECALL_K=50} is a number.
 */
public final class SettingsLoader {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private SettingsLoader() {}

    public static Settings load(String path) {
        return load(path, System.getenv());
    }

    public static Settings load(String path, Map<String, String> env) {
        String p = path != null ? path : env.get("KL_CONFIG");
        try {
            JsonNode tree;
            if (p != null) {
                tree = YAML.readTree(Files.readString(Path.of(p)));
            } else if (Files.exists(Path.of("kl.yaml"))) {
                tree = YAML.readTree(Files.readString(Path.of("kl.yaml")));
            } else {
                tree = null;
            }
            ObjectNode root = tree instanceof ObjectNode o ? o : JsonNodeFactory.instance.objectNode();
            applyEnv(root, env);
            return YAML.treeToValue(root, Settings.class);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot load settings from " + p, e);
        }
    }

    public static Settings fromYaml(String yaml) {
        try {
            JsonNode tree = YAML.readTree(yaml);
            return YAML.treeToValue(tree == null ? JsonNodeFactory.instance.objectNode() : tree, Settings.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void applyEnv(ObjectNode root, Map<String, String> env) throws IOException {
        for (var e : env.entrySet()) {
            String key = e.getKey();
            if (!key.startsWith("KL_") || key.equals("KL_CONFIG") || key.startsWith("KL_MCP_")) continue;
            String[] path = key.substring(3).toLowerCase(Locale.ROOT).split("__");
            ObjectNode node = root;
            for (int i = 0; i < path.length - 1; i++) {
                JsonNode child = node.get(path[i]);
                node = child instanceof ObjectNode o ? o : node.putObject(path[i]);
            }
            String raw = e.getValue();
            JsonNode value = raw.isEmpty() ? JsonNodeFactory.instance.textNode("") : YAML.readTree(raw);
            node.set(path[path.length - 1], value == null ? JsonNodeFactory.instance.nullNode() : value);
        }
    }
}
