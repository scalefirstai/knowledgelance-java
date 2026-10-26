package io.knowledgelake.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.knowledgelake.okf.Okf;
import io.knowledgelake.store.StateStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Query audit log: who searched, with which filters, and which concepts came back. Query text is
 * stored as a SHA-256, never in clear; the hash still lets an investigator confirm whether a known
 * query was run.
 */
public final class AuditLog {
    private static final Logger log = LoggerFactory.getLogger("knowledgelake.audit");
    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Path path;

    public AuditLog(String path) {
        this.path = path == null ? null : Path.of(path);
        if (this.path != null) {
            try {
                Files.createDirectories(this.path.toAbsolutePath().getParent());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public void record(String user, String iface, String action, String query, Map<String, ?> fields) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", StateStore.utcNow() + "Z");
        entry.put("user", user);
        entry.put("interface", iface);
        entry.put("action", action);
        if (query != null) entry.put("query_sha256", Okf.sha256(query));
        entry.putAll(fields);
        String line;
        try {
            line = JSON.writeValueAsString(entry);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (path == null) {
            log.info(line);
            return;
        }
        synchronized (this) {
            try {
                Files.writeString(path, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
