package io.knowledgelake.index;

import static io.knowledgelake.TestEnv.NAV;
import static io.knowledgelake.TestEnv.ids;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.knowledgelake.KlRuntime;
import io.knowledgelake.TestEnv;
import io.knowledgelake.search.SearchEngine;
import io.knowledgelake.search.SearchModels.SearchRequest;
import io.knowledgelake.store.Partition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexerTest {
    static final String RUNBOOK = "confluence/spaces/NAV/nav-cutoff-runbook";
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

    List<Map<String, Object>> rows(String table, String col, String value) {
        return env.rt.partition("main").query(table, new TermQuery(new Term(col, value)), null);
    }

    @Test
    void initialIndex() {
        var main = env.rt.partition("main").stats();
        assertEquals(8, main.get("concepts"));
        assertEquals(4, main.get("indexes"));
        assertEquals(6, main.get("changes"));
        var restricted = env.rt.partition("restricted").stats();
        assertEquals(1, restricted.get("concepts")); // legal bundle is isolated
        assertEquals(1, restricted.get("indexes"));
        var invalid = env.ix.manifest().values().stream().filter(m -> m.kind().equals("invalid")).map(m -> m.path()).toList();
        assertEquals(List.of("confluence/spaces/NAV/drafts/untitled.md"), invalid);
    }

    @Test
    void reindexIsANoop() {
        var s = env.ix.reconcile();
        assertEquals(0, s.seen);
        assertEquals(0, s.chunksEmbedded);
    }

    @Test
    void manifestSurvivesRestart() {
        env.reopen();
        assertEquals(0, env.ix.reconcile().seen);
    }

    @Test
    void volatileOnlyChangeDoesNotReembed() {
        env.replace(RUNBOOK + ".md", "view_count: 1832", "view_count: 99999");
        var s = env.ix.reconcile();
        assertEquals(1, s.seen);
        assertEquals(1, s.unchanged);
        assertEquals(0, s.chunksEmbedded);
    }

    @Test
    void contentChangeReembedsOnlyChangedChunks() {
        env.replace(RUNBOOK + ".md", "Escalate to the head", "Escalate immediately to the head");
        var s = env.ix.reconcile();
        assertEquals(1, s.indexed);
        assertEquals(1, s.chunksEmbedded);
        assertTrue(s.chunksCached >= 4);
    }

    @Test
    void aclOnlyChangeUpdatesAclWithoutEmbedding() {
        env.replace(RUNBOOK + ".md", "acl: [fund-ops, nav-ops]", "acl: [auditors]");
        var s = env.ix.reconcile();
        assertEquals(1, s.aclOnly);
        assertEquals(0, s.chunksEmbedded);
        for (var tc : List.of(new String[] {"concepts", "concept_id"}, new String[] {"chunks", "concept_id"},
                new String[] {"links", "from_concept"})) {
            var got = rows(tc[0], tc[1], RUNBOOK);
            assertFalse(got.isEmpty(), tc[0]);
            got.forEach(r -> assertEquals(List.of("auditors"), r.get("acl"), tc[0]));
        }
        assertFalse(ids(env.search("late trades", NAV)).contains(RUNBOOK));
    }

    @Test
    void defaultPolicyChangePropagatesOnRecheck() {
        env.replace("jira/projects/FUND/FUND-1450.md", "acl: [fund-ops]\n", "");
        env.ix.reconcile();
        assertEquals(List.of("fund-ops"), rows("concepts", "source_id", "FUND-1450").get(0).get("acl"));
        env.settings.bundles.get("jira").defaultAcl = List.of("fund-ops", "auditors");
        var s = new Indexer(env.rt).reconcile(true, "");
        assertEquals(1, s.aclOnly);
        assertEquals(0, s.chunksEmbedded);
        assertEquals(List.of("fund-ops", "auditors"), rows("concepts", "source_id", "FUND-1450").get(0).get("acl"));
    }

    @Test
    void deleteRemovesAllRows() throws Exception {
        Files.delete(env.bundles.resolve(RUNBOOK + ".md"));
        assertEquals(1, env.ix.reconcile().deleted);
        assertTrue(rows("concepts", "concept_id", RUNBOOK).isEmpty());
        assertTrue(rows("chunks", "concept_id", RUNBOOK).isEmpty());
        assertTrue(rows("links", "from_concept", RUNBOOK).isEmpty());
    }

    @Test
    void pageThatBecomesInvalidStopsBeingServed() {
        env.replace(RUNBOOK + ".md", "type: Runbook\n", "");
        assertEquals(1, env.ix.reconcile().invalid);
        assertTrue(rows("concepts", "concept_id", RUNBOOK).isEmpty());
    }

    @Test
    void eventPathAppliesSpecificKeys() {
        env.write("jira/projects/FUND/FUND-1500.md",
                "---\ntype: Issue\ntitle: New issue about redemption gates\nsource_id: FUND-1500\n---\nBody.");
        var s = env.ix.applyKeys(List.of("jira/projects/FUND/FUND-1500.md", "jira/projects/FUND/notes.txt"), List.of());
        assertEquals(1, s.indexed);
        assertEquals("jira/projects/FUND/FUND-1500", ids(env.search("redemption gates", NAV)).get(0));
    }

    @Test
    void rebuildFromBundles() {
        var before = env.rt.partition("main").stats();
        env.ix.rebuild();
        assertEquals(before, env.rt.partition("main").stats());
    }

    @Test
    void bundleMovedToOtherPartition() {
        env.settings.bundles.get("legal").partition = "main";
        env.reopen();
        env.ix.reconcile(true, "");
        assertEquals(0, env.rt.partition("restricted").stats().get("concepts"));
        assertEquals(0, env.rt.partition("restricted").stats().get("indexes"));
        assertFalse(env.rt.partition("main").query("concepts", new TermQuery(new Term("bundle", "legal")), null).isEmpty());
    }

    @Test
    void modelGuardRefusesMixingVectors() {
        env.write("jira/projects/FUND/FUND-1500.md", "---\ntype: Issue\ntitle: x\n---\nBody.");
        env.settings.embedding.dim = 128; // hash-128 is a different "model" for the same column
        env.reopen();
        var e = assertThrows(IllegalStateException.class, () -> env.ix.reconcile());
        assertTrue(e.getMessage().contains("kl reembed"));
    }

    @Test
    void modelChangeReembedsIntoNewColumnThenCutsOver() {
        env.settings.embedding.column = "vector_v2";
        env.settings.embedding.dim = 128; // a different model may have a different dimension
        env.reopen();
        var counts = env.ix.reembed();
        assertTrue(counts.get("main.chunks") > 0);
        assertEquals(8, counts.get("main.concepts"));
        assertEquals("hash-128", env.rt.state.columnModels().get("vector_v2"));
        env.reopen(); // schema now knows both columns
        Partition main = env.rt.partition("main");
        Map<String, Object> chunk = main.query("chunks", new TermQuery(new Term("concept_id", RUNBOOK)), null).get(0);
        assertEquals(256, ((float[]) chunk.get("vector")).length); // old model still serving data
        assertEquals(128, ((float[]) chunk.get("vector_v2")).length);
        try (var engine = new SearchEngine(env.rt)) {
            assertFalse(engine.search(new SearchRequest("late trades").mode("vector"), NAV).results().isEmpty());
        }
    }

    @Test
    void queryRuntimeIsReadOnly() {
        try (var q = new KlRuntime(env.settings, KlRuntime.Role.QUERY)) {
            Partition p = q.partition("main");
            assertEquals(8, p.count("concepts"));
            Query all = org.apache.lucene.search.MatchAllDocsQuery.INSTANCE;
            assertThrows(IllegalStateException.class, () -> p.delete("concepts", all));
        }
    }
}
