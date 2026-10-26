package io.knowledgelake.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.knowledgelake.TestEnv;
import io.knowledgelake.index.Events;
import io.knowledgelake.search.Evaluation;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InterfacesTest {
    static final String RUNBOOK = "confluence/spaces/NAV/nav-cutoff-runbook";
    static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path tmp;
    TestEnv env;
    ApiServer api;
    HttpClient http = HttpClient.newHttpClient();
    String base;

    @BeforeEach
    void setUp() throws Exception {
        env = new TestEnv(tmp);
        env.settings.auth.mode = "header";
        api = new ApiServer(env.rt, "127.0.0.1", 0, true);
        api.start();
        base = "http://127.0.0.1:" + api.port();
    }

    @AfterEach
    void tearDown() {
        api.close();
        env.close();
    }

    HttpResponse<String> call(String method, String path, String body, String user, String groups) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
        if (user != null) b.header("X-KL-User", user).header("X-KL-Groups", groups);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> nav(String method, String path, String body) throws Exception {
        return call(method, path, body, "alice", "nav-ops,fund-ops");
    }

    @Test
    void restRequiresIdentity() throws Exception {
        assertEquals(401, call("POST", "/v1/search", "{\"q\":\"late trades\"}", null, null).statusCode());
    }

    @Test
    void restSearchAndConcept() throws Exception {
        var r = nav("POST", "/v1/search", "{\"q\":\"late trades\",\"filters\":{\"type\":[\"Runbook\"]},\"k\":3}");
        assertEquals(200, r.statusCode());
        JsonNode body = JSON.readTree(r.body());
        assertEquals(1, body.get("results").size());
        assertEquals(RUNBOOK, body.get("results").get(0).get("concept_id").asText());
        assertTrue(body.has("timings_ms"));
        assertTrue(body.get("results").get(0).get("chunks").get(0).get("breakdown").get("rrf").asDouble() > 0);
        assertEquals("Runbook", JSON.readTree(nav("GET", "/v1/concepts/" + RUNBOOK, null).body()).get("type").asText());
        assertEquals(404, call("GET", "/v1/concepts/" + RUNBOOK, null, "eve", "contractors").statusCode());
        List<String> audit = Files.readAllLines(tmp.resolve("kl/state/audit.jsonl"));
        JsonNode entry = JSON.readTree(audit.get(0));
        assertEquals("alice", entry.get("user").asText());
        assertTrue(entry.has("query_sha256"));
        assertFalse(audit.get(0).contains("late trades"));
    }

    @Test
    void restValidation() throws Exception {
        assertEquals(422, nav("POST", "/v1/search", "{\"q\":\"x\",\"k\":1000}").statusCode());
        assertEquals(404, nav("GET", "/v1/nope", null).statusCode());
    }

    @Test
    void restBrowseRelatedChanges() throws Exception {
        assertTrue(JSON.readTree(nav("GET", "/v1/browse?path=confluence/spaces/NAV", null).body()).get("index").asText().contains("NAV operations"));
        JsonNode rel = JSON.readTree(nav("GET", "/v1/related/jira/projects/FUND/FUND-1423", null).body()).get("related");
        boolean found = false;
        for (JsonNode n : rel) found |= n.get("concept_id").asText().equals(RUNBOOK);
        assertTrue(found);
        assertTrue(JSON.readTree(nav("GET", "/v1/changes?bundle=jira", null).body()).get("changes").size() > 0);
    }

    @Test
    void restAnswerDisabledByDefault() throws Exception {
        assertEquals(501, nav("POST", "/v1/answer", "{\"q\":\"x\"}").statusCode());
    }

    @Test
    void sqlOverRest() throws Exception {
        var r = nav("POST", "/v1/sql", "{\"sql\":\"SELECT type, count(*) n FROM concepts GROUP BY type ORDER BY type\"}");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("[\"Runbook\",1]"));
        assertEquals(400, nav("POST", "/v1/sql", "{\"sql\":\"SELECT * FROM read_text('/etc/passwd')\"}").statusCode());
    }

    @Test
    void metricsAndReady() throws Exception {
        assertEquals(8, JSON.readTree(call("GET", "/readyz", null, null, null).body()).at("/partitions/main/concepts").asInt());
        nav("POST", "/v1/search", "{\"q\":\"late trades\"}");
        String metrics = call("GET", "/metrics", null, null, null).body();
        assertTrue(metrics.contains("kl_rows"));
        assertTrue(metrics.contains("kl_search_seconds"));
    }

    @Test
    void mcpOverHttpWithPerCallerAcl() throws Exception {
        for (String[] who : List.of(new String[] {"alice", "nav-ops,fund-ops"}, new String[] {"eve", "contractors"})) {
            var transport = HttpClientStreamableHttpTransport.builder(base)
                    .endpoint("/mcp")
                    .jsonMapper(new JacksonMcpJsonMapper(new ObjectMapper()))
                    .addHttpRequestCustomizer((builder, method, uri, body, ctx) -> builder.header("X-KL-User", who[0]).header("X-KL-Groups", who[1]))
                    .build();
            try (var client = McpClient.sync(transport).build()) {
                client.initialize();
                Set<String> names = new HashSet<>();
                client.listTools().tools().forEach(t -> names.add(t.name()));
                assertEquals(Set.of("search", "get_concept", "browse", "related", "recent_changes"), names);
                var res = client.callTool(McpSchema.CallToolRequest.builder("search").arguments(Map.of("query", "late trades cut-off", "k", 3)).build());
                String text = ((McpSchema.TextContent) res.content().get(0)).text();
                if (who[0].equals("alice")) assertTrue(text.contains("nav-cutoff-runbook"), text);
                else assertEquals("{\"results\":[]}", text);
            }
        }
    }

    @Test
    void s3EventParsing() throws Exception {
        String raw = """
                {"Records": [
                  {"eventName": "ObjectCreated:Put", "s3": {"object": {"key": "bundles/jira/a+b.md"}}},
                  {"eventName": "ObjectRemoved:Delete", "s3": {"object": {"key": "bundles/jira/c.md"}}},
                  {"eventName": "ObjectCreated:Put", "s3": {"object": {"key": "bundles/jira/c.md"}}},
                  {"eventName": "ObjectCreated:Put", "s3": {"object": {"key": "other/x.md"}}}]}""";
        String sns = JSON.writeValueAsString(Map.of("Type", "Notification", "Message", raw));
        for (String body : List.of(raw, sns)) {
            var events = Events.parse(body);
            assertEquals(new Events.S3Event("ObjectCreated:Put", "bundles/jira/a b.md"), events.get(0));
            var sp = Events.split(events, k -> k.startsWith("bundles/") ? k.substring(8) : null);
            assertEquals(Set.of("jira/a b.md", "jira/c.md"), sp.upserted());
            assertEquals(Set.of(), sp.deleted());
        }
    }

    @Test
    void evalMetricsAndGate() throws Exception {
        Map<String, Double> gains = Map.of("a", 2.0, "b", 1.0);
        assertEquals(1.0, Evaluation.ndcg(List.of("a", "b"), gains, 10), 1e-9);
        assertTrue(Evaluation.ndcg(List.of("b", "a"), gains, 10) < 1.0);
        assertEquals(0.5, Evaluation.recall(List.of("x", "a"), gains, 50), 1e-9);
        Map<String, Object> report = Map.of("ndcg@10", 0.5, "recall@50", 0.9);
        assertFalse(Evaluation.gate(report, 0.6, null, null, 0.02).isEmpty());
        assertTrue(Evaluation.gate(report, 0.4, 0.8, null, 0.02).isEmpty());
    }
}
