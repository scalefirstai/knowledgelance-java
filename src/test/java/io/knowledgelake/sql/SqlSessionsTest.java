package io.knowledgelake.sql;

import static io.knowledgelake.TestEnv.NAV;
import static io.knowledgelake.TestEnv.NOBODY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.knowledgelake.TestEnv;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlSessionsTest {
    @TempDir Path tmp;
    TestEnv env;

    @BeforeEach
    void setUp() {
        env = new TestEnv(tmp);
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    @Test
    void searchTableFunctionJoinsWithAclFilteredTables() throws Exception {
        try (var con = SqlSessions.connect(env.engine, NAV); var st = con.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT c.title, s.score FROM kl_hybrid_search('late trades cut-off', k := 5) s "
                    + "JOIN concepts c USING (concept_id) WHERE c.type = 'Runbook'")) {
                assertTrue(rs.next());
                assertEquals("NAV cut-off runbook", rs.getString(1));
            }
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM concepts WHERE list_contains(acl, 'hr')")) {
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM kl_text_search('FUND-1423')")) {
                rs.next();
                assertTrue(rs.getInt(1) >= 1);
            }
        }
        var out = SqlSessions.run(env.engine, NOBODY, "SELECT count(*) AS n FROM chunks", 10);
        assertEquals(List.of(List.of(0L)), out.rows());
    }

    @Test
    void blocksFileAccessAndConfigChanges() {
        assertThrows(SQLException.class, () -> SqlSessions.run(env.engine, NAV, "SELECT * FROM read_text('/etc/passwd')", 10));
        assertThrows(SQLException.class, () -> SqlSessions.run(env.engine, NAV, "SET enable_external_access = true", 10));
    }
}
