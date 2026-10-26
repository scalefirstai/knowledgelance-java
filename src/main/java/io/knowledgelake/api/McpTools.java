package io.knowledgelake.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.knowledgelake.auth.AuditLog;
import io.knowledgelake.auth.Authenticator;
import io.knowledgelake.auth.Identity;
import io.knowledgelake.okf.Okf;
import io.knowledgelake.search.Filters;
import io.knowledgelake.search.SearchEngine;
import io.knowledgelake.search.SearchModels.ConceptHit;
import io.knowledgelake.search.SearchModels.SearchRequest;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * MCP server for agents: {@code search}, {@code get_concept}, {@code browse}, {@code related},
 * {@code recent_changes}.
 *
 * <p>The intended agent loop is OKF's progressive disclosure: {@code browse("")} to see the
 * bundles, {@code browse("confluence/spaces/NAV")} to read that directory's index.md, then
 * {@code search} with a {@code dir} filter, {@code get_concept} on the best hit and
 * {@code related} to follow its links.
 *
 * <p>Identity: over streamable HTTP the caller's headers go through the same
 * {@link Authenticator} as REST; over stdio the server acts for {@code KL_MCP_USER} /
 * {@code KL_MCP_GROUPS} (a local, trusted launch).
 */
public final class McpTools {
    static final String INSTRUCTIONS = """
            KnowledgeLake searches company knowledge (JIRA, Confluence, ...) stored as OKF bundles.
            Start broad with `browse` (path "") to see bundles and their index.md, narrow to a directory, then
            `search` (optionally with `dir`) and open the best hit with `get_concept`. Use `related` to follow
            links to runbooks, decisions and tickets, and `recent_changes` for "what changed" questions.
            Cite `resource_url` when you answer.""";

    private final SearchEngine engine;
    private final Authenticator auth;
    private final AuditLog audit;
    private final Identity stdioIdentity;
    private final ObjectMapper json;

    public McpTools(SearchEngine engine, Authenticator auth, AuditLog audit, ObjectMapper json) {
        this.engine = engine;
        this.auth = auth;
        this.audit = audit;
        this.json = json;
        String user = System.getenv("KL_MCP_USER");
        Set<String> groups = new HashSet<>();
        for (String g : System.getenv().getOrDefault("KL_MCP_GROUPS", "").split(",")) if (!g.isBlank()) groups.add(g.strip());
        this.stdioIdentity = user == null || user.isBlank() ? auth.anonymous() : auth.forUser(user, groups);
    }

    // ------------------------------------------------------------------ servers

    public HttpServletStreamableServerTransportProvider httpTransport() {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(new ObjectMapper()))
                .mcpEndpoint("/mcp")
                .contextExtractor(req -> {
                    Map<String, String> headers = new LinkedHashMap<>();
                    Enumeration<String> names = req.getHeaderNames();
                    while (names.hasMoreElements()) {
                        String n = names.nextElement();
                        headers.put(n, req.getHeader(n));
                    }
                    return McpTransportContext.create(Map.of("headers", headers));
                })
                .build();
    }

    public McpSyncServer serve(HttpServletStreamableServerTransportProvider transport) {
        return McpServer.sync(transport).serverInfo("knowledgelake", "0.1.0").instructions(INSTRUCTIONS)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(tools()).build();
    }

    /** Serve over stdin/stdout; {@code onEof} runs when the client closes stdin. */
    public McpSyncServer serveStdio(Runnable onEof) {
        java.io.InputStream in = new java.io.FilterInputStream(System.in) {
            private void check(int n) {
                if (n < 0) onEof.run();
            }

            @Override
            public int read() throws java.io.IOException {
                int b = super.read();
                check(b);
                return b;
            }

            @Override
            public int read(byte[] buf, int off, int len) throws java.io.IOException {
                int n = super.read(buf, off, len);
                check(n);
                return n;
            }
        };
        var transport = new StdioServerTransportProvider(new JacksonMcpJsonMapper(new ObjectMapper()), in, System.out);
        return McpServer.sync(transport).serverInfo("knowledgelake", "0.1.0").instructions(INSTRUCTIONS)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(tools()).build();
    }

    // ------------------------------------------------------------------ identity

    @SuppressWarnings("unchecked")
    Identity identity(McpSyncServerExchange exchange) {
        McpTransportContext ctx = exchange == null ? null : exchange.transportContext();
        Object headers = ctx == null ? null : ctx.get("headers");
        if (!(headers instanceof Map<?, ?> h)) return stdioIdentity;
        return auth.fromHeaders((Map<String, String>) h);
    }

    // ------------------------------------------------------------------ tools

    private static Map<String, Object> prop(String type, String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", type);
        p.put("description", description);
        return p;
    }

    private static Map<String, Object> strings(String description) {
        Map<String, Object> p = prop("array", description);
        p.put("items", Map.of("type", "string"));
        return p;
    }

    private static Map<String, Object> schema(Map<String, Object> props, List<String> required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", props);
        s.put("required", required);
        return s;
    }

    private SyncToolSpecification tool(String name, String description, Map<String, Object> inputSchema,
                                       BiFunction<Identity, Map<String, Object>, Object> handler) {
        McpSchema.Tool t = McpSchema.Tool.builder(name, inputSchema).description(description).build();
        return SyncToolSpecification.builder().tool(t).callHandler((exchange, req) -> {
            try {
                Identity ident = identity(exchange);
                Object result = handler.apply(ident, req.arguments() == null ? Map.of() : req.arguments());
                return McpSchema.CallToolResult.builder().addTextContent(json.writeValueAsString(result))
                        .structuredContent(json.convertValue(result, Map.class)).isError(false).build();
            } catch (Authenticator.AuthException e) {
                return McpSchema.CallToolResult.builder().addTextContent("unauthorized: " + e.getMessage()).isError(true).build();
            } catch (Exception e) {
                return McpSchema.CallToolResult.builder().addTextContent("error: " + e.getMessage()).isError(true).build();
            }
        }).build();
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Object o) {
        if (o == null) return null;
        if (o instanceof List<?> l) return (List<String>) l;
        return List.of(o.toString());
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static int num(Object o, int dflt) {
        return o instanceof Number n ? n.intValue() : o == null ? dflt : Integer.parseInt(o.toString());
    }

    public List<SyncToolSpecification> tools() {
        List<SyncToolSpecification> tools = new ArrayList<>();

        Map<String, Object> sp = new LinkedHashMap<>();
        sp.put("query", prop("string", "Search text; inline filters work too: type:Runbook space:NAV after:2026-09-01"));
        sp.put("type", strings("Concept types, e.g. Runbook, Issue, Decision"));
        sp.put("bundle", strings("Source bundles, e.g. jira, confluence"));
        sp.put("scope", strings("Spaces or projects, e.g. NAV, FUND"));
        sp.put("tags", strings("Tags"));
        sp.put("dir", prop("string", "Only concepts under this directory"));
        sp.put("after", prop("string", "ISO date: updated on or after"));
        sp.put("before", prop("string", "ISO date: updated before"));
        sp.put("k", prop("integer", "Number of concepts (1-25, default 8)"));
        sp.put("expand_links", prop("boolean", "Also return concepts linked from the top hits"));
        tools.add(tool("search", "Hybrid (keyword + semantic) search with optional filters. Exact keys such as FUND-1423 are "
                + "pinned to the top. Returns concepts with their best matching chunks.", schema(sp, List.of("query")), (ident, a) -> {
            String q = str(a.get("query"));
            Filters f = new Filters(list(a.get("type")), list(a.get("bundle")), list(a.get("scope")), list(a.get("tags")),
                    str(a.get("dir")), str(a.get("after")), str(a.get("before")));
            SearchRequest req = new SearchRequest(q).k(Math.max(1, Math.min(num(a.get("k"), 8), 25))).filters(f)
                    .expandLinks(Boolean.TRUE.equals(a.get("expand_links")) ? 1 : 0);
            var resp = engine.search(req, ident);
            audit.record(ident.user(), "mcp", "search", q, Map.of("filters", f,
                    "results", resp.results().stream().map(ConceptHit::conceptId).toList()));
            List<Map<String, Object>> results = new ArrayList<>();
            for (ConceptHit h : resp.results()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("concept_id", h.conceptId());
                m.put("title", h.title());
                m.put("type", h.type());
                m.put("resource_url", h.resourceUrl());
                m.put("updated", h.updated());
                m.put("score", h.score());
                m.put("pinned", h.pinned());
                m.put("via", h.via());
                m.put("chunks", h.chunks().stream().map(c -> Map.of("heading_path", c.headingPath(), "text", c.text())).toList());
                results.add(m);
            }
            return Map.of("results", results);
        }));

        tools.add(tool("get_concept", "Full markdown and frontmatter of one concept (e.g. \"confluence/spaces/NAV/nav-cutoff-runbook\").",
                schema(Map.of("concept_id", prop("string", "Concept id")), List.of("concept_id")), (ident, a) -> {
            String id = str(a.get("concept_id"));
            var c = engine.getConcept(id, ident);
            audit.record(ident.user(), "mcp", "get_concept", null, Map.of("concept_id", id, "found", c != null));
            return c == null ? Map.of("error", "not found") : c;
        }));

        tools.add(tool("browse", "The index.md of a directory plus its subdirectories and concepts. Use \"\" for the bundle list.",
                schema(Map.of("path", prop("string", "Directory, e.g. confluence/spaces/NAV")), List.of()), (ident, a) -> {
            String path = a.get("path") == null ? "" : str(a.get("path"));
            audit.record(ident.user(), "mcp", "browse", null, Map.of("path", path));
            return engine.browse(path, ident);
        }));

        tools.add(tool("related", "Concepts linked to or from a concept (markdown links and ticket-key mentions).",
                schema(Map.of("concept_id", prop("string", "Concept id")), List.of("concept_id")), (ident, a) -> {
            String id = str(a.get("concept_id"));
            audit.record(ident.user(), "mcp", "related", null, Map.of("concept_id", id));
            return Map.of("related", engine.related(id, ident));
        }));

        Map<String, Object> rc = new LinkedHashMap<>();
        rc.put("bundle", prop("string", "Bundle"));
        rc.put("dir", prop("string", "Directory"));
        rc.put("since", prop("string", "ISO date"));
        rc.put("limit", prop("integer", "Max entries (default 30)"));
        tools.add(tool("recent_changes", "Entries from bundle log.md files, newest first. Filter by bundle, directory and ISO date.",
                schema(rc, List.of()), (ident, a) -> {
            audit.record(ident.user(), "mcp", "recent_changes", null, Map.of());
            return Map.of("changes", engine.recentChanges(ident, str(a.get("bundle")), str(a.get("dir")),
                    Okf.parseTimestamp(a.get("since")), Math.max(1, Math.min(num(a.get("limit"), 30), 200))));
        }));
        return tools;
    }
}
