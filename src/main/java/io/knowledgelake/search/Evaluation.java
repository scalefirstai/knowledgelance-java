package io.knowledgelake.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.knowledgelake.auth.Identity;
import io.knowledgelake.search.SearchModels.ConceptHit;
import io.knowledgelake.search.SearchModels.SearchRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Golden-set evaluation: recall@50 for the retrievers, nDCG@10 and MRR for the final ranking.
 * Same YAML format as the Python implementation:
 *
 * <pre>
 * queries:
 *   - q: "late trade cut-off for NAV strike"
 *     relevant: [confluence/spaces/NAV/nav-cutoff-runbook]            # binary relevance
 *   - q: "FUND-1423 root cause"
 *     relevant: {jira/projects/FUND/FUND-1423: 2, confluence/...: 1}  # graded
 *     groups: [nav-ops]                                                # run as this caller
 * </pre>
 */
public final class Evaluation {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private Evaluation() {}

    static Map<String, Double> gains(JsonNode relevant) {
        Map<String, Double> g = new LinkedHashMap<>();
        if (relevant.isObject()) relevant.properties().forEach(e -> g.put(e.getKey(), e.getValue().asDouble()));
        else relevant.forEach(n -> g.put(n.asText(), 1.0));
        return g;
    }

    public static double ndcg(List<String> ranked, Map<String, Double> gains, int k) {
        double dcg = 0;
        for (int i = 0; i < Math.min(k, ranked.size()); i++) dcg += gains.getOrDefault(ranked.get(i), 0.0) / log2(i + 2);
        List<Double> ideal = new ArrayList<>(gains.values());
        ideal.sort((a, b) -> Double.compare(b, a));
        double idcg = 0;
        for (int i = 0; i < Math.min(k, ideal.size()); i++) idcg += ideal.get(i) / log2(i + 2);
        return idcg == 0 ? 0 : dcg / idcg;
    }

    public static double recall(List<String> ranked, Map<String, Double> gains, int k) {
        Set<String> rel = new HashSet<>();
        gains.forEach((c, g) -> {
            if (g > 0) rel.add(c);
        });
        if (rel.isEmpty()) return 0;
        Set<String> top = new HashSet<>(ranked.subList(0, Math.min(k, ranked.size())));
        top.retainAll(rel);
        return (double) top.size() / rel.size();
    }

    public static double mrr(List<String> ranked, Map<String, Double> gains) {
        for (int i = 0; i < ranked.size(); i++) if (gains.getOrDefault(ranked.get(i), 0.0) > 0) return 1.0 / (i + 1);
        return 0;
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }

    public static Map<String, Object> run(SearchEngine engine, Path golden, Set<String> defaultGroups, boolean rerank) throws IOException {
        return run(engine, golden, defaultGroups, rerank, 0);
    }

    /**
     * {@code warmupPasses} runs the whole set that many times before measuring, so latency reflects
     * a JIT-warmed steady state rather than the first requests after start.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> run(SearchEngine engine, Path golden, Set<String> defaultGroups, boolean rerank,
                                          int warmupPasses) throws IOException {
        JsonNode spec = YAML.readTree(Files.readString(golden));
        engine.runtime().embedder().embedQuery("warm up"); // report steady-state latency, not model load time
        if (rerank) engine.runtime().reranker().ifPresent(r -> r.score("warm up", List.of("warm up")));
        for (int pass = 0; pass < warmupPasses; pass++) {
            for (JsonNode item : spec.path("queries")) {
                engine.search(new SearchRequest(item.get("q").asText()).k(10).rerank(rerank), Identity.of("warmup", defaultGroups));
            }
        }
        List<Map<String, Object>> perQuery = new ArrayList<>();
        for (JsonNode item : spec.path("queries")) {
            Map<String, Double> g = gains(item.get("relevant"));
            Set<String> groups = new HashSet<>();
            item.path("groups").forEach(n -> groups.add(n.asText()));
            Identity ident = Identity.of("eval", groups.isEmpty() ? defaultGroups : groups);
            SearchRequest req = new SearchRequest(item.get("q").asText()).k(10).rerank(rerank);
            if (item.has("filters")) req.filters = YAML.treeToValue(item.get("filters"), Filters.class);
            var resp = engine.search(req, ident, true);
            List<String> fin = resp.results().stream().map(ConceptHit::conceptId).toList();
            List<String> cands = (List<String>) resp.parsed().getOrDefault("candidate_concepts", fin);
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("q", item.get("q").asText());
            q.put("recall@50", recall(cands, g, 50));
            q.put("ndcg@10", ndcg(fin, g, 10));
            q.put("mrr", mrr(fin, g));
            q.put("latency_ms", resp.timingsMs().getOrDefault("total", 0.0));
            q.put("top", fin.subList(0, Math.min(3, fin.size())));
            perQuery.add(q);
        }
        int n = Math.max(perQuery.size(), 1);
        List<Double> lat = perQuery.stream().map(q -> (Double) q.get("latency_ms")).sorted().toList();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("queries", perQuery.size());
        report.put("recall@50", round(perQuery.stream().mapToDouble(q -> (Double) q.get("recall@50")).sum() / n));
        report.put("ndcg@10", round(perQuery.stream().mapToDouble(q -> (Double) q.get("ndcg@10")).sum() / n));
        report.put("mrr", round(perQuery.stream().mapToDouble(q -> (Double) q.get("mrr")).sum() / n));
        report.put("p95_latency_ms", lat.isEmpty() ? 0.0 : lat.get(Math.max(0, (int) Math.ceil(0.95 * lat.size()) - 1)));
        report.put("per_query", perQuery);
        return report;
    }

    private static double round(double v) {
        return Math.round(v * 1e4) / 1e4;
    }

    public static List<String> gate(Map<String, Object> report, Double minNdcg, Double minRecall, Path baseline, double maxDrop)
            throws IOException {
        List<String> failures = new ArrayList<>();
        double ndcg = (Double) report.get("ndcg@10"), recall = (Double) report.get("recall@50");
        if (minNdcg != null && ndcg < minNdcg) failures.add("ndcg@10 " + ndcg + " < " + minNdcg);
        if (minRecall != null && recall < minRecall) failures.add("recall@50 " + recall + " < " + minRecall);
        if (baseline != null && Files.exists(baseline)) {
            JsonNode base = new ObjectMapper().readTree(Files.readString(baseline));
            for (String m : List.of("ndcg@10", "recall@50")) {
                double cur = (Double) report.get(m), was = base.path(m).asDouble(0);
                if (cur < was - maxDrop) failures.add(m + " regressed " + was + " -> " + cur + " (max drop " + maxDrop + ")");
            }
        }
        return failures;
    }
}
