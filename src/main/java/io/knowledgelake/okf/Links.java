package io.knowledgelake.okf;

import io.knowledgelake.okf.Okf.Link;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Markdown link and native-key extraction; every relative link becomes a concept edge. */
public final class Links {
    static final Pattern LINK =
            Pattern.compile("(?<!!)\\[([^\\]]*)\\]\\(\\s*<?([^)\\s>]+)>?(?:\\s+\"[^\"]*\")?\\s*\\)");
    private static final Pattern CONFLUENCE_ID = Pattern.compile("pageId=(\\d+)");
    private static final Pattern SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:");

    private Links() {}

    /** Resolve a relative markdown link to a concept id, or null for external/anchor/index links. */
    public static String resolveRelative(String dir, String target) {
        int cut = indexOfAny(target, '#', '?');
        if (cut >= 0) target = target.substring(0, cut);
        if (target.isEmpty() || SCHEME.matcher(target).find() || target.startsWith("//")) return null;
        String joined;
        if (target.startsWith("/")) { // bundle-root-relative
            String bundle = dir.contains("/") ? dir.substring(0, dir.indexOf('/')) : dir;
            joined = normalize(bundle + "/" + target.substring(1));
        } else {
            joined = normalize(dir.isEmpty() ? target : dir + "/" + target);
        }
        if (joined == null) return null;
        if (target.endsWith("/") || Okf.basename(joined).equals("index.md")) return null; // navigation
        return Okf.conceptIdFor(joined.endsWith(".md") ? joined : joined + ".md");
    }

    private static int indexOfAny(String s, char a, char b) {
        int i = s.indexOf(a), j = s.indexOf(b);
        if (i < 0) return j;
        if (j < 0) return i;
        return Math.min(i, j);
    }

    /** posixpath.normpath for relative paths; null when the path escapes the root. */
    static String normalize(String path) {
        Deque<String> parts = new ArrayDeque<>();
        for (String seg : path.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (parts.isEmpty()) return null;
                parts.removeLast();
            } else {
                parts.addLast(seg);
            }
        }
        return parts.isEmpty() ? null : String.join("/", parts);
    }

    public static List<Link> extract(String body, String dir, String selfId, List<String> identifierPatterns) {
        List<Link> links = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher m = LINK.matcher(body);
        while (m.find()) {
            String anchor = m.group(1).strip(), target = m.group(2);
            String cid = resolveRelative(dir, target);
            if (cid != null) {
                add(links, seen, selfId, new Link(cid, null, anchor, "md"));
            } else {
                Matcher pid = CONFLUENCE_ID.matcher(target);
                if (pid.find()) add(links, seen, selfId, new Link(null, pid.group(1), anchor, "confluence_id"));
            }
        }
        for (String pattern : identifierPatterns) {
            Matcher km = Pattern.compile(pattern).matcher(body);
            while (km.find()) add(links, seen, selfId, new Link(null, km.group(), km.group(), "key"));
        }
        return links;
    }

    private static void add(List<Link> links, Set<String> seen, String selfId, Link link) {
        String key = link.toConcept() + "\u0000" + link.toRef();
        if (selfId.equals(link.toConcept()) || !seen.add(key)) return;
        links.add(link);
    }
}
