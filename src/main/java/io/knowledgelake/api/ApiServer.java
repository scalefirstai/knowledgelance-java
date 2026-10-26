package io.knowledgelake.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.knowledgelake.KlRuntime;
import io.knowledgelake.auth.AuditLog;
import io.knowledgelake.auth.Authenticator;
import io.knowledgelake.auth.Identity;
import io.knowledgelake.okf.Okf;
import io.knowledgelake.search.Answers;
import io.knowledgelake.search.SearchEngine;
import io.knowledgelake.search.SearchModels.ConceptHit;
import io.knowledgelake.search.SearchModels.SearchRequest;
import io.knowledgelake.search.SearchModels.SearchResponse;
import io.knowledgelake.sql.SqlSessions;
import io.knowledgelake.store.Partition;
import io.modelcontextprotocol.server.McpSyncServer;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.core.metrics.Histogram;
import io.prometheus.metrics.expositionformats.ExpositionFormats;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REST front door on Jetty, with MCP (streamable HTTP) mounted at {@code /mcp} in the same process.
 *
 * <pre>
 * POST /v1/search                 hybrid search
 * GET  /v1/concepts/{concept_id}  full markdown + frontmatter
 * GET  /v1/browse?path=...        index.md for a directory (progressive disclosure)
 * GET  /v1/related/{concept_id}   linked concepts
 * GET  /v1/changes                log.md entries
 * POST /v1/sql                    read-only SQL in a session bound to the caller
 * POST /v1/answer                 RAG answer with citations (when answer.base_url is set)
 * GET  /healthz, /readyz, /metrics
 * </pre>
 *
 * Request and response bodies match the Python implementation (snake_case JSON).
 */
public final class ApiServer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ApiServer.class);

    public static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final Histogram SEARCH_LATENCY = Histogram.builder().name("kl_search_seconds")
            .help("End-to-end search latency").classicUpperBounds(0.025, 0.05, 0.1, 0.2, 0.3, 0.5, 1, 2, 5).register();
    private static final Histogram STAGE_LATENCY = Histogram.builder().name("kl_search_stage_seconds")
            .help("Per-stage search latency").labelNames("stage").register();
    private static final Counter REQUESTS = Counter.builder().name("kl_requests_total")
            .help("Requests by endpoint and status").labelNames("endpoint", "status").register();
    private static final Gauge INDEX_AGE = Gauge.builder().name("kl_index_version_age_seconds")
            .help("Seconds since the indexer last committed").register();
    private static final Gauge ROWS = Gauge.builder().name("kl_rows").help("Rows per table").labelNames("partition", "table").register();

    private final KlRuntime rt;
    private final SearchEngine engine;
    private final Authenticator auth;
    private final AuditLog audit;
    private final Server server;
    private final McpSyncServer mcp;

    public ApiServer(KlRuntime rt, String host, int port, boolean mountMcp) {
        this.rt = rt;
        this.engine = new SearchEngine(rt);
        this.auth = new Authenticator(rt.settings.auth);
        this.audit = new AuditLog(rt.auditPath());
        this.server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost(host);
        connector.setPort(port);
        server.addConnector(connector);
        ServletContextHandler ctx = new ServletContextHandler();
        ctx.setContextPath("/");
        if (mountMcp) {
            McpTools tools = new McpTools(engine, auth, audit, JSON);
            var transport = tools.httpTransport();
            this.mcp = tools.serve(transport);
            ServletHolder h = new ServletHolder("mcp", transport);
            h.setAsyncSupported(true);
            ctx.addServlet(h, "/mcp");
            ctx.addServlet(h, "/mcp/*");
        } else {
            this.mcp = null;
        }
        ctx.addServlet(new ServletHolder("rest", new Rest()), "/*");
        server.setHandler(ctx);
    }

    public void start() throws Exception {
        rt.refreshIfChanged();
        rt.startRefresher();
        rt.embedder().embedQuery("warm up"); // load models before the first request, not during it
        rt.reranker().ifPresent(r -> r.score("warm up", List.of("warm up")));
        server.start();
        log.info("KnowledgeLake listening on {}", server.getURI());
    }

    public int port() {
        return ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    public void join() throws InterruptedException {
        server.join();
    }

    @Override
    public void close() {
        if (mcp != null) mcp.close();
        try {
            server.stop();
        } catch (Exception e) {
            log.warn("stopping HTTP server: {}", e.toString());
        }
        engine.close();
    }

    /** Maps client errors to HTTP statuses; everything else is a 500. */
    private static final class HttpError extends RuntimeException {
        final int status;

        HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final class Rest extends HttpServlet {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            String path = req.getRequestURI();
            String endpoint = endpointOf(path);
            int status = 200;
            Object body;
            try {
                body = route(req, path);
            } catch (HttpError e) {
                status = e.status;
                body = Map.of("detail", e.getMessage());
            } catch (Authenticator.AuthException e) {
                status = 401;
                body = Map.of("detail", e.getMessage());
            } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e) {
                status = 422;
                body = Map.of("detail", e.getMessage());
            } catch (Exception e) {
                log.error("{} {} failed", req.getMethod(), path, e);
                status = 500;
                body = Map.of("detail", "internal error");
            }
            REQUESTS.labelValues(endpoint, String.valueOf(status)).inc();
            if (body instanceof RawBody raw) {
                resp.setStatus(status);
                resp.setContentType(raw.contentType());
                resp.getOutputStream().write(raw.bytes());
                return;
            }
            resp.setStatus(status);
            resp.setContentType("application/json");
            JSON.writeValue(resp.getOutputStream(), body);
        }

        private String endpointOf(String path) {
            String[] parts = path.split("/");
            return parts.length > 2 ? parts[2] : parts.length > 1 ? parts[1] : "/";
        }

        private Identity identity(HttpServletRequest req) {
            Map<String, String> headers = new LinkedHashMap<>();
            for (String n : Collections.list(req.getHeaderNames())) headers.put(n, req.getHeader(n));
            return auth.fromHeaders(headers);
        }

        private Object route(HttpServletRequest req, String path) throws Exception {
            String m = req.getMethod();
            if (m.equals("GET") && path.equals("/healthz")) return Map.of("ok", true);
            if (m.equals("GET") && path.equals("/readyz")) return readyz();
            if (m.equals("GET") && path.equals("/metrics")) return metrics();
            if (!path.startsWith("/v1/")) throw new HttpError(404, "not found");
            Identity ident = identity(req);
            if (m.equals("POST") && path.equals("/v1/search")) return search(req, ident);
            if (m.equals("GET") && path.startsWith("/v1/concepts/")) {
                String id = path.substring("/v1/concepts/".length());
                var c = engine.getConcept(id, ident);
                audit.record(ident.user(), "rest", "get_concept", null, Map.of("concept_id", id, "found", c != null));
                if (c == null) throw new HttpError(404, "not found"); // same answer for missing and forbidden
                return c;
            }
            if (m.equals("GET") && path.equals("/v1/browse")) {
                String p = req.getParameter("path") == null ? "" : req.getParameter("path");
                audit.record(ident.user(), "rest", "browse", null, Map.of("path", p));
                return engine.browse(p, ident);
            }
            if (m.equals("GET") && path.startsWith("/v1/related/")) {
                String id = path.substring("/v1/related/".length());
                audit.record(ident.user(), "rest", "related", null, Map.of("concept_id", id));
                return Map.of("concept_id", id, "related", engine.related(id, ident));
            }
            if (m.equals("GET") && path.equals("/v1/changes")) {
                LocalDateTime since = Okf.parseTimestamp(req.getParameter("since"));
                int limit = req.getParameter("limit") == null ? 50 : Integer.parseInt(req.getParameter("limit"));
                return Map.of("changes", engine.recentChanges(ident, req.getParameter("bundle"), req.getParameter("dir"),
                        since, Math.min(limit, 500)));
            }
            if (m.equals("POST") && path.equals("/v1/sql")) {
                Map<?, ?> body = JSON.readValue(req.getInputStream(), Map.class);
                String sql = (String) body.get("sql");
                if (sql == null) throw new IllegalArgumentException("sql is required");
                int maxRows = body.get("max_rows") instanceof Number n ? n.intValue() : 1000;
                audit.record(ident.user(), "sql", "query", sql, Map.of());
                try {
                    var r = SqlSessions.run(engine, ident, sql, Math.min(maxRows, 10_000));
                    return Map.of("columns", r.columns(), "rows", r.rows());
                } catch (java.sql.SQLException e) { // SQL errors are the caller's
                    throw new HttpError(400, e.getMessage());
                }
            }
            if (m.equals("POST") && path.equals("/v1/answer")) {
                SearchRequest sr = JSON.readValue(req.getInputStream(), SearchRequest.class);
                audit.record(ident.user(), "rest", "answer", sr.q, Map.of());
                try {
                    return Answers.answer(engine, rt.settings.answer, sr, ident);
                } catch (Answers.AnswerUnavailable e) {
                    throw new HttpError(501, e.getMessage());
                }
            }
            throw new HttpError(404, "not found");
        }

        private SearchResponse search(HttpServletRequest req, Identity ident) throws IOException {
            SearchRequest sr = JSON.readValue(req.getInputStream(), SearchRequest.class);
            long t = System.nanoTime();
            SearchResponse resp = engine.search(sr, ident);
            SEARCH_LATENCY.observe((System.nanoTime() - t) / 1e9);
            resp.timingsMs().forEach((stage, ms) -> STAGE_LATENCY.labelValues(stage).observe(ms / 1000));
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("filters", sr.filters == null ? Map.of() : sr.filters);
            fields.put("results", resp.results().stream().map(ConceptHit::conceptId).toList());
            audit.record(ident.user(), "rest", "search", sr.q, fields);
            return resp;
        }
    }

    private record RawBody(byte[] bytes, String contentType) {}

    private Map<String, Object> readyz() {
        Map<String, Object> version = rt.state.readVersion();
        Map<String, Object> parts = new LinkedHashMap<>();
        for (Partition p : rt.partitions()) parts.put(p.name, p.stats());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("index_version", version.get("version"));
        out.put("indexed_at", version.get("at"));
        out.put("partitions", parts);
        return out;
    }

    private RawBody metrics() throws IOException {
        Object at = rt.state.readVersion().get("at");
        if (at != null) {
            LocalDateTime ts = LocalDateTime.parse(at.toString().replace("Z", ""));
            INDEX_AGE.set(Duration.between(ts, io.knowledgelake.store.StateStore.utcNow()).toSeconds());
        }
        for (Partition p : rt.partitions()) p.stats().forEach((table, n) -> ROWS.labelValues(p.name, table).set(n));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExpositionFormats.init().getPrometheusTextFormatWriter().write(out, PrometheusRegistry.defaultRegistry.scrape());
        return new RawBody(out.toByteArray(), "text/plain; version=0.0.4; charset=utf-8");
    }
}
