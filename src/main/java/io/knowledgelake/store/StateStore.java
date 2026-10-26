package io.knowledgelake.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Indexer state under {@code _kl/state}:
 *
 * <ul>
 *   <li>{@code manifest.parquet}: one row per bundle object (path, etag, content_hash, acl_hash,
 *       kind, partition, indexed_at, error). It makes the indexer incremental and is what nightly
 *       reconcile diffs against. Same columns as the Python implementation.
 *   <li>{@code version.json}: bumped after every commit so query pods know to refresh.
 *   <li>{@code models.json}: vector column to the embedding model that filled it.
 *   <li>{@code ingest_log/<date>.jsonl}: what the indexer did, written once per commit.
 * </ul>
 */
public final class StateStore {
    public static final String MANIFEST_KEY = "state/manifest.parquet";
    public static final String VERSION_KEY = "state/version.json";
    public static final String MODELS_KEY = "state/models.json";
    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public record ManifestRow(String path, String etag, String contentHash, String aclHash, String kind,
                              String partition, LocalDateTime indexedAt, String error) {}

    private final ObjectStore store;
    private final List<String> logBuffer = new ArrayList<>();

    public StateStore(ObjectStore store) {
        this.store = store;
    }

    public static LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------ manifest

    public Map<String, ManifestRow> loadManifest() {
        Map<String, ManifestRow> out = new TreeMap<>();
        if (store.head(MANIFEST_KEY).isEmpty()) return out;
        try {
            Path tmp = Files.createTempFile("kl-manifest", ".parquet");
            try {
                Files.write(tmp, store.getBytes(MANIFEST_KEY));
                try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
                     Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT path, etag, content_hash, acl_hash, kind, partition, indexed_at, error FROM read_parquet('"
                             + tmp.toString().replace("'", "''") + "')")) {
                    while (rs.next()) {
                        Timestamp ts = rs.getTimestamp(7);
                        out.put(rs.getString(1), new ManifestRow(rs.getString(1), rs.getString(2), rs.getString(3),
                                rs.getString(4), rs.getString(5), rs.getString(6),
                                ts == null ? null : ts.toLocalDateTime(), rs.getString(8)));
                    }
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read manifest", e);
        }
        return out;
    }

    public void saveManifest(Map<String, ManifestRow> manifest) {
        try {
            Path tmp = Files.createTempFile("kl-manifest", ".parquet");
            try {
                try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
                    try (Statement st = c.createStatement()) {
                        st.execute("CREATE TABLE m (path VARCHAR, etag VARCHAR, content_hash VARCHAR, acl_hash VARCHAR, "
                                + "kind VARCHAR, partition VARCHAR, indexed_at TIMESTAMP, error VARCHAR)");
                    }
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO m VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                        for (ManifestRow r : new TreeMap<>(manifest).values()) {
                            ps.setString(1, r.path());
                            ps.setString(2, r.etag());
                            ps.setString(3, r.contentHash());
                            ps.setString(4, r.aclHash());
                            ps.setString(5, r.kind());
                            ps.setString(6, r.partition());
                            ps.setTimestamp(7, r.indexedAt() == null ? null : Timestamp.valueOf(r.indexedAt()));
                            ps.setString(8, r.error());
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                    try (Statement st = c.createStatement()) {
                        st.execute("COPY m TO '" + tmp.toString().replace("'", "''") + "' (FORMAT parquet, COMPRESSION zstd)");
                    }
                }
                store.putBytes(MANIFEST_KEY, Files.readAllBytes(tmp));
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot write manifest", e);
        }
    }

    // ------------------------------------------------------------------ ingest log

    public synchronized void log(String event, Map<String, ?> fields) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("ts", utcNow().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z");
        line.put("event", event);
        line.putAll(fields);
        try {
            logBuffer.add(JSON.writeValueAsString(line));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void flushLog() {
        if (logBuffer.isEmpty()) return;
        String key = "state/ingest_log/" + utcNow().toLocalDate() + ".jsonl";
        // object stores have no append; one read-modify-write per commit is fine at this volume
        byte[] prev = store.head(key).isPresent() ? store.getBytes(key) : new byte[0];
        byte[] add = (String.join("\n", logBuffer) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] all = new byte[prev.length + add.length];
        System.arraycopy(prev, 0, all, 0, prev.length);
        System.arraycopy(add, 0, all, prev.length, add.length);
        store.putBytes(key, all);
        logBuffer.clear();
    }

    // ------------------------------------------------------------------ version marker

    public Map<String, Object> readVersion() {
        if (store.head(VERSION_KEY).isEmpty()) return Map.of();
        try {
            return JSON.readValue(store.getBytes(VERSION_KEY), new TypeReference<>() {});
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public long bumpVersion(List<String> partitions) {
        Object cur = readVersion().get("version");
        long v = (cur instanceof Number n ? n.longValue() : 0) + 1;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", v);
        payload.put("at", utcNow().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z");
        payload.put("partitions", partitions);
        try {
            store.putBytes(VERSION_KEY, JSON.writeValueAsBytes(payload));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return v;
    }

    // ------------------------------------------------------------------ embedding model registry

    public Map<String, String> columnModels() {
        if (store.head(MODELS_KEY).isEmpty()) return new LinkedHashMap<>();
        try {
            return JSON.readValue(store.getBytes(MODELS_KEY), new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void setColumnModels(Map<String, String> models) {
        try {
            store.putBytes(MODELS_KEY, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(models));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
