package io.knowledgelake.okf;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OKF v0.2 bundle parsing: frontmatter, canonical hashing, chunking, links, reserved files.
 *
 * <p>Pure functions only: no I/O, no models. Everything the indexer derives from a markdown file
 * is computed here so it can be unit-tested in isolation.
 */
public final class Okf {
    public static final Set<String> RESERVED = Set.of("index.md", "log.md");
    static final Set<String> KNOWN_FIELDS =
            Set.of("type", "title", "description", "tags", "resource", "updated", "acl", "source_id");
    static final Set<String> SCOPE_DIRS = Set.of("spaces", "projects", "sites", "repos", "teams");

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper CANONICAL =
            new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private static final Pattern TOKEN = Pattern.compile("\\w+|[^\\w\\s]", Pattern.UNICODE_CHARACTER_CLASS);
    static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~)");

    private Okf() {}

    /** A file that is not a valid OKF concept (e.g. missing the required {@code type}). */
    public static class OkfException extends RuntimeException {
        public OkfException(String message) {
            super(message);
        }
    }

    public record Frontmatter(Map<String, Object> meta, String body) {}

    public record Chunk(int ord, String headingPath, String text) {
        public String embedText() {
            return headingPath.isEmpty() ? text : headingPath + "\n" + text;
        }

        public String hash() {
            return sha256(embedText());
        }
    }

    public record Link(String toConcept, String toRef, String anchorText, String kind) {}

    public record Concept(
            String conceptId,
            String path,
            String bundle,
            String dir,
            String scope,
            String type,
            String title,
            String description,
            List<String> tags,
            String resourceUrl,
            String sourceId,
            LocalDateTime updated,
            List<String> acl, // null = not supplied -> bundle default applies
            Map<String, Object> extra,
            Map<String, Object> frontmatter,
            String body,
            String contentHash,
            String aclHash,
            List<Chunk> chunks,
            List<Link> links) {

        public String cardText() {
            List<String> parts = new ArrayList<>();
            if (!title.isEmpty()) parts.add(title);
            if (!description.isEmpty()) parts.add(description);
            if (!tags.isEmpty()) parts.add("Tags: " + String.join(", ", tags));
            return String.join("\n", parts);
        }

        public String cardHash() {
            return sha256(cardText());
        }
    }

    // ------------------------------------------------------------------ utilities

    public static int countTokens(String text) {
        Matcher m = TOKEN.matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String conceptIdFor(String path) {
        return path.endsWith(".md") ? path.substring(0, path.length() - 3) : path;
    }

    public static String dirname(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }

    public static String basename(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    // ------------------------------------------------------------------ frontmatter

    public static Frontmatter splitFrontmatter(String raw) {
        if (raw.startsWith("﻿")) raw = raw.substring(1);
        String[] lines = raw.split("\n", -1);
        if (lines.length == 0 || !lines[0].strip().equals("---")) return new Frontmatter(new LinkedHashMap<>(), raw);
        for (int i = 1; i < lines.length; i++) {
            String l = lines[i].strip();
            if (l.equals("---") || l.equals("...")) {
                String yaml = String.join("\n", java.util.Arrays.copyOfRange(lines, 1, i));
                Map<String, Object> meta;
                try {
                    Object parsed = yaml.isBlank() ? null : YAML.readValue(yaml, Object.class);
                    if (parsed == null) meta = new LinkedHashMap<>();
                    else if (parsed instanceof Map<?, ?>) meta = YAML.convertValue(parsed, new TypeReference<>() {});
                    else throw new OkfException("frontmatter is not a mapping");
                } catch (OkfException e) {
                    throw e;
                } catch (Exception e) {
                    throw new OkfException("invalid frontmatter: " + e.getMessage());
                }
                String body = String.join("\n", java.util.Arrays.copyOfRange(lines, i + 1, lines.length));
                return new Frontmatter(meta, body);
            }
        }
        throw new OkfException("unterminated frontmatter");
    }

    /** ISO date or date-time to naive UTC; null when absent or unparseable. */
    public static LocalDateTime parseTimestamp(Object value) {
        if (value == null) return null;
        String s = value.toString().strip();
        if (s.isEmpty()) return null;
        try {
            return OffsetDateTime.parse(s.replace(" ", "T")).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(s.replace(" ", "T"));
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s).atStartOfDay();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public static List<String> strList(Object value) {
        List<String> out = new ArrayList<>();
        if (value == null) return out;
        if (value instanceof List<?> l) {
            for (Object o : l) if (o != null) out.add(o.toString());
        } else {
            for (String s : value.toString().split(",")) if (!s.isBlank()) out.add(s.strip());
        }
        return out;
    }

    // ------------------------------------------------------------------ concepts

    /** Stable text for hashing: volatile fields and ACLs dropped, whitespace normalized. */
    public static String canonicalize(Map<String, Object> meta, String body, Set<String> volatileFields) {
        Map<String, Object> kept = new TreeMap<>();
        meta.forEach((k, v) -> {
            if (!volatileFields.contains(k) && !k.equals("acl")) kept.put(k, v);
        });
        StringBuilder sb = new StringBuilder();
        for (String line : body.replace("\r\n", "\n").split("\n", -1)) sb.append(line.stripTrailing()).append('\n');
        String text = sb.toString().replaceAll("\n{3,}", "\n\n").strip();
        try {
            return CANONICAL.writeValueAsString(kept) + "\n\n" + text;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String aclHash(List<String> acl) {
        if (acl == null) return sha256("null");
        List<String> sorted = new ArrayList<>(acl);
        sorted.sort(null);
        return sha256(String.join("\u0000", sorted) + "\u0001" + sorted.size());
    }

    public record ParseOptions(Set<String> volatileFields, int maxTokens, double overlap, List<String> identifierPatterns) {
        public static ParseOptions defaults() {
            return new ParseOptions(Set.of(), 400, 0.15, List.of());
        }
    }

    public static Concept parseConcept(String path, String raw, ParseOptions opts) {
        Frontmatter fm = splitFrontmatter(raw);
        Map<String, Object> meta = fm.meta();
        Object ctype = meta.get("type");
        if (ctype == null || ctype.toString().isBlank()) {
            throw new OkfException(path + ": missing required frontmatter field `type`");
        }
        String conceptId = conceptIdFor(path);
        String bundle = path.contains("/") ? path.substring(0, path.indexOf('/')) : path;
        String dir = dirname(path);
        String title = meta.get("title") != null ? meta.get("title").toString() : null;
        if (title == null || title.isBlank()) {
            String h1 = firstHeading(fm.body());
            title = h1 != null ? h1 : basename(conceptId);
        }
        List<String> acl = meta.containsKey("acl") ? strList(meta.get("acl")) : null;
        Map<String, Object> extra = new LinkedHashMap<>();
        meta.forEach((k, v) -> {
            if (!KNOWN_FIELDS.contains(k)) extra.put(k, v);
        });
        List<Chunk> chunks = Chunker.chunk(fm.body(), title, opts.maxTokens(), opts.overlap());
        List<Link> links = Links.extract(fm.body(), dir, conceptId, opts.identifierPatterns());
        return new Concept(
                conceptId,
                path,
                bundle,
                dir,
                scope(dir, meta),
                ctype.toString(),
                title,
                str(meta.get("description")),
                strList(meta.get("tags")),
                meta.get("resource") == null ? null : meta.get("resource").toString(),
                meta.get("source_id") == null ? null : meta.get("source_id").toString(),
                parseTimestamp(meta.get("updated")),
                acl,
                extra,
                meta,
                fm.body(),
                sha256(canonicalize(meta, fm.body(), opts.volatileFields())),
                aclHash(acl),
                chunks,
                links);
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    static String scope(String dir, Map<String, Object> meta) {
        for (String key : List.of("space", "project", "scope")) {
            Object v = meta.get(key);
            if (v != null && !v.toString().isBlank()) return v.toString();
        }
        String[] parts = dir.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if (SCOPE_DIRS.contains(parts[i])) return parts[i + 1];
        }
        return null;
    }

    static String firstHeading(String body) {
        boolean inFence = false;
        for (String line : body.split("\n", -1)) {
            if (FENCE.matcher(line).find()) {
                inFence = !inFence;
            } else if (!inFence) {
                Matcher m = HEADING.matcher(line);
                if (m.matches() && m.group(1).length() == 1) return m.group(2);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ reserved files

    public record ChangeEntry(LocalDateTime date, String text, List<String> conceptIds) {}

    private static final Pattern DATE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");

    /**
     * Parse a bundle's log.md into dated entries. Accepts date headings with bullets underneath,
     * and bullets that start with a date ("- 2026-10-20: Updated [cut-off](spaces/NAV/x.md)").
     */
    public static List<ChangeEntry> parseLog(String path, String raw) {
        String body = splitFrontmatter(raw).body();
        String dir = dirname(path);
        List<ChangeEntry> entries = new ArrayList<>();
        LocalDateTime current = null;
        for (String line : body.split("\n", -1)) {
            String s = line.strip();
            if (s.isEmpty()) continue;
            Matcher h = HEADING.matcher(s);
            if (h.matches()) {
                Matcher d = DATE.matcher(h.group(2));
                if (d.find()) current = parseTimestamp(d.group(1));
                continue;
            }
            if (!(s.startsWith("-") || s.startsWith("*") || s.startsWith("+"))) continue;
            String text = s.replaceFirst("^[-*+ ]+", "").strip();
            LocalDateTime date = current;
            Matcher d = DATE.matcher(text);
            if (d.lookingAt()) {
                date = parseTimestamp(d.group(1));
                text = text.substring(d.end()).replaceFirst("^[ :—-]+", "").strip();
            }
            List<String> ids = new ArrayList<>();
            Matcher lm = Links.LINK.matcher(text);
            while (lm.find()) {
                String cid = Links.resolveRelative(dir, lm.group(2));
                if (cid != null) ids.add(cid);
            }
            entries.add(new ChangeEntry(date, text, ids));
        }
        return entries;
    }
}
