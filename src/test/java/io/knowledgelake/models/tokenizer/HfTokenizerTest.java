package io.knowledgelake.models.tokenizer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Checks the pure-Java tokenizer against ids produced by the Rust {@code tokenizers} library
 * (fixture generated from the same tokenizer.json files). Skips a model when its tokenizer.json is
 * not in the local Hugging Face cache.
 */
public class HfTokenizerTest {

    public static Optional<Path> cached(String repo) {
        Path base = Path.of(System.getProperty("user.home"), ".cache", "huggingface", "hub",
                "models--" + repo.replace("/", "--"), "snapshots");
        if (!Files.isDirectory(base)) return Optional.empty();
        try (Stream<Path> s = Files.walk(base, 2)) {
            return s.filter(p -> p.getFileName().toString().equals("tokenizer.json")).findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    static long[] longs(JsonNode arr) {
        long[] out = new long[arr.size()];
        for (int i = 0; i < out.length; i++) out[i] = arr.get(i).asLong();
        return out;
    }

    @TestFactory
    List<DynamicTest> matchesRustTokenizers() throws IOException {
        JsonNode fixtures;
        try (InputStream in = getClass().getResourceAsStream("/tokenizer-fixtures.json")) {
            fixtures = new ObjectMapper().readTree(in);
        }
        List<DynamicTest> tests = new ArrayList<>();
        fixtures.properties().forEach(model -> {
            String name = model.getKey();
            JsonNode spec = model.getValue();
            Optional<Path> path = cached(spec.get("repo").asText());
            tests.add(DynamicTest.dynamicTest(name, () -> {
                assumeTrue(path.isPresent(), "tokenizer.json for " + name + " not cached");
                HfTokenizer tok = HfTokenizer.load(path.get(), 512);
                for (JsonNode c : spec.get("single")) {
                    String text = c.get("text").asText();
                    assertArrayEquals(longs(c.get("ids")), tok.encode(text).ids(),
                            () -> name + " single: " + text.substring(0, Math.min(60, text.length())));
                }
                for (JsonNode c : spec.get("pair")) {
                    var enc = tok.encodePair(c.get("a").asText(), c.get("b").asText());
                    assertArrayEquals(longs(c.get("ids")), enc.ids(), name + " pair ids");
                    assertArrayEquals(longs(c.get("type_ids")), enc.typeIds(), name + " pair type ids");
                }
            }));
        });
        return tests;
    }
}
