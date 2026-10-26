package io.knowledgelake.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.knowledgelake.auth.Identity;
import io.knowledgelake.config.Settings;
import io.knowledgelake.search.SearchModels.ChunkHit;
import io.knowledgelake.search.SearchModels.ConceptHit;
import io.knowledgelake.search.SearchModels.SearchRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional RAG answers with citations, via an in-network OpenAI-compatible server (vLLM, Ollama).
 *
 * <p>Disabled unless {@code answer.base_url} is set. Context is built only from chunks the
 * caller's search returned, so ACLs carry through: the model never sees text the caller could not
 * open.
 */
public final class Answers {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SYSTEM = "You answer questions using only the numbered sources provided. Cite sources "
            + "inline as [n]. If the sources do not contain the answer, say so plainly. Do not invent facts.";

    private Answers() {}

    public static class AnswerUnavailable extends RuntimeException {
        public AnswerUnavailable(String m) {
            super(m);
        }
    }

    public static Map<String, Object> answer(SearchEngine engine, Settings.Answer cfg, SearchRequest req, Identity ident)
            throws Exception {
        if (cfg.baseUrl == null || cfg.baseUrl.isBlank()) throw new AnswerUnavailable("answer.base_url is not configured");
        var resp = engine.search(req, ident);
        List<Map<String, Object>> sources = new ArrayList<>();
        List<String> blocks = new ArrayList<>();
        outer:
        for (ConceptHit hit : resp.results()) {
            for (ChunkHit ch : hit.chunks()) {
                if (sources.size() >= cfg.maxContextChunks) break outer;
                int n = sources.size() + 1;
                Map<String, Object> src = new LinkedHashMap<>();
                src.put("n", n);
                src.put("concept_id", hit.conceptId());
                src.put("title", hit.title());
                src.put("chunk_id", ch.chunkId());
                src.put("resource_url", hit.resourceUrl());
                sources.add(src);
                blocks.add("[" + n + "] " + hit.title() + " — " + ch.headingPath() + "\n" + ch.text());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (sources.isEmpty()) {
            out.put("answer", "No accessible sources matched the question.");
            out.put("sources", List.of());
            return out;
        }
        Map<String, Object> payload = Map.of(
                "model", cfg.model,
                "temperature", 0.1,
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM),
                        Map.of("role", "user", "content", "Sources:\n\n" + String.join("\n\n", blocks) + "\n\nQuestion: " + req.q)));
        HttpRequest http = HttpRequest.newBuilder(URI.create(cfg.baseUrl.replaceAll("/+$", "") + "/chat/completions"))
                .timeout(Duration.ofMillis((long) (cfg.timeoutS * 1000)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(payload)))
                .build();
        HttpResponse<String> r = HttpClient.newHttpClient().send(http, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) throw new IllegalStateException("answer model returned HTTP " + r.statusCode());
        JsonNode body = JSON.readTree(r.body());
        out.put("answer", body.path("choices").path(0).path("message").path("content").asText());
        out.put("sources", sources);
        out.put("search", Map.of("timings_ms", resp.timingsMs()));
        return out;
    }
}
