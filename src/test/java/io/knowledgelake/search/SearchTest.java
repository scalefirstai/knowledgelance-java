package io.knowledgelake.search;

import static io.knowledgelake.TestEnv.LEGAL;
import static io.knowledgelake.TestEnv.NAV;
import static io.knowledgelake.TestEnv.NOBODY;
import static io.knowledgelake.TestEnv.ids;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.knowledgelake.TestEnv;
import io.knowledgelake.auth.Identity;
import io.knowledgelake.search.SearchModels.ConceptHit;
import io.knowledgelake.search.SearchModels.SearchRequest;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchTest {
    static final String RUNBOOK = "confluence/spaces/NAV/nav-cutoff-runbook";
    static final List<String> PATTERNS = List.of("\\b[A-Z][A-Z0-9]{1,9}-\\d+\\b");
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
    void parseQueryExtractsFiltersAndIdentifiers() {
        var pq = QueryParsing.parse("late \"trade cut-off\" type:Runbook,Page space:NAV after:2026-09-01 FUND-1423", PATTERNS);
        assertEquals("late trade cut-off FUND-1423", pq.text());
        assertEquals(List.of("Runbook", "Page"), pq.filters().type);
        assertEquals(List.of("NAV"), pq.filters().scope);
        assertEquals(LocalDateTime.of(2026, 9, 1, 0, 0), pq.filters().afterTs());
        assertEquals(List.of("FUND-1423"), pq.identifiers());
    }

    @Test
    void hybridSearchFindsRunbook() {
        var resp = env.search("late trades booked after the cut-off", NAV);
        assertTrue(ids(resp).subList(0, 3).contains(RUNBOOK));
        ConceptHit hit = resp.results().get(0);
        assertFalse(hit.chunks().isEmpty());
        assertTrue(hit.chunks().size() <= 3);
        assertTrue(hit.chunks().get(0).breakdown().keySet().containsAll(Set.of("bm25_rank", "vector_rank", "card_rank", "rrf")));
    }

    @Test
    void aclPrefilterHidesEverythingForOutsiders() {
        assertEquals(List.of(), ids(env.search("late trades", NOBODY)));
        assertFalse(ids(env.search("salary bands grade", NAV)).contains("confluence/spaces/HR/salary-bands"));
    }

    @Test
    void restrictedPartitionOnlyForRequiredGroup() {
        assertFalse(ids(env.search("administrator NAV SLA", NAV)).contains("legal/contracts/administrator-agreement"));
        assertEquals("legal/contracts/administrator-agreement", ids(env.search("administrator NAV SLA", LEGAL)).get(0));
    }

    @Test
    void publicAclEntry() {
        env.replace("confluence/spaces/NAV/fx-rates-sourcing.md", "acl: [fund-ops, nav-ops, pricing]", "acl: [\"*\"]");
        env.ix.reconcile();
        assertTrue(ids(env.search("FX fix fallback", NOBODY)).contains("confluence/spaces/NAV/fx-rates-sourcing"));
    }

    @Test
    void exactIdentifierIsPinned() {
        var resp = env.search("FUND-1388 cut-off late trades", NAV);
        assertEquals("jira/projects/FUND/FUND-1388", resp.results().get(0).conceptId());
        assertTrue(resp.results().get(0).pinned());
    }

    @Test
    void pinsRespectAcl() {
        assertFalse(ids(env.search("SEC-77", NAV)).contains("jira/projects/SEC/SEC-77"));
        assertEquals("jira/projects/SEC/SEC-77", ids(env.search("SEC-77", Identity.of("s", Set.of("security-team")))).get(0));
    }

    @Test
    void metadataFilters() {
        var f = new Filters(List.of("Decision"), null, null, null, null, null, null);
        assertEquals(List.of("confluence/spaces/NAV/late-trades-decision"), ids(env.search(new SearchRequest("cut-off").filters(f), NAV)));
        assertTrue(env.search("nav bundle:jira", NAV).results().stream().allMatch(h -> h.type().equals("Issue")));
        var after = env.search("nav after:2026-10-15", NAV);
        assertFalse(after.results().isEmpty());
        assertTrue(after.results().stream().allMatch(h -> !h.updated().isBefore(LocalDateTime.of(2026, 10, 15, 0, 0))));
        var scoped = env.search("fix dir:confluence/spaces/NAV", NAV);
        assertFalse(scoped.results().isEmpty());
        assertTrue(scoped.results().stream().allMatch(h -> h.dir().startsWith("confluence/spaces/NAV")));
    }

    @Test
    void filterOnlyQueryListsNewestFirst() {
        var resp = env.search(new SearchRequest("type:Issue").k(10), NAV);
        var dates = resp.results().stream().map(ConceptHit::updated).toList();
        assertEquals(3, dates.size());
        assertTrue(dates.get(0).isAfter(dates.get(1)) || dates.get(0).equals(dates.get(1)));
        assertTrue(!dates.get(1).isBefore(dates.get(2)));
    }

    @Test
    void textAndVectorModes() {
        assertTrue(ids(env.search(new SearchRequest("late trades").mode("text"), NAV)).contains(RUNBOOK));
        assertFalse(ids(env.search(new SearchRequest("late trades").mode("vector"), NAV)).isEmpty());
    }

    @Test
    void linkExpansionAddsLinkedConceptsBelowDirectHits() {
        var resp = env.search(new SearchRequest("swing pricing thresholds").k(1).expandLinks(1), NAV);
        var direct = resp.results().stream().filter(h -> h.via() == null).toList();
        assertEquals(1, direct.size());
        resp.results().stream().filter(h -> h.via() != null).forEach(h -> assertTrue(h.score() < direct.get(0).score()));
    }

    @Test
    void expansionFollowsLinksFromTicket() {
        var resp = env.search(new SearchRequest("FUND-1423").k(1).expandLinks(1), NAV);
        Set<String> via = new HashSet<>();
        resp.results().stream().filter(h -> h.via() != null).forEach(h -> via.add(h.conceptId()));
        assertTrue(via.contains(RUNBOOK));
        assertTrue(via.contains("confluence/spaces/NAV/late-trades-decision"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getConceptAndAcl() {
        var c = env.engine.getConcept(RUNBOOK, NAV);
        assertEquals("Runbook", c.get("type"));
        assertTrue(((String) c.get("body")).contains("## Cut-off"));
        assertEquals(1832, ((Map<String, Object>) c.get("frontmatter")).get("view_count"));
        assertNull(env.engine.getConcept(RUNBOOK, NOBODY));
    }

    @Test
    @SuppressWarnings("unchecked")
    void browseProgressiveDisclosure() {
        var root = env.engine.browse("", NAV);
        List<String> subs = (List<String>) root.get("subdirectories");
        assertTrue(subs.containsAll(List.of("jira", "confluence")));
        assertFalse(subs.contains("legal"));
        var conf = env.engine.browse("confluence", NAV);
        assertTrue(((List<String>) conf.get("subdirectories")).contains("confluence/spaces"));
        assertTrue(((String) conf.get("index")).contains("# Confluence"));
        var nav = env.engine.browse("confluence/spaces/NAV", NAV);
        assertTrue(((String) nav.get("index")).contains("NAV operations space"));
        assertTrue(((List<Map<String, Object>>) nav.get("concepts")).stream().anyMatch(c -> c.get("concept_id").equals(RUNBOOK)));
        assertNull(env.engine.browse("confluence/spaces/NAV", NOBODY).get("index"));
    }

    @Test
    void relatedResolvesPathsAndKeys() {
        var rel = env.engine.related("jira/projects/FUND/FUND-1423", NAV);
        Set<String> out = new HashSet<>();
        rel.forEach(r -> out.add(r.get("concept_id") + "|" + r.get("direction")));
        assertTrue(out.contains(RUNBOOK + "|outgoing"));
        assertTrue(out.contains("jira/projects/FUND/FUND-1388|outgoing")); // via the FUND-1388 key mention
        assertTrue(out.contains("confluence/spaces/NAV/late-trades-decision|incoming")); // decision mentions FUND-1423
        assertTrue(env.engine.related("jira/projects/FUND/FUND-1423", NOBODY).isEmpty());
    }

    @Test
    void recentChanges() {
        var rows = env.engine.recentChanges(NAV, "confluence", null, null, 50);
        assertEquals("2026-10-20", ((LocalDateTime) rows.get(0).get("date")).toLocalDate().toString());
        assertEquals(List.of(RUNBOOK), rows.get(0).get("concept_ids"));
        assertTrue(env.engine.recentChanges(NOBODY, null, null, null, 50).isEmpty());
    }

    @Test
    void recentChangesHideEntriesAboutInvisibleConcepts() {
        env.write("jira/log.md", env.read("jira/log.md") + "\n## 2026-10-22\n- Updated [SEC-77](projects/SEC/SEC-77.md): dormant admins\n");
        env.ix.reconcile();
        var texts = env.engine.recentChanges(NAV, "jira", null, null, 50).stream().map(r -> (String) r.get("text")).toList();
        assertTrue(texts.stream().anyMatch(t -> t.contains("FUND-1450")));
        assertTrue(texts.stream().noneMatch(t -> t.contains("SEC-77")));
        var security = Identity.of("s", Set.of("security-team", "fund-ops"));
        assertTrue(env.engine.recentChanges(security, "jira", null, null, 50).stream()
                .anyMatch(r -> ((String) r.get("text")).contains("SEC-77")));
    }

    @Test
    void highlightEscapesAndMarks() {
        String out = SearchEngine.highlight("<b>Late</b> trades are late", List.of("late"), 280);
        assertTrue(out.contains("&lt;b&gt;<mark>Late</mark>&lt;/b&gt;"));
        assertEquals(2, out.split("<mark>", -1).length - 1);
    }
}
