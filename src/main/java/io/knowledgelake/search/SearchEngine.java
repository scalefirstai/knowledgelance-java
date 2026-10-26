package io.knowledgelake.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.knowledgelake.KlRuntime;
import io.knowledgelake.auth.Identity;
import io.knowledgelake.config.Settings;
import io.knowledgelake.search.QueryParsing.ParsedQuery;
import io.knowledgelake.search.SearchModels.ChunkHit;
import io.knowledgelake.search.SearchModels.ConceptHit;
import io.knowledgelake.search.SearchModels.SearchRequest;
import io.knowledgelake.search.SearchModels.SearchResponse;
import io.knowledgelake.store.Partition;
import io.knowledgelake.store.Partition.Hit;
import io.knowledgelake.store.StateStore;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The search core shared by REST, SQL and MCP.
 *
 * <p>Per query (spec §Retrieval and ranking):
 *
 * <ol>
 *   <li>Parse: inline filters, exact identifiers. Build the ACL + metadata predicate once.
 *   <li>Recall in parallel, top {@code recall_k} each, with the predicate as a <em>pre</em>-filter:
 *       BM25 (text ×1, title ×3, heading path ×2), chunk vectors, concept-card vectors.
 *   <li>Fuse: weighted RRF {@code Σ wᵢ / (k + rankᵢ)} plus optional recency; exact
 *       {@code source_id} hits pinned.
 *   <li>Rerank the top {@code top_n} with a cross-encoder over (query, heading path + chunk).
 *   <li>Group to at most 3 chunks per concept; optionally follow one hop of links.
 *   <li>Return concept metadata, best chunks with highlights, and a per-chunk score breakdown.
 * </ol>
 *
 * Card hits are concept-level; they are fused at chunk level by adding the card vote to every
 * candidate chunk of that concept, contributing the concept's first chunk if none was recalled.
 */
public final class SearchEngine implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SearchEngine.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final List<String> CHUNK_COLS = List.of("chunk_id", "concept_id", "ord", "heading_path", "text", "updated");
    static final List<String> CONCEPT_META = List.of("concept_id", "title", "type", "bundle", "dir", "scope",
            "description", "tags", "resource_url", "source_id", "updated");
    private static final Set<String> STOP = Set.of(("a an and are as at be by for from how i in is it of on or the "
            + "to what when where which who why with").split(" "));

    private final KlRuntime rt;
    private final Settings s;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    public SearchEngine(KlRuntime rt) {
        this.rt = rt;
        this.s = rt.settings;
    }

    public KlRuntime runtime() {
        return rt;
    }

    /** One recalled chunk and its votes. */
    private static final class Cand {
        final Map<String, Object> row;
        final String partition;
        Integer bm25, vec, card;
        double rrf, recency;
        Double rerank;
        boolean pinned;

        Cand(Map<String, Object> row, String partition) {
            this.row = row;
            this.partition = partition;
        }

        double score() {
            return rerank != null ? rerank : rrf + recency;
        }

        String conceptId() {
            return (String) row.get("concept_id");
        }

        Map<String, Object> breakdown() {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("bm25_rank", bm25);
            b.put("vector_rank", vec);
            b.put("card_rank", card);
            b.put("rrf", round(rrf));
            b.put("recency", round(recency));
            b.put("rerank", rerank == null ? null : round(rerank));
            b.put("pinned", pinned ? 1 : 0);
            return b;
        }
    }

    private record Ranked(String partition, Map<String, Object> row, float score) {}

    // ================================================================== search

    public SearchResponse search(SearchRequest req, Identity ident) {
        return search(req, ident, false);
    }

    public SearchResponse search(SearchRequest req, Identity ident, boolean withCandidates) {
        req.validate();
        Settings.Ranking r = s.ranking;
        Map<String, Double> timings = new LinkedHashMap<>();
        long tAll = System.nanoTime();

        long t = System.nanoTime();
        ParsedQuery pq = QueryParsing.parse(req.q, r.identifierPatterns);
        Filters filters = pq.filters().merged(req.filters);
        Set<String> groups = ident.groups();
        Query where = QueryParsing.filter(filters, groups);
        List<Partition> partitions = rt.partitionsFor(groups).stream().filter(p -> p.hasRows("chunks")).toList();
        timings.put("parse", ms(t));
        Map<String, Object> parsed = new LinkedHashMap<>();
        parsed.put("text", pq.text());
        parsed.put("filters", filters);
        parsed.put("identifiers", pq.identifiers());

        if (partitions.isEmpty()) return new SearchResponse(req.q, parsed, List.of(), 0, timings);
        if (pq.text().isBlank()) {
            List<ConceptHit> results = browseFiltered(partitions, where, req.k);
            timings.put("total", ms(tAll));
            return new SearchResponse(req.q, parsed, results, results.size(), timings);
        }

        // ---- recall (parallel)
        boolean useText = !req.mode.equals("vector");
        boolean useVec = !req.mode.equals("text");
        t = System.nanoTime();
        float[] qvec = useVec ? rt.embedder().embedQuery(pq.text()) : null;
        timings.put("embed_query", ms(t));

        t = System.nanoTime();
        Map<String, List<Future<List<Ranked>>>> futures = new HashMap<>();
        for (Partition p : partitions) {
            if (useText) futures.computeIfAbsent("bm25", k -> new ArrayList<>()).add(pool.submit(() -> bm25(p, pq.text(), where)));
            if (useVec) {
                futures.computeIfAbsent("vec", k -> new ArrayList<>())
                        .add(pool.submit(() -> vector(p, "chunks", p.vecCol, qvec, where)));
                futures.computeIfAbsent("card", k -> new ArrayList<>())
                        .add(pool.submit(() -> vector(p, "concepts", p.cardCol, qvec, where)));
            }
        }
        Future<List<Ranked>> pinFuture = pq.identifiers().isEmpty() ? null
                : pool.submit(() -> pins(partitions, pq.identifiers(), where));
        List<Ranked> bm25 = merged(futures.get("bm25"), r.recallK);
        List<Ranked> vec = merged(futures.get("vec"), r.recallK);
        List<Ranked> card = merged(futures.get("card"), r.recallK);
        List<Ranked> pins = pinFuture == null ? List.of() : get(pinFuture);
        timings.put("recall", ms(t));

        // ---- fuse
        t = System.nanoTime();
        Map<String, Cand> cands = new LinkedHashMap<>();
        for (int i = 0; i < bm25.size(); i++) {
            Cand c = cand(cands, bm25.get(i));
            c.bm25 = i + 1;
            c.rrf += r.wBm25 / (r.rrfK + i + 1);
        }
        for (int i = 0; i < vec.size(); i++) {
            Cand c = cand(cands, vec.get(i));
            c.vec = i + 1;
            c.rrf += r.wVector / (r.rrfK + i + 1);
        }
        if (!card.isEmpty()) {
            Map<String, List<Cand>> byConcept = byConcept(cands);
            List<Ranked> missing = card.stream().filter(x -> !byConcept.containsKey((String) x.row().get("concept_id"))).toList();
            for (Ranked first : firstChunks(missing, where)) {
                byConcept.computeIfAbsent((String) first.row().get("concept_id"), k -> new ArrayList<>()).add(cand(cands, first));
            }
            for (int i = 0; i < card.size(); i++) {
                for (Cand c : byConcept.getOrDefault((String) card.get(i).row().get("concept_id"), List.of())) {
                    c.card = i + 1;
                    c.rrf += r.wCard / (r.rrfK + i + 1);
                }
            }
        }
        if (r.recencyWeight > 0) {
            LocalDateTime now = StateStore.utcNow();
            for (Cand c : cands.values()) {
                if (c.row.get("updated") instanceof LocalDateTime u) {
                    double age = Math.max(Duration.between(u, now).toSeconds() / 86400.0, 0.0);
                    c.recency = r.recencyWeight * Math.pow(0.5, age / r.recencyHalfLifeDays);
                }
            }
        }
        List<String> pinned = new ArrayList<>();
        if (!pins.isEmpty()) {
            Set<String> have = new HashSet<>(byConcept(cands).keySet());
            List<Ranked> extra = pins.stream().filter(x -> !have.contains((String) x.row().get("concept_id"))).toList();
            for (Ranked first : firstChunks(extra, where)) cand(cands, first);
            for (Ranked p : pins) pinned.add((String) p.row().get("concept_id"));
            for (Cand c : cands.values()) if (pinned.contains(c.conceptId())) c.pinned = true;
        }
        List<Cand> ranked = new ArrayList<>(cands.values());
        ranked.sort(Comparator.comparingDouble(Cand::score).reversed());
        timings.put("fuse", ms(t));

        // ---- rerank
        var reranker = req.rerank ? rt.reranker() : java.util.Optional.<io.knowledgelake.models.Models.Reranker>empty();
        if (reranker.isPresent() && !ranked.isEmpty()) {
            t = System.nanoTime();
            int n = Math.min(s.rerank.topN, ranked.size());
            List<Cand> top = ranked.subList(0, n);
            List<String> passages = top.stream().map(c -> passage(c.row)).toList();
            float[] scores = reranker.get().score(pq.text(), passages);
            double floor = Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                top.get(i).rerank = (double) scores[i];
                floor = Math.min(floor, scores[i]);
            }
            for (Cand c : ranked.subList(n, ranked.size())) c.rerank = floor - 1.0 + c.rrf; // below the window
            ranked.sort(Comparator.comparingDouble(Cand::score).reversed());
            timings.put("rerank", ms(t));
        }

        // ---- group, pin, expand
        t = System.nanoTime();
        int maxPer = req.maxChunksPerConcept != null ? req.maxChunksPerConcept : r.maxChunksPerConcept;
        Map<String, List<Cand>> grouped = new LinkedHashMap<>();
        for (Cand c : ranked) {
            List<Cand> l = grouped.computeIfAbsent(c.conceptId(), k -> new ArrayList<>());
            if (l.size() < maxPer) l.add(c);
        }
        List<String> order = new ArrayList<>(grouped.keySet());
        order.sort(Comparator.<String, Boolean>comparing(cid -> !pinned.contains(cid))
                .thenComparing(cid -> -grouped.get(cid).get(0).score()));
        order = order.subList(0, Math.min(req.k, order.size()));
        Map<String, Map<String, Object>> meta = conceptMeta(order, groups);
        List<String> terms = terms(pq.text());
        List<ConceptHit> results = new ArrayList<>();
        for (String cid : order) {
            Map<String, Object> m = meta.get(cid);
            if (m == null) continue; // deleted between recall and fetch, or failed the ACL double-check
            List<Cand> hits = grouped.get(cid);
            List<ChunkHit> chunks = hits.stream().map(c -> chunkHit(c, terms)).toList();
            results.add(hit(m, round(hits.get(0).score()), pinned.contains(cid), null, chunks));
        }
        if (req.expandLinks > 0 && !results.isEmpty()) results.addAll(expand(results, groups));
        timings.put("group_expand", ms(t));
        timings.put("total", ms(tAll));

        if (withCandidates) { // evaluation: recall@N measured on fused candidates, before rerank
            List<Cand> fused = new ArrayList<>(cands.values());
            fused.sort(Comparator.comparingDouble((Cand c) -> c.rrf + c.recency).reversed());
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            for (Cand c : fused) seen.add(c.conceptId());
            parsed.put("candidate_concepts", new ArrayList<>(seen));
        }
        return new SearchResponse(req.q, parsed, results, cands.size(), timings);
    }

    private static Map<String, List<Cand>> byConcept(Map<String, Cand> cands) {
        Map<String, List<Cand>> out = new HashMap<>();
        for (Cand c : cands.values()) out.computeIfAbsent(c.conceptId(), k -> new ArrayList<>()).add(c);
        return out;
    }

    private static Cand cand(Map<String, Cand> cands, Ranked r) {
        return cands.computeIfAbsent((String) r.row().get("chunk_id"), k -> {
            Map<String, Object> row = new HashMap<>();
            for (String col : CHUNK_COLS) row.put(col, r.row().get(col));
            return new Cand(row, r.partition());
        });
    }

    private static <T> T get(Future<T> f) {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof RuntimeException re ? re : new IllegalStateException(e.getCause());
        }
    }

    /** Merge per-partition result lists by score (higher is better for both BM25 and cosine). */
    private static List<Ranked> merged(List<Future<List<Ranked>>> fs, int limit) {
        if (fs == null) return List.of();
        List<Ranked> all = new ArrayList<>();
        for (var f : fs) all.addAll(get(f));
        all.sort(Comparator.comparingDouble(Ranked::score).reversed());
        return all.subList(0, Math.min(limit, all.size()));
    }

    // ------------------------------------------------------------------ retrievers

    /** BM25 over chunk text (×1), title (×boost_title) and heading path (×boost_heading). */
    static Query bm25Query(String text, Settings.Ranking r) {
        BooleanQuery.Builder should = new BooleanQuery.Builder();
        String escaped = QueryParser.escape(text);
        int clauses = 0;
        for (var f : List.of(Map.entry("text", 1f), Map.entry("title", r.boostTitle), Map.entry("heading_path", r.boostHeading))) {
            try {
                Query q = new QueryParser(f.getKey(), Partition.ANALYZER).parse(escaped);
                if (q instanceof BooleanQuery bq && bq.clauses().isEmpty()) continue; // only stopwords
                should.add(new BoostQuery(q, f.getValue()), BooleanClause.Occur.SHOULD);
                clauses++;
            } catch (ParseException e) {
                log.debug("bm25 parse failed for {}: {}", f.getKey(), e.getMessage());
            }
        }
        return clauses == 0 ? null : should.build();
    }

    private List<Ranked> bm25(Partition p, String text, Query where) {
        Query q = bm25Query(text, s.ranking);
        if (q == null) return List.of();
        Query full = new BooleanQuery.Builder().add(q, BooleanClause.Occur.MUST).add(where, BooleanClause.Occur.FILTER).build();
        return ranked(p, p.search("chunks", full, s.ranking.recallK, CHUNK_COLS));
    }

    private List<Ranked> vector(Partition p, String table, String field, float[] qvec, Query where) {
        if (!p.hasRows(table)) return List.of();
        List<String> cols = table.equals("chunks") ? CHUNK_COLS : List.of("concept_id");
        return ranked(p, p.knn(table, field, qvec, s.ranking.recallK, where, cols));
    }

    private static List<Ranked> ranked(Partition p, List<Hit> hits) {
        return hits.stream().map(h -> new Ranked(p.name, h.row(), h.score())).toList();
    }

    private List<Ranked> pins(List<Partition> partitions, List<String> identifiers, Query where) {
        List<Ranked> out = new ArrayList<>();
        for (Partition p : partitions) {
            Query q = QueryParsing.and(Partition.termsIn("source_id", identifiers), where);
            for (var row : p.query("concepts", q, List.of("concept_id", "source_id"))) out.add(new Ranked(p.name, row, 0));
        }
        out.sort(Comparator.comparingInt(x -> identifiers.indexOf((String) x.row().get("source_id"))));
        return out;
    }

    private List<Ranked> firstChunks(List<Ranked> wanted, Query where) {
        Map<String, List<String>> byP = new LinkedHashMap<>();
        for (Ranked w : wanted) byP.computeIfAbsent(w.partition(), k -> new ArrayList<>()).add((String) w.row().get("concept_id"));
        List<Ranked> out = new ArrayList<>();
        byP.forEach((pname, ids) -> {
            Query q = QueryParsing.and(Partition.termsIn("concept_id", ids), IntPoint.newExactQuery("ord", 0), where);
            for (var row : rt.partition(pname).query("chunks", q, CHUNK_COLS)) out.add(new Ranked(pname, row, 0));
        });
        return out;
    }

    // ------------------------------------------------------------------ metadata, expansion

    private Map<String, Map<String, Object>> conceptMeta(List<String> ids, Set<String> groups) {
        Map<String, Map<String, Object>> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        for (Partition p : rt.partitionsFor(groups)) {
            Query q = QueryParsing.and(Partition.termsIn("concept_id", ids), QueryParsing.acl(groups));
            for (var row : p.query("concepts", q, CONCEPT_META)) out.put((String) row.get("concept_id"), row);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static ConceptHit hit(Map<String, Object> m, double score, boolean pinned, Map<String, String> via,
                                  List<ChunkHit> chunks) {
        return new ConceptHit((String) m.get("concept_id"), (String) m.get("title"), (String) m.get("type"),
                (String) m.get("bundle"), (String) m.get("dir"), (String) m.get("scope"),
                m.get("description") == null ? "" : (String) m.get("description"),
                m.get("tags") == null ? List.of() : (List<String>) m.get("tags"),
                (String) m.get("resource_url"), (String) m.get("source_id"), (LocalDateTime) m.get("updated"),
                score, pinned, via, chunks);
    }

    private List<ConceptHit> expand(List<ConceptHit> results, Set<String> groups) {
        Settings.Ranking r = s.ranking;
        Set<String> have = new HashSet<>();
        results.forEach(h -> have.add(h.conceptId()));
        double minDirect = results.stream().mapToDouble(ConceptHit::score).min().orElse(0);
        Map<String, ConceptHit> extra = new LinkedHashMap<>();
        for (ConceptHit parent : results.subList(0, Math.min(r.expandTop, results.size()))) {
            for (Map<String, Object> rel : related(parent.conceptId(), Identity.of("_expand", groups))) {
                String cid = (String) rel.get("concept_id");
                if (have.contains(cid) || extra.containsKey(cid)) continue;
                double score = Math.min(parent.score() * r.expandPenalty, minDirect - Math.abs(minDirect) * 0.01 - 1e-6);
                Map<String, String> via = Map.of("from", parent.conceptId(), "direction", (String) rel.get("direction"),
                        "anchor_text", (String) rel.get("anchor_text"));
                extra.put(cid, hit(rel, round(score), false, via, List.of()));
            }
        }
        List<ConceptHit> out = new ArrayList<>(extra.values());
        out.sort(Comparator.comparingDouble(ConceptHit::score).reversed());
        return out;
    }

    private List<ConceptHit> browseFiltered(List<Partition> partitions, Query where, int k) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Partition p : partitions) rows.addAll(p.query("concepts", where, CONCEPT_META, 5000));
        rows.sort(Comparator.comparing((Map<String, Object> m) -> (LocalDateTime) m.get("updated"),
                Comparator.nullsFirst(Comparator.naturalOrder())).reversed());
        return rows.stream().limit(k).map(m -> hit(m, 0.0, false, null, List.of())).toList();
    }

    // ================================================================== navigation (MCP / REST)

    public Map<String, Object> getConcept(String conceptId, Identity ident) {
        Set<String> groups = ident.groups();
        List<String> cols = new ArrayList<>(CONCEPT_META);
        cols.addAll(List.of("path", "frontmatter", "body", "extra", "indexed_at"));
        for (Partition p : rt.partitionsFor(groups)) {
            Query q = QueryParsing.and(new TermQuery(new Term("concept_id", conceptId)), QueryParsing.acl(groups));
            List<Map<String, Object>> rows = p.query("concepts", q, cols, 1);
            if (!rows.isEmpty()) {
                Map<String, Object> row = new LinkedHashMap<>(rows.get(0));
                row.put("frontmatter", parseJson((String) row.get("frontmatter")));
                row.put("extra", parseJson((String) row.get("extra")));
                return row;
            }
        }
        return null;
    }

    private static Map<String, Object> parseJson(String s) {
        if (s == null || s.isEmpty()) return Map.of();
        try {
            return JSON.readValue(s, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** index.md for a directory plus its immediate children: progressive disclosure for agents. */
    public Map<String, Object> browse(String path, Identity ident) {
        Set<String> groups = ident.groups();
        String dir = path == null ? "" : path.replaceAll("^/+|/+$", "");
        Query acl = QueryParsing.acl(groups);
        Map<String, Object> index = null;
        TreeSet<String> subdirs = new TreeSet<>();
        List<Map<String, Object>> concepts = new ArrayList<>();
        for (Partition p : rt.partitionsFor(groups)) {
            if (!dir.isEmpty()) {
                if (index == null) {
                    var rows = p.query("indexes", QueryParsing.and(new TermQuery(new Term("dir", dir)), acl),
                            List.of("dir", "path", "content"), 1);
                    if (!rows.isEmpty()) index = rows.get(0);
                }
                Query under = new org.apache.lucene.search.PrefixQuery(new Term("dir", dir + "/"));
                for (var row : p.query("indexes", QueryParsing.and(under, acl), List.of("dir"))) addChild(subdirs, dir, (String) row.get("dir"));
                for (var row : p.query("concepts", QueryParsing.and(under, acl), List.of("dir"))) addChild(subdirs, dir, (String) row.get("dir"));
                concepts.addAll(p.query("concepts", QueryParsing.and(new TermQuery(new Term("dir", dir)), acl),
                        List.of("concept_id", "title", "type", "description")));
            } else { // root: one entry per bundle the caller can see
                List<Map<String, Object>> roots = new ArrayList<>();
                for (var row : p.query("indexes", acl, List.of("dir", "bundle", "content"))) {
                    subdirs.add((String) row.get("bundle"));
                    if (row.get("dir").equals(row.get("bundle"))) roots.add(row);
                }
                for (var row : p.query("concepts", acl, List.of("bundle"))) subdirs.add((String) row.get("bundle"));
                if (!roots.isEmpty() && index == null) {
                    roots.sort(Comparator.comparing(m -> (String) m.get("dir")));
                    StringBuilder sb = new StringBuilder();
                    for (var row : roots) {
                        if (!sb.isEmpty()) sb.append("\n\n");
                        sb.append("# ").append(row.get("dir")).append('\n').append(row.get("content"));
                    }
                    index = new HashMap<>(Map.of("dir", "", "content", sb.toString()));
                }
            }
        }
        concepts.sort(Comparator.comparing(c -> c.get("title") == null ? "" : (String) c.get("title")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", dir);
        out.put("index", index == null ? null : index.get("content"));
        out.put("index_path", index == null ? null : index.get("path"));
        out.put("subdirectories", new ArrayList<>(subdirs));
        out.put("concepts", concepts);
        return out;
    }

    private static void addChild(Set<String> subdirs, String parent, String dir) {
        if (dir == null || !dir.startsWith(parent + "/")) return;
        String rest = dir.substring(parent.length() + 1);
        if (!rest.isEmpty()) subdirs.add(parent + "/" + rest.split("/", 2)[0]);
    }

    /** Concepts linked to or from a concept (path links and native-key mentions), ACL-checked both ends. */
    public List<Map<String, Object>> related(String conceptId, Identity ident) {
        Set<String> groups = ident.groups();
        Query acl = QueryParsing.acl(groups);
        Map<String, Object> me = getConcept(conceptId, ident);
        if (me == null) return List.of();
        record Edge(String direction, String conceptId, String anchor, String kind) {}
        List<Edge> edges = new ArrayList<>();
        Map<String, String> refsOut = new LinkedHashMap<>();
        for (Partition p : rt.partitionsFor(groups)) {
            for (var row : p.query("links", new TermQuery(new Term("from_concept", conceptId)), null)) {
                if (row.get("to_concept") != null) {
                    edges.add(new Edge("outgoing", (String) row.get("to_concept"), (String) row.get("anchor_text"), (String) row.get("kind")));
                } else if (row.get("to_ref") != null) {
                    refsOut.putIfAbsent((String) row.get("to_ref"), (String) row.get("anchor_text"));
                }
            }
            BooleanQuery.Builder incoming = new BooleanQuery.Builder()
                    .add(new TermQuery(new Term("to_concept", conceptId)), BooleanClause.Occur.SHOULD);
            if (me.get("source_id") != null) {
                incoming.add(new TermQuery(new Term("to_ref", (String) me.get("source_id"))), BooleanClause.Occur.SHOULD);
            }
            incoming.setMinimumNumberShouldMatch(1);
            for (var row : p.query("links", QueryParsing.and(incoming.build(), acl), null)) {
                edges.add(new Edge("incoming", (String) row.get("from_concept"), (String) row.get("anchor_text"), (String) row.get("kind")));
            }
        }
        if (!refsOut.isEmpty()) {
            for (Partition p : rt.partitionsFor(groups)) {
                Query q = QueryParsing.and(Partition.termsIn("source_id", refsOut.keySet()), acl);
                for (var row : p.query("concepts", q, List.of("concept_id", "source_id"))) {
                    edges.add(new Edge("outgoing", (String) row.get("concept_id"), refsOut.get((String) row.get("source_id")), "key"));
                }
            }
        }
        List<String> ids = edges.stream().map(Edge::conceptId).filter(c -> c != null && !c.equals(conceptId)).distinct().toList();
        Map<String, Map<String, Object>> meta = conceptMeta(ids, groups);
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Edge e : edges) {
            if (meta.containsKey(e.conceptId()) && seen.add(e.conceptId() + "\u0000" + e.direction())) {
                Map<String, Object> m = new LinkedHashMap<>(meta.get(e.conceptId()));
                m.put("direction", e.direction());
                m.put("anchor_text", e.anchor() == null ? "" : e.anchor());
                m.put("kind", e.kind());
                out.add(m);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> recentChanges(Identity ident, String bundle, String dir, LocalDateTime since, int limit) {
        Set<String> groups = ident.groups();
        BooleanQuery.Builder b = new BooleanQuery.Builder().add(QueryParsing.acl(groups), BooleanClause.Occur.FILTER);
        if (bundle != null) b.add(new TermQuery(new Term("bundle", bundle)), BooleanClause.Occur.FILTER);
        if (dir != null) b.add(QueryParsing.dirUnder(dir.replaceAll("^/+", ""), "dir"), BooleanClause.Occur.FILTER);
        if (since != null) b.add(QueryParsing.range("date", since, null), BooleanClause.Occur.FILTER);
        Query q = b.build();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Partition p : rt.partitionsFor(groups)) {
            rows.addAll(p.query("changes", q, List.of("bundle", "dir", "date", "text", "concept_ids")));
        }
        // an entry that only mentions concepts the caller cannot open would leak their titles
        Set<String> linked = new HashSet<>();
        for (var r : rows) linked.addAll((List<String>) r.get("concept_ids"));
        Set<String> visible = conceptMeta(new ArrayList<>(linked), groups).keySet();
        rows.removeIf(r -> {
            List<String> ids = (List<String>) r.get("concept_ids");
            return !ids.isEmpty() && ids.stream().noneMatch(visible::contains);
        });
        rows.sort(Comparator.comparing((Map<String, Object> m) -> (LocalDateTime) m.get("date"),
                Comparator.nullsFirst(Comparator.naturalOrder())).reversed());
        return rows.subList(0, Math.min(limit, rows.size()));
    }

    // ------------------------------------------------------------------ helpers

    static double round(double v) {
        return Math.round(v * 1e6) / 1e6;
    }

    private static double ms(long t0) {
        return Math.round((System.nanoTime() - t0) / 1e4) / 100.0;
    }

    private static String passage(Map<String, Object> row) {
        String hp = (String) row.get("heading_path");
        return hp == null || hp.isEmpty() ? (String) row.get("text") : hp + "\n" + row.get("text");
    }

    static List<String> terms(String text) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("[\\w-]+", Pattern.UNICODE_CHARACTER_CLASS).matcher(text.toLowerCase());
        while (m.find()) if (m.group().length() > 1 && !STOP.contains(m.group())) out.add(m.group());
        return new ArrayList<>(out);
    }

    static String escapeHtml(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#x27;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** HTML-escaped snippet around the first matching term, with &lt;mark&gt; around every match. */
    public static String highlight(String text, List<String> terms, int width) {
        if (terms.isEmpty()) return escapeHtml(text.substring(0, Math.min(width, text.length())));
        StringBuilder alt = new StringBuilder();
        for (String t : terms) {
            if (!alt.isEmpty()) alt.append('|');
            alt.append(Pattern.quote(t));
        }
        Pattern p = Pattern.compile("(?<![\\w])(" + alt + ")", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        Matcher first = p.matcher(text);
        int start = Math.max(0, (first.find() ? first.start() : 0) - width / 3);
        int end = Math.min(text.length(), start + width);
        String window = text.substring(start, end);
        StringBuilder out = new StringBuilder(start > 0 ? "…" : "");
        Matcher m = p.matcher(window);
        int last = 0;
        while (m.find()) {
            out.append(escapeHtml(window.substring(last, m.start()))).append("<mark>").append(escapeHtml(m.group())).append("</mark>");
            last = m.end();
        }
        out.append(escapeHtml(window.substring(last)));
        if (end < text.length()) out.append('…');
        return out.toString();
    }

    private static ChunkHit chunkHit(Cand c, List<String> terms) {
        String text = (String) c.row.get("text");
        String hp = (String) c.row.get("heading_path");
        return new ChunkHit((String) c.row.get("chunk_id"), hp == null ? "" : hp, text, highlight(text, terms, 280),
                round(c.score()), c.breakdown());
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
