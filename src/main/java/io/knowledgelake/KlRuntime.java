package io.knowledgelake;

import io.knowledgelake.config.Settings;
import io.knowledgelake.config.Uris;
import io.knowledgelake.models.Models;
import io.knowledgelake.store.ObjectStore;
import io.knowledgelake.store.Partition;
import io.knowledgelake.store.StateStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Wires settings into stores, partitions and models. Shared by the indexer and the query tier. */
public final class KlRuntime implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(KlRuntime.class);

    public enum Role { INDEXER, QUERY }

    public final Settings settings;
    public final Role role;
    public final ObjectStore bundles;
    public final ObjectStore kl;
    public final StateStore state;
    private final Map<String, Partition> partitions = new LinkedHashMap<>();
    private volatile Models.Embedder embedder;
    private volatile Optional<Models.Reranker> reranker;
    private volatile Object lastVersion;
    private ScheduledExecutorService refresher;

    public KlRuntime(Settings settings, Role role) {
        this.settings = settings;
        this.role = role;
        this.bundles = ObjectStore.open(settings.bundlesUri, settings.s3);
        this.kl = ObjectStore.open(settings.klUri, settings.s3);
        this.state = new StateStore(kl);
    }

    // ------------------------------------------------------------------ models

    public Models.Embedder embedder() {
        if (embedder == null) {
            synchronized (this) {
                if (embedder == null) embedder = Models.loadEmbedder(settings.embedding, settings.modelCacheDir);
            }
        }
        return embedder;
    }

    public Optional<Models.Reranker> reranker() {
        if (reranker == null) {
            synchronized (this) {
                if (reranker == null) reranker = Models.loadReranker(settings.rerank, settings.modelCacheDir);
            }
        }
        return reranker;
    }

    // ------------------------------------------------------------------ partitions

    /** Local working directory for a partition: the URI itself, or a mirror when it is on S3. */
    private Path localDir(String name, String uri) {
        if (!Uris.isS3(uri)) return Path.of(uri);
        String base = settings.cache.dir != null ? settings.cache.dir
                : Path.of(System.getProperty("java.io.tmpdir"), "knowledgelake").toString();
        return Path.of(base, role == Role.INDEXER ? "indexer" : "mirror", name);
    }

    public synchronized Partition partition(String name) {
        return partitions.computeIfAbsent(name, n -> {
            String uri = Objects.requireNonNull(settings.partitionUris().get(n), "unknown partition " + n);
            ObjectStore remote = Uris.isS3(uri) ? ObjectStore.open(uri, settings.s3) : null;
            List<String> others = new ArrayList<>(state.columnModels().keySet());
            others.remove(settings.embedding.column);
            return new Partition(n, uri, localDir(n, uri), remote, settings.embedding.column, others, role == Role.QUERY);
        });
    }

    public List<Partition> partitions() {
        List<Partition> out = new ArrayList<>();
        for (String n : settings.partitionUris().keySet()) out.add(partition(n));
        return out;
    }

    /** Partitions a caller may touch at all. ACL filters still apply inside each one. */
    public List<Partition> partitionsFor(Set<String> groups) {
        List<Partition> out = new ArrayList<>();
        for (String n : settings.partitionUris().keySet()) {
            List<String> required = settings.requiredGroups(n);
            if (required.isEmpty() || required.stream().anyMatch(groups::contains)) out.add(partition(n));
        }
        return out;
    }

    // ------------------------------------------------------------------ refresh

    /** Query tier: re-sync mirrors and reopen searchers when the indexer bumped the version. */
    public boolean refreshIfChanged() {
        Object version = state.readVersion().get("version");
        if (version == null || version.equals(lastVersion)) return false;
        lastVersion = version;
        List<Partition> open;
        synchronized (this) {
            open = new ArrayList<>(partitions.values());
        }
        for (Partition p : open) p.sync();
        log.info("refreshed partitions to index version {}", version);
        return true;
    }

    public synchronized void startRefresher() {
        if (refresher != null) return;
        refresher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kl-refresh");
            t.setDaemon(true);
            return t;
        });
        long ms = (long) (settings.cache.refreshIntervalS * 1000);
        refresher.scheduleWithFixedDelay(() -> {
            try {
                refreshIfChanged();
            } catch (Exception e) {
                log.warn("refresh failed: {}", e.toString());
            }
        }, ms, ms, TimeUnit.MILLISECONDS);
    }

    public String auditPath() {
        if (settings.auditPath != null) return settings.auditPath;
        return Uris.isS3(settings.klUri) ? null : Uris.join(settings.klUri, "state", "audit.jsonl");
    }

    @Override
    public synchronized void close() {
        if (refresher != null) refresher.shutdownNow();
        partitions.values().forEach(Partition::close);
        partitions.clear();
    }
}
