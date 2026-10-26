package io.knowledgelake.sql;

import io.knowledgelake.auth.Identity;
import io.knowledgelake.search.QueryParsing;
import io.knowledgelake.search.SearchEngine;
import io.knowledgelake.search.SearchModels.ChunkHit;
import io.knowledgelake.search.SearchModels.ConceptHit;
import io.knowledgelake.search.SearchModels.SearchRequest;
import io.knowledgelake.store.Partition;
import io.knowledgelake.store.Schema;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBColumnType;
import org.duckdb.DuckDBConnection;
import org.duckdb.DuckDBDataChunkWriter;
import org.duckdb.DuckDBFunctions;
import org.duckdb.DuckDBTableFunction;
import org.duckdb.DuckDBTableFunctionBindInfo;
import org.duckdb.DuckDBTableFunctionCallInfo;
import org.duckdb.DuckDBTableFunctionInitInfo;
import org.duckdb.DuckDBValue;
import org.duckdb.DuckDBWritableVector;

/**
 * SQL front door: DuckDB sessions bound to one caller identity.
 *
 * <p>Each session gets {@code concepts}, {@code chunks}, {@code links}, {@code changes} and
 * {@code indexes} as tables copied from the indexes <em>after</em> ACL filtering, so no SQL the
 * caller writes can reach a row they may not see (no unfiltered view exists in the session).
 * Search is exposed as real DuckDB table functions that mirror Spice's search UDTFs:
 *
 * <pre>
 * SELECT c.title, c.resource_url, s.score
 * FROM kl_hybrid_search('NAV cut-off exceptions', k := 20) s
 * JOIN concepts c USING (concept_id)
 * WHERE c.type = 'Runbook';
 * </pre>
 *
 * External file/network access is disabled and the configuration locked before user SQL runs.
 */
public final class SqlSessions {
    private SqlSessions() {}

    private static String sqlType(Schema.Type t) {
        return switch (t) {
            case KEYWORDS -> "VARCHAR[]";
            case TIMESTAMP -> "TIMESTAMP";
            case INT -> "INTEGER";
            default -> "VARCHAR";
        };
    }

    public static Connection connect(SearchEngine engine, Identity ident) throws SQLException {
        DuckDBConnection con = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
        try {
            var acl = QueryParsing.acl(ident.groups());
            var partitions = engine.runtime().partitionsFor(ident.groups());
            String vecCol = engine.runtime().settings.embedding.column;
            for (String table : Schema.TABLES) {
                Map<String, Schema.Type> cols = new LinkedHashMap<>(Schema.columns(table, vecCol));
                cols.values().removeIf(t -> t == Schema.Type.VECTOR); // vectors stay out of SQL
                List<String> defs = new ArrayList<>();
                cols.forEach((n, t) -> defs.add(n + " " + sqlType(t)));
                try (Statement st = con.createStatement()) {
                    st.execute("CREATE TABLE " + table + " (" + String.join(", ", defs) + ")");
                }
                try (DuckDBAppender app = con.createAppender(table)) {
                    for (Partition p : partitions) {
                        for (Map<String, Object> row : p.query(table, acl, new ArrayList<>(cols.keySet()))) {
                            app.beginRow();
                            for (var c : cols.entrySet()) append(app, row.get(c.getKey()));
                            app.endRow();
                        }
                    }
                }
            }
            registerSearch(con, engine, ident, "kl_hybrid_search", "hybrid");
            registerSearch(con, engine, ident, "kl_text_search", "text");
            registerSearch(con, engine, ident, "kl_vector_search", "vector");
            try (Statement st = con.createStatement()) {
                st.execute("SET enable_external_access = false");
                st.execute("SET lock_configuration = true");
            }
            return con;
        } catch (SQLException | RuntimeException e) {
            con.close();
            throw e;
        }
    }

    private static void append(DuckDBAppender app, Object v) throws SQLException {
        if (v == null) app.appendNull();
        else if (v instanceof String s) app.append(s);
        else if (v instanceof LocalDateTime ts) app.append(ts);
        else if (v instanceof Integer i) app.append(i.intValue());
        else if (v instanceof List<?> l) app.append(l);
        else app.append(v.toString());
    }

    // ------------------------------------------------------------------ search table functions

    private record Bound(List<Object[]> rows) {}

    private static final class Cursor {
        int next = 0;
    }

    private static final String[] COLS = {"rank", "concept_id", "chunk_id", "title", "type", "heading_path", "snippet", "score", "pinned"};

    private static void registerSearch(DuckDBConnection con, SearchEngine engine, Identity ident, String name, String mode)
            throws SQLException {
        DuckDBTableFunction<Bound, Cursor, Object> fn = new DuckDBTableFunction<>() {
            @Override
            public Bound bind(DuckDBTableFunctionBindInfo info) {
                info.addResultColumn("rank", DuckDBColumnType.INTEGER);
                info.addResultColumn("concept_id", DuckDBColumnType.VARCHAR);
                info.addResultColumn("chunk_id", DuckDBColumnType.VARCHAR);
                info.addResultColumn("title", DuckDBColumnType.VARCHAR);
                info.addResultColumn("type", DuckDBColumnType.VARCHAR);
                info.addResultColumn("heading_path", DuckDBColumnType.VARCHAR);
                info.addResultColumn("snippet", DuckDBColumnType.VARCHAR);
                info.addResultColumn("score", DuckDBColumnType.DOUBLE);
                info.addResultColumn("pinned", DuckDBColumnType.BOOLEAN);
                String q = info.getParameter(0).getString();
                int k = 10;
                try {
                    DuckDBValue kv = info.getNamedParameter("k");
                    if (kv != null && !kv.isNull()) k = Math.max(1, Math.min(kv.getInt(), 100));
                } catch (Exception absent) { // the driver throws when `k := ...` was not given
                    // keep the default
                }
                var resp = engine.search(new SearchRequest(q).k(k).mode(mode), ident);
                List<Object[]> rows = new ArrayList<>();
                for (ConceptHit h : resp.results()) {
                    ChunkHit best = h.chunks().isEmpty() ? null : h.chunks().get(0);
                    rows.add(new Object[] {rows.size() + 1, h.conceptId(), best == null ? null : best.chunkId(), h.title(),
                            h.type(), best == null ? null : best.headingPath(), best == null ? h.description() : best.snippet(),
                            h.score(), h.pinned()});
                }
                info.setCardinality(rows.size(), true);
                return new Bound(rows);
            }

            @Override
            public Cursor init(DuckDBTableFunctionInitInfo info) {
                info.setMaxThreads(1);
                return new Cursor();
            }

            @Override
            public long apply(DuckDBTableFunctionCallInfo call, DuckDBDataChunkWriter out) {
                Bound b = call.getBindData();
                Cursor c = call.getInitData();
                long n = 0;
                while (c.next < b.rows().size() && n < out.capacity()) {
                    Object[] r = b.rows().get(c.next++);
                    for (int col = 0; col < COLS.length; col++) write(out.vector(col), n, r[col]);
                    n++;
                }
                return n;
            }
        };
        DuckDBFunctions.tableFunction()
                .withName(name)
                .withParameter(DuckDBColumnType.VARCHAR)
                .withNamedParameter("k", DuckDBColumnType.INTEGER)
                .withFunction(fn)
                .register(con);
    }

    private static void write(DuckDBWritableVector v, long row, Object value) {
        if (value == null) v.setNull(row);
        else if (value instanceof Integer i) v.setInt(row, i);
        else if (value instanceof Double d) v.setDouble(row, d);
        else if (value instanceof Boolean b) v.setBoolean(row, b);
        else v.setString(row, value.toString());
    }

    // ------------------------------------------------------------------ one-shot query

    public record Result(List<String> columns, List<List<Object>> rows) {}

    public static Result run(SearchEngine engine, Identity ident, String sql, int maxRows) throws SQLException {
        try (Connection con = connect(engine, ident); Statement st = con.createStatement()) {
            st.setMaxRows(maxRows);
            if (!st.execute(sql)) return new Result(List.of(), List.of());
            try (ResultSet rs = st.getResultSet()) {
                ResultSetMetaData md = rs.getMetaData();
                List<String> cols = new ArrayList<>();
                for (int i = 1; i <= md.getColumnCount(); i++) cols.add(md.getColumnLabel(i));
                List<List<Object>> rows = new ArrayList<>();
                while (rs.next() && rows.size() < maxRows) {
                    List<Object> row = new ArrayList<>();
                    for (int i = 1; i <= cols.size(); i++) {
                        Object o = rs.getObject(i);
                        if (o instanceof java.sql.Array a) o = List.of((Object[]) a.getArray());
                        else if (o instanceof java.sql.Timestamp ts) o = ts.toLocalDateTime();
                        row.add(o);
                    }
                    rows.add(row);
                }
                return new Result(cols, rows);
            }
        }
    }
}
