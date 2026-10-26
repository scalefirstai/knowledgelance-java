package io.knowledgelake.search;

import io.knowledgelake.config.Settings;
import io.knowledgelake.store.Partition;
import io.knowledgelake.store.Schema;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;

/**
 * Query parsing (inline filters, exact identifiers) and filter to Lucene predicates.
 *
 * <p>{@code "late trades type:Runbook space:NAV after:2026-09-01 FUND-1423"} parses to text
 * {@code "late trades FUND-1423"}, filters {@code {type: [Runbook], scope: [NAV], after:
 * 2026-09-01}} and identifiers {@code [FUND-1423]} (pinned when an exact {@code source_id} match
 * exists).
 */
public final class QueryParsing {
    private static final Map<String, String> FILTER_KEYS = Map.ofEntries(
            Map.entry("type", "type"), Map.entry("types", "type"),
            Map.entry("bundle", "bundle"), Map.entry("source", "bundle"),
            Map.entry("space", "scope"), Map.entry("project", "scope"), Map.entry("scope", "scope"),
            Map.entry("tag", "tags"), Map.entry("tags", "tags"),
            Map.entry("dir", "dir"), Map.entry("path", "dir"),
            Map.entry("after", "after"), Map.entry("since", "after"),
            Map.entry("before", "before"), Map.entry("until", "before"));

    private QueryParsing() {}

    public record ParsedQuery(String text, Filters filters, List<String> identifiers) {}

    /** POSIX-shell-like split: quotes group words and are removed. */
    static List<String> shellSplit(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean inToken = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else cur.append(c);
            } else if (c == '"' || c == '\'') {
                quote = c;
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken) out.add(cur.toString());
                cur.setLength(0);
                inToken = false;
            } else {
                cur.append(c);
                inToken = true;
            }
        }
        if (quote != 0) return new ArrayList<>(List.of(s.trim().split("\\s+"))); // unbalanced: plain split
        if (inToken) out.add(cur.toString());
        return out;
    }

    public static ParsedQuery parse(String q, List<String> identifierPatterns) {
        Map<String, List<String>> raw = new LinkedHashMap<>();
        List<String> text = new ArrayList<>();
        for (String tok : shellSplit(q)) {
            int colon = tok.indexOf(':');
            String key = colon > 0 ? tok.substring(0, colon).toLowerCase(Locale.ROOT) : null;
            String value = colon > 0 ? tok.substring(colon + 1) : "";
            if (key != null && !value.isEmpty() && FILTER_KEYS.containsKey(key)) {
                List<String> vals = raw.computeIfAbsent(FILTER_KEYS.get(key), k -> new ArrayList<>());
                for (String v : value.split(",")) if (!v.isEmpty()) vals.add(v);
            } else {
                text.add(tok);
            }
        }
        String t = String.join(" ", text);
        Filters f = new Filters(raw.get("type"), raw.get("bundle"), raw.get("scope"), raw.get("tags"),
                last(raw.get("dir")), last(raw.get("after")), last(raw.get("before")));
        List<String> ids = new ArrayList<>();
        for (String p : identifierPatterns) {
            Matcher m = Pattern.compile(p).matcher(t);
            while (m.find()) if (!ids.contains(m.group())) ids.add(m.group());
        }
        return new ParsedQuery(t, f, ids);
    }

    private static String last(List<String> l) {
        return l == null || l.isEmpty() ? null : l.get(l.size() - 1);
    }

    // ------------------------------------------------------------------ predicates

    /** The ACL pre-filter. Applied inside every retriever, never after ranking. */
    public static Query acl(Set<String> groups) {
        Set<String> g = new HashSet<>(groups);
        g.add(Settings.PUBLIC);
        return Partition.termsIn("acl", g);
    }

    public static Query dirUnder(String dir, String field) {
        String d = dir.replaceAll("/+$", "");
        return new BooleanQuery.Builder()
                .add(new TermQuery(new Term(field, d)), BooleanClause.Occur.SHOULD)
                .add(new PrefixQuery(new Term(field, d + "/")), BooleanClause.Occur.SHOULD)
                .setMinimumNumberShouldMatch(1)
                .build();
    }

    public static Query range(String field, LocalDateTime from, LocalDateTime toExclusive) {
        long lo = from == null ? Long.MIN_VALUE : Schema.millis(from);
        long hi = toExclusive == null ? Long.MAX_VALUE : Schema.millis(toExclusive) - 1;
        return LongPoint.newRangeQuery(field, lo, hi);
    }

    /** ACL + metadata predicate for concepts and chunks (both carry the same filter columns). */
    public static Query filter(Filters f, Set<String> groups) {
        BooleanQuery.Builder b = new BooleanQuery.Builder().add(acl(groups), BooleanClause.Occur.FILTER);
        if (f.type != null) b.add(Partition.termsIn("type", f.type), BooleanClause.Occur.FILTER);
        if (f.bundle != null) b.add(Partition.termsIn("bundle", f.bundle), BooleanClause.Occur.FILTER);
        if (f.scope != null) b.add(Partition.termsIn("scope", f.scope), BooleanClause.Occur.FILTER);
        if (f.tags != null) b.add(Partition.termsIn("tags", f.tags), BooleanClause.Occur.FILTER);
        if (f.dir != null) b.add(dirUnder(f.dir, "dir"), BooleanClause.Occur.FILTER);
        if (f.afterTs() != null || f.beforeTs() != null) {
            b.add(range("updated", f.afterTs(), f.beforeTs()), BooleanClause.Occur.FILTER);
        }
        return b.build();
    }

    public static Query and(Query... clauses) {
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        for (Query q : clauses) b.add(q, BooleanClause.Occur.FILTER);
        return b.build();
    }
}
