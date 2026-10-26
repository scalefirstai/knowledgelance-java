package io.knowledgelake.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One partition: five Lucene indexes (concepts, chunks, links, indexes, changes) under one root.
 *
 * <p>The {@code main} partition holds most bundles; restricted bundles can be routed to their own
 * partition with its own URI and IAM role, so a filter bug cannot leak them (coarse isolation).
 *
 * <p>When the partition URI is on S3, {@code localDir} is a working copy: the indexer pulls the
 * latest published commit on open and publishes after every commit; query pods only pull
 * ({@link LuceneSync}). Reads always go through local disk, which is the spec's "acceleration".
 */
public final class Partition implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Partition.class);

    /** Analyzer for every TEXT column and for query parsing. */
    public static final Analyzer ANALYZER = new EnglishAnalyzer();

    public final String name;
    public final String uri;
    public final String vecCol;
    public final String cardCol;
    private final List<String> otherVecCols;
    private final Path localDir;
    private final ObjectStore remote; // null when the partition is local
    private final boolean readOnly;
    private final Map<String, TableIndex> tables = new HashMap<>();

    private final class TableIndex {
        final String table;
        final Map<String, Schema.Type> schema;
        final FSDirectory dir;
        IndexWriter writer;
        SearcherManager manager;
        boolean dirty;

        TableIndex(String table) throws IOException {
            this.table = table;
            this.schema = Schema.columns(table, vecCol, otherVecCols);
            Path p = localDir.resolve(table);
            Files.createDirectories(p);
            this.dir = FSDirectory.open(p);
        }

        IndexWriter writer() throws IOException {
            if (readOnly) throw new IllegalStateException("partition " + name + " is read-only");
            if (writer == null) {
                IndexWriterConfig cfg = new IndexWriterConfig(ANALYZER);
                cfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
                writer = new IndexWriter(dir, cfg);
                if (manager != null) manager.close();
                manager = new SearcherManager(writer, null);
            }
            return writer;
        }

        SearcherManager manager() throws IOException {
            if (manager == null) {
                if (!readOnly) {
                    writer();
                } else if (DirectoryReader.indexExists(dir)) {
                    manager = new SearcherManager(dir, null);
                }
            }
            return manager;
        }
    }

    public Partition(String name, String uri, Path localDir, ObjectStore remote, String vecCol,
                     List<String> otherVecCols, boolean readOnly) {
        this.name = name;
        this.otherVecCols = List.copyOf(otherVecCols);
        this.uri = uri;
        this.localDir = localDir;
        this.remote = remote;
        this.vecCol = vecCol;
        this.cardCol = "card_" + vecCol;
        this.readOnly = readOnly;
        try {
            Files.createDirectories(localDir);
            if (remote != null) sync();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Map<String, Schema.Type> schema(String table) {
        return Schema.columns(table, vecCol, otherVecCols);
    }

    private synchronized TableIndex t(String table) {
        return tables.computeIfAbsent(table, n -> {
            try {
                return new TableIndex(n);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    // ================================================================== writes

    public synchronized void upsert(String table, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return;
        TableIndex ti = t(table);
        String key = Schema.KEYS.get(table);
        try {
            IndexWriter w = ti.writer();
            for (Map<String, Object> row : rows) {
                w.updateDocument(new Term(key, row.get(key).toString()), Schema.toDocument(ti.schema, row));
            }
            ti.dirty = true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void append(String table, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return;
        TableIndex ti = t(table);
        try {
            IndexWriter w = ti.writer();
            List<Document> docs = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) docs.add(Schema.toDocument(ti.schema, row));
            w.addDocuments(docs);
            ti.dirty = true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void delete(String table, Query query) {
        TableIndex ti = t(table);
        try {
            ti.writer().deleteDocuments(query);
            ti.dirty = true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Commit every changed table, make it visible to searchers, and publish to S3 if remote. */
    public synchronized boolean commit() {
        boolean any = false;
        try {
            for (TableIndex ti : tables.values()) {
                if (!ti.dirty) continue;
                ti.writer.commit();
                ti.manager.maybeRefreshBlocking();
                ti.dirty = false;
                any = true;
                if (remote != null) LuceneSync.publish(ti.dir.getDirectory(), remote, ti.table, false);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return any;
    }

    /** Scheduled maintenance: merge away deletes, then prune unreferenced remote files. */
    public synchronized void optimize() {
        try {
            for (String table : Schema.TABLES) {
                TableIndex ti = t(table);
                IndexWriter w = ti.writer();
                w.forceMergeDeletes();
                w.maybeMerge();
                w.commit();
                ti.manager.maybeRefreshBlocking();
                if (remote != null) LuceneSync.publish(ti.dir.getDirectory(), remote, table, true);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Remove every row from every table. */
    public synchronized void truncate() {
        for (String table : Schema.TABLES) delete(table, MatchAllDocsQuery.INSTANCE);
        commit();
    }

    // ================================================================== reads

    /** Query-tier refresh: pull the latest published commit (S3) and reopen searchers. */
    public synchronized void sync() {
        try {
            if (remote != null) {
                for (String table : Schema.TABLES) {
                    if (readOnly || !tables.containsKey(table)) LuceneSync.pull(remote, table, localDir.resolve(table));
                }
            }
            for (TableIndex ti : tables.values()) {
                if (ti.manager != null) ti.manager.maybeRefreshBlocking();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public record Hit(Map<String, Object> row, float score) {}

    private List<Hit> collect(TableIndex ti, IndexSearcher s, TopDocs top, List<String> columns) throws IOException {
        List<Hit> out = new ArrayList<>(top.scoreDocs.length);
        var stored = s.storedFields();
        for (ScoreDoc sd : top.scoreDocs) {
            out.add(new Hit(Schema.toRow(ti.schema, stored.document(sd.doc), columns), sd.score));
        }
        return out;
    }

    /** Top-{@code limit} rows matching {@code query}, best score first. */
    public List<Hit> search(String table, Query query, int limit, List<String> columns) {
        TableIndex ti = t(table);
        try {
            SearcherManager m = ti.manager();
            if (m == null) return List.of();
            IndexSearcher s = m.acquire();
            try {
                int n = Math.max(1, Math.min(limit, Math.max(1, s.getIndexReader().maxDoc())));
                return collect(ti, s, s.search(query, n), columns);
            } finally {
                m.release(s);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every row matching {@code filter} (no ranking), up to {@code limit}. */
    public List<Map<String, Object>> query(String table, Query filter, List<String> columns, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Hit h : search(table, filter == null ? MatchAllDocsQuery.INSTANCE : filter, limit, columns)) out.add(h.row());
        return out;
    }

    public List<Map<String, Object>> query(String table, Query filter, List<String> columns) {
        return query(table, filter, columns, Integer.MAX_VALUE);
    }

    /** Approximate nearest neighbours with {@code filter} applied as a pre-filter inside HNSW. */
    public List<Hit> knn(String table, String field, float[] vector, int k, Query filter, List<String> columns) {
        return search(table, new KnnFloatVectorQuery(field, vector, k, filter), k, columns);
    }

    public int count(String table) {
        TableIndex ti = t(table);
        try {
            SearcherManager m = ti.manager();
            if (m == null) return 0;
            IndexSearcher s = m.acquire();
            try {
                return s.getIndexReader().numDocs();
            } finally {
                m.release(s);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean hasRows(String table) {
        return count(table) > 0;
    }

    public Map<String, Integer> stats() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String table : Schema.TABLES) out.put(table, count(table));
        return out;
    }

    public static Query termsIn(String field, Collection<String> values) {
        List<BytesRef> refs = new ArrayList<>();
        for (String v : Set.copyOf(values)) refs.add(new BytesRef(v));
        return new TermInSetQuery(field, refs);
    }

    @Override
    public synchronized void close() {
        for (TableIndex ti : tables.values()) {
            try {
                if (ti.manager != null) ti.manager.close();
                if (ti.writer != null) ti.writer.close();
                ti.dir.close();
            } catch (IOException e) {
                log.warn("closing {}/{}: {}", name, ti.table, e.toString());
            }
        }
        tables.clear();
    }
}
