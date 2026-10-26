package io.knowledgelake.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.knowledgelake.KlRuntime;
import io.knowledgelake.api.ApiServer;
import io.knowledgelake.api.McpTools;
import io.knowledgelake.auth.AuditLog;
import io.knowledgelake.auth.Authenticator;
import io.knowledgelake.auth.Identity;
import io.knowledgelake.config.Settings;
import io.knowledgelake.config.SettingsLoader;
import io.knowledgelake.index.Events;
import io.knowledgelake.index.Indexer;
import io.knowledgelake.search.Evaluation;
import io.knowledgelake.search.SearchEngine;
import io.knowledgelake.search.SearchModels.ConceptHit;
import io.knowledgelake.search.SearchModels.SearchRequest;
import io.knowledgelake.store.Partition;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** {@code kl}: the KnowledgeLake command line. */
@Command(name = "kl", mixinStandardHelpOptions = true, version = "KnowledgeLake 0.1.0",
        description = "Hybrid search over OKF bundles: index, serve (REST + MCP), query (search, SQL), evaluate.",
        subcommands = {Main.IndexCmd.class, Main.Reconcile.class, Main.Rebuild.class, Main.Optimize.class, Main.Reembed.class,
                Main.Watch.class, Main.Stats.class, Main.Serve.class, Main.Mcp.class, Main.Search.class, Main.Sql.class,
                Main.Eval.class, Main.FetchModels.class})
public final class Main implements Runnable {
    static final ObjectMapper JSON = ApiServer.JSON.copy().enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);

    @Option(names = {"-c", "--config"}, description = "path to kl.yaml (default: $KL_CONFIG or ./kl.yaml)", scope = CommandLine.ScopeType.INHERIT)
    String config;

    @Option(names = {"-v", "--verbose"}, description = "debug logging", scope = CommandLine.ScopeType.INHERIT)
    boolean verbose;

    public static void main(String[] args) {
        boolean v = List.of(args).contains("-v") || List.of(args).contains("--verbose");
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", v ? "debug" : "info");
        System.setProperty("org.slf4j.simpleLogger.log.org.eclipse.jetty", v ? "info" : "warn");
        System.setProperty("org.slf4j.simpleLogger.log.io.modelcontextprotocol", "warn");
        System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.err"); // stdout is MCP's on stdio
        System.exit(new CommandLine(new Main()).execute(args));
    }

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }

    Settings settings() {
        return SettingsLoader.load(config);
    }

    static void print(Object o) throws Exception {
        System.out.println(JSON.writeValueAsString(o));
    }

    abstract static class Sub implements Callable<Integer> {
        @CommandLine.ParentCommand Main parent;

        KlRuntime indexerRuntime() {
            return new KlRuntime(parent.settings(), KlRuntime.Role.INDEXER);
        }
    }

    @Command(name = "index", description = "incremental index (manifest diff)")
    static final class IndexCmd extends Sub {
        public Integer call() throws Exception {
            try (var rt = indexerRuntime()) {
                print(new Indexer(rt).reconcile().asMap());
            }
            return 0;
        }
    }

    @Command(name = "reconcile", description = "list-and-diff every bundle (nightly backstop)")
    static final class Reconcile extends Sub {
        @Option(names = "--recheck-acls", description = "re-read ACLs even when ETags are unchanged") boolean recheck;

        public Integer call() throws Exception {
            try (var rt = indexerRuntime()) {
                print(new Indexer(rt).reconcile(recheck, "").asMap());
            }
            return 0;
        }
    }

    @Command(name = "rebuild", description = "drop derived rows and re-index from the bundles")
    static final class Rebuild extends Sub {
        public Integer call() throws Exception {
            try (var rt = indexerRuntime()) {
                Indexer ix = new Indexer(rt);
                var stats = ix.rebuild();
                ix.optimize();
                print(stats.asMap());
            }
            return 0;
        }
    }

    @Command(name = "optimize", description = "merge away deletes, prune published files")
    static final class Optimize extends Sub {
        public Integer call() throws Exception {
            try (var rt = indexerRuntime()) {
                new Indexer(rt).optimize();
                print(Map.of("ok", true));
            }
            return 0;
        }
    }

    @Command(name = "reembed", description = "fill embedding.column with the configured model (model change)")
    static final class Reembed extends Sub {
        public Integer call() throws Exception {
            try (var rt = indexerRuntime()) {
                print(new Indexer(rt).reembed());
            }
            return 0;
        }
    }

    @Command(name = "watch", description = "follow the change feed (SQS if configured, else polling)")
    static final class Watch extends Sub {
        @Option(names = "--once") boolean once;

        public Integer call() {
            try (var rt = indexerRuntime()) {
                Events.run(rt, once);
            }
            return 0;
        }
    }

    @Command(name = "stats", description = "row counts and index version")
    static final class Stats extends Sub {
        public Integer call() throws Exception {
            try (var rt = indexerRuntime()) {
                Map<String, Object> parts = new LinkedHashMap<>();
                for (Partition p : rt.partitions()) parts.put(p.name, p.stats());
                print(Map.of("version", rt.state.readVersion(), "partitions", parts));
            }
            return 0;
        }
    }

    @Command(name = "serve", description = "REST + MCP")
    static final class Serve extends Sub {
        @Option(names = "--host", defaultValue = "0.0.0.0") String host;
        @Option(names = "--port", defaultValue = "8080") int port;
        @Option(names = "--with-indexer", description = "single-pod mode: also follow the change feed") boolean withIndexer;
        @Option(names = "--no-mcp") boolean noMcp;

        public Integer call() throws Exception {
            Settings s = parent.settings();
            if (withIndexer) {
                Thread t = new Thread(() -> Events.run(new KlRuntime(s, KlRuntime.Role.INDEXER), false), "kl-indexer");
                t.setDaemon(true);
                t.start();
            }
            try (var rt = new KlRuntime(s, KlRuntime.Role.QUERY); var api = new ApiServer(rt, host, port, !noMcp)) {
                api.start();
                api.join();
            }
            return 0;
        }
    }

    @Command(name = "mcp", description = "MCP server for agents")
    static final class Mcp extends Sub {
        @Option(names = "--transport", defaultValue = "stdio", description = "stdio | http") String transport;
        @Option(names = "--host", defaultValue = "127.0.0.1") String host;
        @Option(names = "--port", defaultValue = "8765") int port;

        public Integer call() throws Exception {
            Settings s = parent.settings();
            var rt = new KlRuntime(s, KlRuntime.Role.QUERY);
            if (transport.equals("http")) {
                try (var api = new ApiServer(rt, host, port, true)) {
                    api.start();
                    api.join();
                }
                return 0;
            }
            var engine = new SearchEngine(rt);
            rt.startRefresher();
            CountDownLatch eof = new CountDownLatch(1);
            var server = new McpTools(engine, new Authenticator(s.auth), new AuditLog(rt.auditPath()), ApiServer.JSON)
                    .serveStdio(eof::countDown);
            eof.await(); // the transport runs on its own threads; exit once the client closes stdin
            server.close();
            return 0;
        }
    }

    @Command(name = "fetch-models", description = "download the configured ONNX models into the cache (for images and air-gapped hosts)")
    static final class FetchModels extends Sub {
        public Integer call() throws Exception {
            Settings s = parent.settings();
            s.embedding.provider = "onnx";
            var emb = io.knowledgelake.models.Models.loadEmbedder(s.embedding, s.modelCacheDir);
            emb.embedQuery("check");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("embedding", emb.name());
            if (!"none".equals(s.rerank.provider)) {
                s.rerank.provider = "onnx";
                io.knowledgelake.models.Models.loadReranker(s.rerank, s.modelCacheDir).ifPresent(r -> {
                    r.score("check", List.of("check"));
                    out.put("rerank", r.name());
                });
            }
            print(out);
            return 0;
        }
    }

    static Identity identity(Settings s, String user, String groups) {
        Authenticator auth = new Authenticator(s.auth);
        if (user == null && groups == null) return auth.anonymous();
        Set<String> g = new HashSet<>();
        if (groups != null) for (String x : groups.split(",")) if (!x.isBlank()) g.add(x.strip());
        return auth.forUser(user == null ? "cli" : user, g);
    }

    @Command(name = "search", description = "search from the terminal")
    static final class Search extends Sub {
        @Parameters(arity = "0..1") String text = "";
        @Option(names = "--user") String user;
        @Option(names = "--groups", description = "comma-separated groups to search as") String groups;
        @Option(names = "-k", defaultValue = "5") int k;
        @Option(names = "--mode", defaultValue = "hybrid") String mode;
        @Option(names = "--no-rerank") boolean noRerank;
        @Option(names = "--expand") boolean expand;
        @Option(names = "--json") boolean json;

        public Integer call() throws Exception {
            Settings s = parent.settings();
            try (var rt = new KlRuntime(s, KlRuntime.Role.QUERY); var engine = new SearchEngine(rt)) {
                var resp = engine.search(new SearchRequest(text).k(k).mode(mode).rerank(!noRerank).expandLinks(expand ? 1 : 0),
                        identity(s, user, groups));
                if (json) {
                    print(resp);
                    return 0;
                }
                int i = 1;
                for (ConceptHit h : resp.results()) {
                    String tag = h.pinned() ? " [pinned]" : h.via() != null ? " [via " + h.via().get("from") + "]" : "";
                    System.out.printf("%2d. %s  (%s, %s) score=%.4f%s%n", i++, h.title(), h.type(), h.conceptId(), h.score(), tag);
                    if (!h.chunks().isEmpty()) {
                        var c = h.chunks().get(0);
                        String t = c.text().replace("\n", " ");
                        System.out.println("      " + c.headingPath());
                        System.out.println("      " + (t.length() > 160 ? t.substring(0, 160) + "…" : t));
                    }
                }
                System.err.printf("%n%d results, %d candidates, %s ms%n", resp.results().size(), resp.candidates(),
                        resp.timingsMs().get("total"));
            }
            return 0;
        }
    }

    @Command(name = "sql", description = "SQL with search table functions (interactive without an argument)")
    static final class Sql extends Sub {
        @Parameters(arity = "0..1") String text;
        @Option(names = "--user") String user;
        @Option(names = "--groups") String groups;

        public Integer call() throws Exception {
            Settings s = parent.settings();
            try (var rt = new KlRuntime(s, KlRuntime.Role.QUERY); var engine = new SearchEngine(rt);
                 Connection con = io.knowledgelake.sql.SqlSessions.connect(engine, identity(s, user, groups))) {
                if (text != null) return runOne(con, text) ? 0 : 1;
                System.err.println("KnowledgeLake SQL — tables: concepts chunks links changes indexes; "
                        + "functions: kl_hybrid_search kl_text_search kl_vector_search. Ctrl-D to exit.");
                BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
                while (true) {
                    System.err.print("kl> ");
                    String line = in.readLine();
                    if (line == null) break;
                    if (!line.isBlank()) runOne(con, line);
                }
            }
            return 0;
        }

        static boolean runOne(Connection con, String sql) {
            try (Statement st = con.createStatement()) {
                if (!st.execute(sql)) return true;
                try (ResultSet rs = st.getResultSet()) {
                    ResultSetMetaData md = rs.getMetaData();
                    StringBuilder header = new StringBuilder();
                    for (int i = 1; i <= md.getColumnCount(); i++) header.append(i > 1 ? " | " : "").append(md.getColumnLabel(i));
                    System.out.println(header);
                    System.out.println("-".repeat(Math.min(header.length(), 120)));
                    int n = 0;
                    while (rs.next() && n++ < 200) {
                        StringBuilder row = new StringBuilder();
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            Object o = rs.getObject(i);
                            if (o instanceof java.sql.Array a) o = List.of((Object[]) a.getArray());
                            String v = String.valueOf(o).replace("\n", " ");
                            row.append(i > 1 ? " | " : "").append(v.length() > 80 ? v.substring(0, 80) + "…" : v);
                        }
                        System.out.println(row);
                    }
                }
                return true;
            } catch (Exception e) {
                System.err.println("error: " + e.getMessage());
                return false;
            }
        }
    }

    @Command(name = "eval", description = "golden-set evaluation with CI gates")
    static final class Eval extends Sub {
        @Parameters(arity = "1") Path golden;
        @Option(names = "--groups", defaultValue = "*") String groups;
        @Option(names = "--no-rerank") boolean noRerank;
        @Option(names = "--min-ndcg") Double minNdcg;
        @Option(names = "--min-recall") Double minRecall;
        @Option(names = "--baseline") Path baseline;
        @Option(names = "--max-drop", defaultValue = "0.02") double maxDrop;
        @Option(names = "--write-baseline") Path writeBaseline;
        @Option(names = "--json") boolean json;
        @Option(names = "--warmup", defaultValue = "0", description = "passes over the set before measuring latency") int warmup;

        @SuppressWarnings("unchecked")
        public Integer call() throws Exception {
            Settings s = parent.settings();
            try (var rt = new KlRuntime(s, KlRuntime.Role.QUERY); var engine = new SearchEngine(rt)) {
                Set<String> g = new HashSet<>(List.of(groups.split(",")));
                Map<String, Object> report = Evaluation.run(engine, golden, g, !noRerank, warmup);
                Map<String, Object> summary = new LinkedHashMap<>(report);
                summary.remove("per_query");
                if (json) {
                    print(report);
                } else {
                    for (var q : (List<Map<String, Object>>) report.get("per_query")) {
                        System.out.printf("ndcg=%.3f recall=%.2f mrr=%.2f  %s%n", q.get("ndcg@10"), q.get("recall@50"), q.get("mrr"), q.get("q"));
                    }
                    print(summary);
                }
                if (writeBaseline != null) Files.writeString(writeBaseline, JSON.writeValueAsString(summary));
                var failures = Evaluation.gate(report, minNdcg, minRecall, baseline, maxDrop);
                failures.forEach(f -> System.err.println("GATE FAILED: " + f));
                return failures.isEmpty() ? 0 : 1;
            }
        }
    }
}
