package io.knowledgelake.okf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class OkfTest {
    static final String DOC = """
            ---
            type: Runbook
            title: Cut-off runbook
            tags: [nav, ops]
            acl: [nav-ops]
            exported_at: 2026-10-22T01:00:00Z
            source_id: "884211"
            custom_field: 7
            ---
            # Runbook

            Intro paragraph.

            ## Cut-off

            See [FX](../fx.md) and [the decision](decision.md#context) and [external](https://x.example/a.md).
            Ticket FUND-1423 and FUND-1423 again.

            | a | b |
            |---|---|
            | 1 | 2 |

            ### Late trades

            Late trade text.
            """;

    static Okf.Concept parse(String raw) {
        return Okf.parseConcept("confluence/spaces/NAV/runbook.md", raw,
                new Okf.ParseOptions(Set.of("exported_at"), 400, 0.15, List.of("\\b[A-Z][A-Z0-9]{1,9}-\\d+\\b")));
    }

    @Test
    void frontmatterMapsToColumns() {
        var c = parse(DOC);
        assertEquals("confluence/spaces/NAV/runbook", c.conceptId());
        assertEquals("confluence", c.bundle());
        assertEquals("confluence/spaces/NAV", c.dir());
        assertEquals("NAV", c.scope());
        assertEquals("Runbook", c.type());
        assertEquals(List.of("nav", "ops"), c.tags());
        assertEquals(List.of("nav-ops"), c.acl());
        assertEquals("884211", c.sourceId());
        assertEquals(Set.of("exported_at", "custom_field"), c.extra().keySet());
        assertEquals(7, c.extra().get("custom_field"));
    }

    @Test
    void missingTypeIsRejected() {
        assertThrows(Okf.OkfException.class, () -> parse("---\ntitle: x\n---\nbody"));
    }

    @Test
    void hashIgnoresVolatileFieldsWhitespaceAndAcl() {
        String base = parse(DOC).contentHash();
        assertEquals(base, parse(DOC.replace("2026-10-22T01:00:00Z", "2026-10-23T01:00:00Z")).contentHash());
        assertEquals(base, parse(DOC.replace("Intro paragraph.", "Intro paragraph.   \n\n\n")).contentHash());
        var changedAcl = parse(DOC.replace("acl: [nav-ops]", "acl: [nav-ops, auditors]"));
        assertEquals(base, changedAcl.contentHash());
        assertNotEquals(parse(DOC).aclHash(), changedAcl.aclHash());
        assertNotEquals(base, parse(DOC.replace("Late trade text.", "Different.")).contentHash());
    }

    @Test
    void chunksCarryHeadingPathsAndTablesStayWhole() {
        var chunks = parse(DOC).chunks();
        var paths = chunks.stream().map(Okf.Chunk::headingPath).toList();
        assertTrue(paths.contains("Runbook > Cut-off"));
        assertTrue(paths.contains("Runbook > Cut-off > Late trades"));
        assertTrue(chunks.stream().anyMatch(ch -> ch.headingPath().equals("Runbook > Cut-off")
                && ch.text().contains("| 1 | 2 |") && ch.text().contains("| a | b |")));
    }

    @Test
    void longSectionsSplitWithOverlap() {
        StringBuilder words = new StringBuilder();
        for (int i = 0; i < 1000; i++) words.append("w").append(i).append(' ');
        String body = "---\ntype: Page\ntitle: T\n---\n## S\n\n" + words;
        var chunks = Okf.parseConcept("b/x.md", body, new Okf.ParseOptions(Set.of(), 200, 0.15, List.of())).chunks();
        assertTrue(chunks.size() >= 5);
        assertTrue(chunks.stream().allMatch(ch -> Okf.countTokens(ch.text()) <= 240));
        String[] first = chunks.get(0).text().split("\\s+");
        assertTrue(List.of(chunks.get(1).text().split("\\s+")).contains(first[first.length - 1]));
        assertEquals("T > S", chunks.get(0).headingPath());
    }

    @Test
    void linksResolveRelativePathsAndKeys() {
        var links = parse(DOC).links();
        Set<String> md = links.stream().filter(l -> l.kind().equals("md")).map(Okf.Link::toConcept).collect(Collectors.toSet());
        assertEquals(Set.of("confluence/spaces/fx", "confluence/spaces/NAV/decision"), md);
        assertEquals(List.of("FUND-1423"), links.stream().filter(l -> l.kind().equals("key")).map(Okf.Link::toRef).toList());
    }

    @Test
    void parseLogBothShapes() {
        String raw = "# Log\n\n## 2026-10-21\n- Updated [A](a/x.md)\n\n- 2026-10-20: Created [B](b/y.md)\n";
        var entries = Okf.parseLog("jira/log.md", raw);
        assertEquals("2026-10-21", entries.get(0).date().toLocalDate().toString());
        assertEquals("2026-10-20", entries.get(1).date().toLocalDate().toString());
        assertEquals(List.of("jira/a/x"), entries.get(0).conceptIds());
        assertTrue(entries.get(1).text().startsWith("Created"));
    }

    @Test
    void directoryLinksAreNavigationNotEdges() {
        assertEquals(null, Links.resolveRelative("jira", "projects/FUND/"));
        assertEquals(null, Links.resolveRelative("jira", "../../../escape.md"));
        assertEquals("jira/projects/FUND/FUND-1", Links.resolveRelative("jira/projects/SEC", "../FUND/FUND-1.md"));
        assertEquals(Map.of(), Map.of());
    }
}
