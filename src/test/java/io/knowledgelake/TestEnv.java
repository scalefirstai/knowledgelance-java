package io.knowledgelake;

import io.knowledgelake.auth.Identity;
import io.knowledgelake.config.Settings;
import io.knowledgelake.config.SettingsLoader;
import io.knowledgelake.index.Indexer;
import io.knowledgelake.search.SearchEngine;
import io.knowledgelake.search.SearchModels.SearchRequest;
import io.knowledgelake.search.SearchModels.SearchResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** A fresh copy of the sample bundles, indexed with the hash embedder (no downloads). */
public final class TestEnv implements AutoCloseable {
    public static final Path EXAMPLES = Path.of("examples", "bundles").toAbsolutePath();
    public static final Identity NAV = Identity.of("alice", Set.of("nav-ops", "fund-ops"));
    public static final Identity LEGAL = Identity.of("lee", Set.of("legal"));
    public static final Identity NOBODY = Identity.of("eve", Set.of("contractors"));

    public final Path tmp;
    public final Path bundles;
    public final Settings settings;
    public KlRuntime rt;
    public Indexer ix;
    public SearchEngine engine;

    public TestEnv(Path tmp) {
        this.tmp = tmp;
        this.bundles = tmp.resolve("bundles");
        copyTree(EXAMPLES, bundles);
        this.settings = settings(tmp);
        reopen();
        ix.reconcile();
    }

    public static Settings settings(Path tmp) {
        String root = tmp.toString();
        return SettingsLoader.fromYaml("""
                bundles_uri: %s/bundles
                kl_uri: %s/kl
                bundles:
                  jira: {default_acl: [fund-ops]}
                  confluence: {default_acl: []}
                  legal: {default_acl: [legal], partition: restricted}
                partitions:
                  restricted: {uri: %s/kl/lucene/restricted, required_groups: [legal]}
                embedding: {provider: hash, dim: 256}
                rerank: {provider: none}
                """.formatted(root, root, root));
    }

    /** New runtime/indexer/engine over the same files (e.g. after a settings change). */
    public void reopen() {
        if (rt != null) close();
        rt = new KlRuntime(settings, KlRuntime.Role.INDEXER);
        ix = new Indexer(rt);
        engine = new SearchEngine(rt);
    }

    public SearchResponse search(String q, Identity ident) {
        return engine.search(new SearchRequest(q), ident);
    }

    public SearchResponse search(SearchRequest req, Identity ident) {
        return engine.search(req, ident);
    }

    public static List<String> ids(SearchResponse r) {
        return r.results().stream().map(h -> h.conceptId()).toList();
    }

    public void write(String rel, String content) {
        try {
            Files.writeString(bundles.resolve(rel), content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String read(String rel) {
        try {
            return Files.readString(bundles.resolve(rel));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void replace(String rel, String from, String to) {
        String s = read(rel);
        if (!s.contains(from)) throw new IllegalArgumentException(rel + " does not contain: " + from);
        write(rel, s.replace(from, to));
    }

    static void copyTree(Path from, Path to) {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.toList()) {
                Path dest = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(dest);
                else Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        engine.close();
        rt.close();
    }
}
