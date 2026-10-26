package io.knowledgelake.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.knowledgelake.config.Settings;
import io.knowledgelake.models.tokenizer.HfTokenizerTest;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Java ONNX embeddings and rerank scores match the Python implementation on the same models. */
class OnnxParityTest {

    static double cosine(float[] a, JsonNode b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            double y = b.get(i).asDouble();
            dot += a[i] * y;
            na += a[i] * a[i];
            nb += y * y;
        }
        return dot / Math.sqrt(na * nb);
    }

    @Test
    void embeddingsAndRerankMatchPython() throws Exception {
        assumeTrue(HfTokenizerTest.cached("Xenova/bge-small-en-v1.5").isPresent(), "bge-small not cached");
        assumeTrue(HfTokenizerTest.cached("Xenova/ms-marco-MiniLM-L-6-v2").isPresent(), "MiniLM not cached");
        JsonNode fx;
        try (InputStream in = getClass().getResourceAsStream("/model-fixtures.json")) {
            fx = new ObjectMapper().readTree(in);
        }
        Settings.Embedding ecfg = new Settings.Embedding();
        ecfg.provider = "onnx";
        Settings.Rerank rcfg = new Settings.Rerank();
        rcfg.provider = "onnx";
        var emb = new Models.OnnxEmbedder(ecfg, null);
        var rr = new Models.OnnxReranker(rcfg, null);
        List<String> docs = new ArrayList<>();
        fx.get("docs").forEach(d -> docs.add(d.asText()));
        float[][] vecs = emb.embedDocuments(docs);
        for (int i = 0; i < docs.size(); i++) {
            assertTrue(cosine(vecs[i], fx.get("doc_vectors").get(i)) > 0.9999, "doc vector " + i);
        }
        assertTrue(cosine(emb.embedQuery(fx.get("query").asText()), fx.get("query_vector")) > 0.9999);
        float[] scores = rr.score(fx.get("query").asText(), docs);
        for (int i = 0; i < scores.length; i++) {
            assertEquals(fx.get("rerank_scores").get(i).asDouble(), scores[i], 1e-4, "rerank " + i);
        }
    }
}
