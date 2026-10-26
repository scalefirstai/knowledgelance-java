package io.knowledgelake.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.knowledgelake.KlRuntime;
import io.knowledgelake.config.Settings;
import io.knowledgelake.okf.Okf;
import io.knowledgelake.okf.Okf.Chunk;
import io.knowledgelake.okf.Okf.Concept;
import io.knowledgelake.okf.Okf.Link;
import io.knowledgelake.store.ObjectStore.ObjectInfo;
import io.knowledgelake.store.Partition;
import io.knowledgelake.store.StateStore;
import io.knowledgelake.store.StateStore.ManifestRow;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.TermQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns bundle object changes into rows in the partition indexes.
 *
 * <p>Pipeline per object (spec §Ingestion): detect, parse, canonicalize and hash, chunk, extract
 * links, embed (cached by chunk hash), write (upsert/delete), record in the manifest. Writes are
 * batched across many objects and committed once per batch, so a full rebuild produces a handful
 * of Lucene commits rather than one per file.
 */
public final class Indexer {
    private static final Logger log = LoggerFactory.getLogger(Indexer.class);
    private static final ObjectMapper JSON = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final KlRuntime rt;
    private final Settings s;
    private final int batchSize;
    private Map<String, ManifestRow> manifest;

    public Indexer(KlRuntime rt) {
        this(rt, 200);
    }

    public Indexer(KlRuntime rt, int batchSize) {
        this.rt = rt;
        this.s = rt.settings;
        this.batchSize = batchSize;
        this.manifest = rt.state.loadManifest();
    }

    public Map<String, ManifestRow> manifest() {
        return manifest;
    }

    private record Work(ObjectInfo info, Concept concept, List<String> acl, String partition, boolean aclOnly) {}

    // ================================================================== entry points

    public IndexStats reconcile() {
        return reconcile(false, "");
    }

    /**
     * List every bundle object and diff ETags against the manifest. Runs nightly to catch anything
     * S3 events missed. With {@code recheckAcls} every concept is re-read even when its ETag is
     * unchanged, so a change to a bundle's default policy propagates without re-embedding.
     */
    public IndexStats reconcile(boolean recheckAcls, String prefix) {
        Map<String, ObjectInfo> listed = new LinkedHashMap<>();
        for (ObjectInfo o : rt.bundles.list(prefix)) if (indexable(o.key())) listed.put(o.key(), o);
        List<ObjectInfo> changed = new ArrayList<>();
        for (ObjectInfo o : listed.values()) {
            ManifestRow m = manifest.get(o.key());
            if (recheckAcls || m == null || !m.etag().equals(o.etag())) changed.add(o);
        }
        List<String> gone = manifest.keySet().stream().filter(k -> k.startsWith(prefix) && !listed.containsKey(k)).toList();
        return apply(changed, gone, recheckAcls);
    }

    /** Event-driven path: S3 PUT/DELETE notifications for specific keys. */
    public IndexStats applyKeys(Collection<String> upserted, Collection<String> deleted) {
        List<ObjectInfo> infos = new ArrayList<>();
        List<String> gone = new ArrayList<>(deleted);
        for (String key : upserted) {
            if (!indexable(key)) continue;
            Optional<ObjectInfo> info = rt.bundles.head(key);
            if (info.isEmpty()) {
                gone.add(key); // deleted before we got to it
            } else if (!manifest.containsKey(key) || !manifest.get(key).etag().equals(info.get().etag())) {
                infos.add(info.get());
            }
        }
        return apply(infos, gone.stream().filter(Indexer::indexable).toList(), false);
    }

    public synchronized IndexStats apply(List<ObjectInfo> changed, List<String> deleted, boolean forceRead) {
        long t0 = System.nanoTime();
        IndexStats stats = new IndexStats();
        stats.seen = changed.size() + deleted.size();
        Set<String> touched = new TreeSet<>();
        for (int i = 0; i < changed.size(); i += batchSize) {
            touched.addAll(processBatch(changed.subList(i, Math.min(changed.size(), i + batchSize)), stats));
        }
        if (!deleted.isEmpty()) touched.addAll(delete(deleted, stats));
        if (!touched.isEmpty()) {
            for (String name : touched) rt.partition(name).commit();
            rt.state.saveManifest(manifest);
            rt.state.bumpVersion(new ArrayList<>(touched));
        }
        stats.seconds = Math.round((System.nanoTime() - t0) / 1e6) / 1000.0;
        rt.state.log("apply", stats.summary());
        rt.state.flushLog();
        return stats;
    }

    /** Scheduled maintenance: merge away deleted docs, prune unreferenced published files. */
    public void optimize() {
        List<String> names = new ArrayList<>();
        for (Partition p : rt.partitions()) {
            p.optimize();
            names.add(p.name);
        }
        rt.state.bumpVersion(names);
        rt.state.log("optimize", Map.of());
        rt.state.flushLog();
    }

    /** Drop every derived row and re-index from the bundles (S3 is the system of record). */
    public IndexStats rebuild() {
        for (Partition p : rt.partitions()) p.truncate();
        manifest = new HashMap<>();
        Map<String, String> models = rt.state.columnModels();
        models.remove(s.embedding.column);
        rt.state.setColumnModels(models);
        return reconcile();
    }

    // ================================================================== helpers

    static boolean indexable(String key) {
        return key.endsWith(".md") && key.contains("/") && !key.startsWith("_kl/") && !key.startsWith(".");
    }

    private List<String> effectiveAcl(List<String> conceptAcl, String bundle) {
        return conceptAcl != null ? conceptAcl : new ArrayList<>(s.bundle(bundle).defaultAcl);
    }

    private Set<String> volatileFields(String bundle) {
        Set<String> v = new HashSet<>(s.globalVolatileFields);
        v.addAll(s.bundle(bundle).volatileFields);
        return v;
    }

    private void record(ObjectInfo info, String kind, String partition, String contentHash, String aclHash, String error) {
        manifest.put(info.key(), new ManifestRow(info.key(), info.etag(), contentHash, aclHash, kind, partition,
                StateStore.utcNow(), error));
    }

    private static String bundleOf(String key) {
        return key.substring(0, key.indexOf('/'));
    }

    // ================================================================== batch processing

    private Set<String> processBatch(List<ObjectInfo> batch, IndexStats stats) {
        List<Work> work = new ArrayList<>();
        Set<String> touched = new TreeSet<>();
        for (ObjectInfo info : batch) {
            String bundle = bundleOf(info.key());
            String partition = s.partitionFor(bundle);
            ManifestRow prev = manifest.get(info.key());
            if (prev != null && !prev.partition().equals(partition)) { // bundle re-routed: clean the old partition
                remove(rt.partition(prev.partition()), info.key());
                touched.add(prev.partition());
                prev = null;
            }
            String raw;
            try {
                raw = rt.bundles.getText(info.key());
            } catch (RuntimeException e) {
                stats.errors.add(info.key() + ": " + e.getMessage());
                continue;
            }
            if (Okf.RESERVED.contains(Okf.basename(info.key()))) {
                indexReserved(info, raw, bundle, partition);
                touched.add(partition);
                stats.reserved++;
                continue;
            }
            Concept concept;
            try {
                concept = Okf.parseConcept(info.key(), raw, new Okf.ParseOptions(volatileFields(bundle),
                        s.chunking.maxTokens, s.chunking.overlap, s.ranking.identifierPatterns));
            } catch (RuntimeException e) {
                stats.invalid++;
                stats.errors.add(e.getMessage());
                if (prev != null && prev.kind().equals("concept")) { // a page that became invalid must stop being served
                    deleteConcepts(rt.partition(prev.partition()), List.of(Okf.conceptIdFor(info.key())));
                    touched.add(prev.partition());
                }
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                record(info, "invalid", partition, null, null, msg.substring(0, Math.min(500, msg.length())));
                continue;
            }
            List<String> acl = effectiveAcl(concept.acl(), bundle);
            String aHash = Okf.aclHash(acl);
            boolean sameContent = prev != null && concept.contentHash().equals(prev.contentHash());
            if (sameContent && aHash.equals(prev.aclHash())) {
                stats.unchanged++;
                record(info, "concept", partition, concept.contentHash(), aHash, null);
                continue;
            }
            work.add(new Work(info, concept, acl, partition, sameContent));
        }

        Map<String, List<Work>> byPartition = new LinkedHashMap<>();
        for (Work w : work) byPartition.computeIfAbsent(w.partition(), k -> new ArrayList<>()).add(w);
        byPartition.forEach((name, items) -> {
            Partition p = rt.partition(name);
            List<Work> aclOnly = items.stream().filter(Work::aclOnly).toList();
            List<Work> full = items.stream().filter(w -> !w.aclOnly()).toList();
            if (!aclOnly.isEmpty()) {
                updateAcls(p, aclOnly);
                stats.aclOnly += aclOnly.size();
            }
            if (!full.isEmpty()) {
                writeFull(p, full, stats);
                stats.indexed += full.size();
            }
            for (Work w : items) record(w.info(), "concept", name, w.concept().contentHash(), Okf.aclHash(w.acl()), null);
            touched.add(name);
        });
        return touched;
    }

    private void indexReserved(ObjectInfo info, String raw, String bundle, String partition) {
        Partition p = rt.partition(partition);
        Okf.Frontmatter fm = Okf.splitFrontmatter(raw);
        List<String> acl = effectiveAcl(fm.meta().containsKey("acl") ? Okf.strList(fm.meta().get("acl")) : null, bundle);
        String dir = Okf.dirname(info.key());
        if (info.key().endsWith("index.md")) {
            Map<String, Object> row = new HashMap<>();
            row.put("dir", dir);
            row.put("bundle", bundle);
            row.put("path", info.key());
            row.put("content", fm.body().strip());
            row.put("acl", acl);
            row.put("indexed_at", StateStore.utcNow());
            p.upsert("indexes", List.of(row));
            record(info, "index", partition, Okf.sha256(raw), null, null);
        } else {
            List<Okf.ChangeEntry> entries = Okf.parseLog(info.key(), raw);
            p.delete("changes", new TermQuery(new Term("dir", dir)));
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                Okf.ChangeEntry e = entries.get(i);
                Map<String, Object> row = new HashMap<>();
                row.put("change_id", Okf.sha256(info.key() + "\n" + i + "\n" + e.text()).substring(0, 32));
                row.put("bundle", bundle);
                row.put("dir", dir);
                row.put("date", e.date());
                row.put("text", e.text());
                row.put("concept_ids", e.conceptIds());
                row.put("acl", acl);
                rows.add(row);
            }
            p.append("changes", rows);
            record(info, "log", partition, Okf.sha256(raw), null, null);
        }
    }

    /** ACL changed, content did not: rewrite the acl columns without re-embedding. */
    private void updateAcls(Partition p, List<Work> items) {
        Map<String, List<String>> acls = new HashMap<>();
        for (Work w : items) acls.put(w.concept().conceptId(), w.acl());
        var ids = Partition.termsIn("concept_id", acls.keySet());
        List<Map<String, Object>> concepts = new ArrayList<>();
        for (var r : p.query("concepts", ids, null)) {
            Map<String, Object> row = new HashMap<>(r);
            row.put("acl", acls.get((String) row.get("concept_id")));
            row.put("acl_hash", Okf.aclHash(acls.get((String) row.get("concept_id"))));
            concepts.add(row);
        }
        List<Map<String, Object>> chunks = new ArrayList<>();
        for (var r : p.query("chunks", ids, null)) {
            Map<String, Object> row = new HashMap<>(r);
            row.put("acl", acls.get((String) row.get("concept_id")));
            chunks.add(row);
        }
        var from = Partition.termsIn("from_concept", acls.keySet());
        List<Map<String, Object>> links = new ArrayList<>();
        for (var r : p.query("links", from, null)) {
            Map<String, Object> row = new HashMap<>(r);
            row.put("acl", acls.get((String) row.get("from_concept")));
            links.add(row);
        }
        p.upsert("concepts", concepts);
        p.upsert("chunks", chunks);
        p.delete("links", from);
        p.append("links", links);
    }

    private Map<String, float[]> cachedVectors(Partition p, String table, String hashCol, String vecCol, Set<String> hashes) {
        Map<String, float[]> cached = new HashMap<>();
        if (hashes.isEmpty() || !p.hasRows(table)) return cached;
        List<String> all = new ArrayList<>(hashes);
        for (int i = 0; i < all.size(); i += 1000) {
            var q = Partition.termsIn(hashCol, all.subList(i, Math.min(all.size(), i + 1000)));
            for (var row : p.query(table, q, List.of(hashCol, vecCol))) {
                if (row.get(vecCol) instanceof float[] v && v.length == s.embedding.dim) cached.put((String) row.get(hashCol), v);
            }
        }
        return cached;
    }

    /** Vectors from two models must never share a column (the hash cache would mix them). */
    private void checkModel() {
        String col = s.embedding.column, name = rt.embedder().name();
        Map<String, String> models = rt.state.columnModels();
        String owner = models.get(col);
        if (owner == null) {
            models.put(col, name);
            rt.state.setColumnModels(models);
        } else if (!owner.equals(name)) {
            throw new IllegalStateException("vector column '" + col + "' holds embeddings from '" + owner
                    + "', but the configured model is '" + name + "'. Set embedding.column to a new name and run "
                    + "`kl reembed`, or run `kl rebuild`.");
        }
    }

    private void writeFull(Partition p, List<Work> items, IndexStats stats) {
        checkModel();
        var emb = rt.embedder();
        // 1. embedding cache: unchanged chunks (by hash) are never re-embedded
        Set<String> chunkHashes = new HashSet<>(), cardHashes = new HashSet<>();
        for (Work w : items) {
            for (Chunk c : w.concept().chunks()) chunkHashes.add(c.hash());
            cardHashes.add(w.concept().cardHash());
        }
        Map<String, float[]> chunkCache = cachedVectors(p, "chunks", "chunk_hash", p.vecCol, chunkHashes);
        Map<String, float[]> cardCache = cachedVectors(p, "concepts", "card_hash", p.cardCol, cardHashes);
        Map<String, String> needChunks = new LinkedHashMap<>(), needCards = new LinkedHashMap<>();
        for (Work w : items) {
            for (Chunk c : w.concept().chunks()) {
                if (chunkCache.containsKey(c.hash())) stats.chunksCached++;
                else needChunks.putIfAbsent(c.hash(), c.embedText());
            }
            if (!cardCache.containsKey(w.concept().cardHash())) needCards.putIfAbsent(w.concept().cardHash(), w.concept().cardText());
        }
        stats.chunksEmbedded += needChunks.size();
        embedInto(emb, needChunks, chunkCache);
        embedInto(emb, needCards, cardCache);

        // 2. rows
        LocalDateTime now = StateStore.utcNow();
        List<Map<String, Object>> conceptRows = new ArrayList<>(), chunkRows = new ArrayList<>(), linkRows = new ArrayList<>();
        for (Work w : items) {
            Concept c = w.concept();
            Map<String, Object> row = new HashMap<>();
            row.put("concept_id", c.conceptId());
            row.put("path", c.path());
            row.put("bundle", c.bundle());
            row.put("dir", c.dir());
            row.put("scope", c.scope());
            row.put("type", c.type());
            row.put("title", c.title());
            row.put("description", c.description());
            row.put("tags", c.tags());
            row.put("resource_url", c.resourceUrl());
            row.put("source_id", c.sourceId());
            row.put("updated", c.updated());
            row.put("acl", w.acl());
            row.put("extra", json(c.extra()));
            row.put("frontmatter", json(c.frontmatter()));
            row.put("body", c.body());
            row.put("content_hash", c.contentHash());
            row.put("acl_hash", Okf.aclHash(w.acl()));
            row.put("card_hash", c.cardHash());
            row.put(p.cardCol, cardCache.get(c.cardHash()));
            row.put("indexed_at", now);
            conceptRows.add(row);
            for (Chunk ch : c.chunks()) {
                Map<String, Object> cr = new HashMap<>();
                cr.put("chunk_id", c.conceptId() + "#" + String.format("%04d", ch.ord()));
                cr.put("concept_id", c.conceptId());
                cr.put("bundle", c.bundle());
                cr.put("dir", c.dir());
                cr.put("scope", c.scope());
                cr.put("type", c.type());
                cr.put("title", c.title());
                cr.put("tags", c.tags());
                cr.put("source_id", c.sourceId());
                cr.put("updated", c.updated());
                cr.put("acl", w.acl());
                cr.put("ord", ch.ord());
                cr.put("heading_path", ch.headingPath());
                cr.put("text", ch.text());
                cr.put("chunk_hash", ch.hash());
                cr.put(p.vecCol, chunkCache.get(ch.hash()));
                chunkRows.add(cr);
            }
            for (Link ln : c.links()) {
                Map<String, Object> lr = new HashMap<>();
                lr.put("from_concept", c.conceptId());
                lr.put("to_concept", ln.toConcept());
                lr.put("to_ref", ln.toRef());
                lr.put("anchor_text", ln.anchorText());
                lr.put("kind", ln.kind());
                lr.put("bundle", c.bundle());
                lr.put("acl", w.acl());
                linkRows.add(lr);
            }
        }

        // 3. writes: replace chunks/links wholesale per concept; upsert the concept row
        List<String> ids = items.stream().map(w -> w.concept().conceptId()).toList();
        p.delete("chunks", Partition.termsIn("concept_id", ids));
        p.delete("links", Partition.termsIn("from_concept", ids));
        p.append("chunks", chunkRows);
        p.append("links", linkRows);
        p.upsert("concepts", conceptRows);
        for (Work w : items) {
            rt.state.log("indexed", Map.of("path", w.info().key(), "chunks", w.concept().chunks().size(),
                    "links", w.concept().links().size(), "partition", p.name));
        }
    }

    private static void embedInto(io.knowledgelake.models.Models.Embedder emb, Map<String, String> need, Map<String, float[]> cache) {
        if (need.isEmpty()) return;
        List<String> keys = new ArrayList<>(need.keySet());
        float[][] vecs = emb.embedDocuments(keys.stream().map(need::get).toList());
        for (int i = 0; i < keys.size(); i++) cache.put(keys.get(i), vecs[i]);
    }

    private static String json(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private void deleteConcepts(Partition p, List<String> ids) {
        p.delete("concepts", Partition.termsIn("concept_id", ids));
        p.delete("chunks", Partition.termsIn("concept_id", ids));
        p.delete("links", Partition.termsIn("from_concept", ids));
    }

    /** Remove whatever rows one bundle object produced in a partition. */
    private void remove(Partition p, String key) {
        String base = Okf.basename(key);
        if (base.equals("index.md")) p.delete("indexes", new TermQuery(new Term("dir", Okf.dirname(key))));
        else if (base.equals("log.md")) p.delete("changes", new TermQuery(new Term("dir", Okf.dirname(key))));
        else deleteConcepts(p, List.of(Okf.conceptIdFor(key)));
    }

    private Set<String> delete(List<String> keys, IndexStats stats) {
        Set<String> touched = new LinkedHashSet<>();
        for (String key : keys) {
            ManifestRow prev = manifest.remove(key);
            String name = prev != null ? prev.partition() : s.partitionFor(bundleOf(key));
            remove(rt.partition(name), key);
            touched.add(name);
            stats.deleted++;
            rt.state.log("deleted", Map.of("path", key));
        }
        return touched;
    }

    // ================================================================== model change

    /**
     * Fill the configured vector field for every row (after switching {@code embedding.column}).
     * The old field stays in place, so the previous model keeps serving until config cuts over.
     */
    public Map<String, Integer> reembed() {
        var emb = rt.embedder();
        Map<String, String> models = rt.state.columnModels();
        models.put(s.embedding.column, emb.name());
        rt.state.setColumnModels(models);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Partition p : rt.partitions()) {
            for (String table : List.of("chunks", "concepts")) {
                if (!p.hasRows(table)) continue;
                List<Map<String, Object>> rows = new ArrayList<>();
                List<String> texts = new ArrayList<>();
                // keep every stored column, including vector fields of other models
                for (var r : p.query(table, MatchAllDocsQuery.INSTANCE, null)) {
                    Map<String, Object> row = new HashMap<>(r);
                    rows.add(row);
                    texts.add(table.equals("chunks") ? chunkText(row) : cardText(row));
                }
                float[][] vecs = emb.embedDocuments(texts);
                String col = table.equals("chunks") ? p.vecCol : p.cardCol;
                for (int i = 0; i < rows.size(); i++) rows.get(i).put(col, vecs[i]);
                p.upsert(table, rows);
                counts.put(p.name + "." + table, rows.size());
            }
            p.commit();
        }
        rt.state.bumpVersion(rt.partitions().stream().map(p -> p.name).toList());
        Map<String, Object> logFields = new LinkedHashMap<>(counts);
        logFields.put("model", emb.name());
        logFields.put("column", s.embedding.column);
        rt.state.log("reembed", logFields);
        rt.state.flushLog();
        return counts;
    }

    private static String chunkText(Map<String, Object> r) {
        String hp = (String) r.get("heading_path");
        return hp == null || hp.isEmpty() ? (String) r.get("text") : hp + "\n" + r.get("text");
    }

    @SuppressWarnings("unchecked")
    private static String cardText(Map<String, Object> r) {
        List<String> parts = new ArrayList<>();
        if (r.get("title") != null && !((String) r.get("title")).isEmpty()) parts.add((String) r.get("title"));
        if (r.get("description") != null && !((String) r.get("description")).isEmpty()) parts.add((String) r.get("description"));
        List<String> tags = (List<String>) r.get("tags");
        if (tags != null && !tags.isEmpty()) parts.add("Tags: " + String.join(", ", tags));
        return String.join("\n", parts);
    }
}
