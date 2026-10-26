package io.knowledgelake.store;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.util.BytesRef;

/**
 * Table schemas over Lucene documents. Rows are {@code Map<String, Object>} (as in the Python
 * version's Lance rows); each column has a type that decides how it is indexed and stored.
 *
 * <ul>
 *   <li>{@code KEYWORD}: exact-match filterable string, stored.
 *   <li>{@code KEYWORDS}: multi-valued keyword (tags, acl), stored; reads back as a list.
 *   <li>{@code TEXT}: analyzed for BM25, stored.
 *   <li>{@code STORED}: stored only (body, JSON blobs).
 *   <li>{@code TIMESTAMP}: range-filterable and sortable, stored as epoch millis (UTC).
 *   <li>{@code INT}: filterable int, stored.
 *   <li>{@code VECTOR}: HNSW (cosine) plus a stored copy so the embedding cache can reuse it.
 * </ul>
 */
public final class Schema {
    public enum Type { KEYWORD, KEYWORDS, TEXT, STORED, TIMESTAMP, INT, VECTOR }

    public static final List<String> TABLES = List.of("concepts", "chunks", "links", "indexes", "changes");
    public static final Map<String, String> KEYS =
            Map.of("concepts", "concept_id", "chunks", "chunk_id", "indexes", "dir", "changes", "change_id");

    private Schema() {}

    public static Map<String, Type> columns(String table, String vecCol) {
        return columns(table, vecCol, List.of());
    }

    /**
     * Columns of {@code table}. {@code otherVecCols} are vector fields of other embedding models
     * (from the model registry): kept so a re-embed into a new field preserves the old one until
     * config cuts over.
     */
    public static Map<String, Type> columns(String table, String vecCol, java.util.Collection<String> otherVecCols) {
        Map<String, Type> c = new LinkedHashMap<>();
        switch (table) {
            case "concepts" -> {
                kw(c, "concept_id", "path", "bundle", "dir", "scope", "type");
                c.put("title", Type.TEXT);
                c.put("description", Type.STORED);
                c.put("tags", Type.KEYWORDS);
                c.put("resource_url", Type.STORED);
                c.put("source_id", Type.KEYWORD);
                c.put("updated", Type.TIMESTAMP);
                c.put("acl", Type.KEYWORDS);
                c.put("extra", Type.STORED);
                c.put("frontmatter", Type.STORED);
                c.put("body", Type.STORED);
                kw(c, "content_hash", "acl_hash", "card_hash");
                c.put("card_" + vecCol, Type.VECTOR);
                for (String other : otherVecCols) c.putIfAbsent("card_" + other, Type.VECTOR);
                c.put("indexed_at", Type.TIMESTAMP);
            }
            case "chunks" -> {
                kw(c, "chunk_id", "concept_id", "bundle", "dir", "scope", "type");
                c.put("title", Type.TEXT);
                c.put("tags", Type.KEYWORDS);
                c.put("source_id", Type.KEYWORD);
                c.put("updated", Type.TIMESTAMP);
                c.put("acl", Type.KEYWORDS);
                c.put("ord", Type.INT);
                c.put("heading_path", Type.TEXT);
                c.put("text", Type.TEXT);
                c.put("chunk_hash", Type.KEYWORD);
                c.put(vecCol, Type.VECTOR);
                for (String other : otherVecCols) c.putIfAbsent(other, Type.VECTOR);
            }
            case "links" -> {
                kw(c, "from_concept", "to_concept", "to_ref");
                c.put("anchor_text", Type.STORED);
                kw(c, "kind", "bundle");
                c.put("acl", Type.KEYWORDS);
            }
            case "indexes" -> {
                kw(c, "dir", "bundle", "path");
                c.put("content", Type.STORED);
                c.put("acl", Type.KEYWORDS);
                c.put("indexed_at", Type.TIMESTAMP);
            }
            case "changes" -> {
                kw(c, "change_id", "bundle", "dir");
                c.put("date", Type.TIMESTAMP);
                c.put("text", Type.STORED);
                c.put("concept_ids", Type.KEYWORDS);
                c.put("acl", Type.KEYWORDS);
            }
            default -> throw new IllegalArgumentException("unknown table " + table);
        }
        return c;
    }

    private static void kw(Map<String, Type> c, String... names) {
        for (String n : names) c.put(n, Type.KEYWORD);
    }

    public static long millis(LocalDateTime ts) {
        return ts.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    public static LocalDateTime fromMillis(long ms) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC);
    }

    static byte[] floats(float[] v) {
        ByteBuffer bb = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : v) bb.putFloat(f);
        return bb.array();
    }

    static float[] floats(BytesRef b) {
        ByteBuffer bb = ByteBuffer.wrap(b.bytes, b.offset, b.length).order(ByteOrder.LITTLE_ENDIAN);
        float[] v = new float[b.length / 4];
        for (int i = 0; i < v.length; i++) v[i] = bb.getFloat();
        return v;
    }

    /** Row to Lucene document. Columns present in the row but not in the schema are ignored. */
    public static Document toDocument(Map<String, Type> schema, Map<String, Object> row) {
        Document d = new Document();
        for (var e : schema.entrySet()) {
            String name = e.getKey();
            Object v = row.get(name);
            if (v == null) continue;
            switch (e.getValue()) {
                case KEYWORD -> d.add(new StringField(name, v.toString(), Field.Store.YES));
                case KEYWORDS -> {
                    for (Object o : (List<?>) v) d.add(new StringField(name, o.toString(), Field.Store.YES));
                }
                case TEXT -> d.add(new TextField(name, v.toString(), Field.Store.YES));
                case STORED -> d.add(new StoredField(name, v.toString()));
                case TIMESTAMP -> {
                    long ms = millis((LocalDateTime) v);
                    d.add(new LongPoint(name, ms));
                    d.add(new NumericDocValuesField(name, ms));
                    d.add(new StoredField(name, ms));
                }
                case INT -> {
                    int i = ((Number) v).intValue();
                    d.add(new IntPoint(name, i));
                    d.add(new StoredField(name, i));
                }
                case VECTOR -> {
                    float[] vec = (float[]) v;
                    d.add(new KnnFloatVectorField(name, vec, VectorSimilarityFunction.COSINE));
                    d.add(new StoredField(name, new BytesRef(floats(vec))));
                }
            }
        }
        return d;
    }

    /** Lucene document back to a row, restricted to {@code columns} when non-null. */
    public static Map<String, Object> toRow(Map<String, Type> schema, Document d, List<String> columns) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (var e : schema.entrySet()) {
            String name = e.getKey();
            if (columns != null && !columns.contains(name)) continue;
            switch (e.getValue()) {
                case KEYWORDS -> {
                    List<String> vals = new ArrayList<>();
                    for (IndexableField f : d.getFields(name)) vals.add(f.stringValue());
                    row.put(name, vals);
                }
                case TIMESTAMP -> {
                    IndexableField f = storedField(d, name);
                    row.put(name, f == null ? null : fromMillis(f.numericValue().longValue()));
                }
                case INT -> {
                    IndexableField f = storedField(d, name);
                    row.put(name, f == null ? null : f.numericValue().intValue());
                }
                case VECTOR -> {
                    IndexableField f = storedField(d, name);
                    row.put(name, f == null ? null : floats(f.binaryValue()));
                }
                default -> row.put(name, d.get(name));
            }
        }
        return row;
    }

    private static IndexableField storedField(Document d, String name) {
        for (IndexableField f : d.getFields(name)) {
            if (f.storedValue() != null || f.numericValue() != null || f.binaryValue() != null) return f;
        }
        return null;
    }
}
