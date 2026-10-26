package io.knowledgelake.models.tokenizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * A pure-Java subset of Hugging Face {@code tokenizers}, driven by {@code tokenizer.json}.
 *
 * <p>Supports the two pipelines the default models use, with no native code:
 *
 * <ul>
 *   <li>BERT: BertNormalizer, BertPreTokenizer, WordPiece (bge-small, MiniLM cross-encoders);
 *   <li>SentencePiece: Precompiled + Replace normalizers, Metaspace, Unigram (bge-m3,
 *       bge-reranker-v2-m3).
 * </ul>
 *
 * Post-processing is TemplateProcessing with longest-first truncation for pairs. Any other
 * component fails at load time rather than tokenizing silently wrong. Ids are verified against
 * the Rust implementation in the test suite.
 */
public final class HfTokenizer {
    public record Encoding(long[] ids, long[] typeIds) {
        public int length() {
            return ids.length;
        }
    }

    private record TemplateItem(String specialId, String sequence, int typeId) {}

    private final UnaryOperator<String> normalizer;
    private final PreTokenizer preTokenizer;
    private final Model model;
    private final List<TemplateItem> single;
    private final List<TemplateItem> pair;
    private final Map<String, Long> specialIds = new HashMap<>();
    private final int maxLength;
    public final long padId;

    private interface PreTokenizer {
        List<String> split(String text);
    }

    private interface Model {
        void tokenize(String word, List<Long> out);
    }

    public static HfTokenizer load(Path tokenizerJson, int maxLength) throws IOException {
        return new HfTokenizer(new ObjectMapper().readTree(Files.readString(tokenizerJson)), maxLength);
    }

    HfTokenizer(JsonNode root, int maxLength) {
        this.maxLength = maxLength;
        this.normalizer = normalizer(root.get("normalizer"));
        this.preTokenizer = preTokenizer(root.get("pre_tokenizer"));
        this.model = model(root.get("model"));
        for (JsonNode t : root.path("added_tokens")) specialIds.put(t.get("content").asText(), t.get("id").asLong());
        JsonNode post = root.get("post_processor");
        if (post == null || post.isNull() || !"TemplateProcessing".equals(post.path("type").asText())) {
            throw new IllegalArgumentException("unsupported post_processor: " + post);
        }
        post.path("special_tokens").forEach(st -> specialIds.put(st.get("id").asText(), st.get("ids").get(0).asLong()));
        this.single = template(post.get("single"));
        this.pair = template(post.get("pair"));
        Long pad = specialIds.get("[PAD]");
        if (pad == null) pad = specialIds.get("<pad>");
        this.padId = pad == null ? 0 : pad;
    }

    private static List<TemplateItem> template(JsonNode items) {
        List<TemplateItem> out = new ArrayList<>();
        for (JsonNode it : items) {
            if (it.has("SpecialToken")) {
                JsonNode s = it.get("SpecialToken");
                out.add(new TemplateItem(s.get("id").asText(), null, s.path("type_id").asInt()));
            } else {
                JsonNode s = it.get("Sequence");
                out.add(new TemplateItem(null, s.get("id").asText(), s.path("type_id").asInt()));
            }
        }
        return out;
    }

    // ================================================================== encode

    private List<Long> tokens(String text) {
        List<Long> ids = new ArrayList<>();
        for (String word : preTokenizer.split(normalizer.apply(text))) model.tokenize(word, ids);
        return ids;
    }

    public Encoding encode(String text) {
        List<Long> a = tokens(text);
        int budget = maxLength - specials(single);
        if (a.size() > budget) a = a.subList(0, Math.max(0, budget));
        return assemble(single, a, List.of());
    }

    /** Pair encoding (query, passage) with longest-first truncation, as cross-encoders expect. */
    public Encoding encodePair(String first, String second) {
        List<Long> a = tokens(first);
        List<Long> b = tokens(second);
        int max = maxLength - specials(pair);
        int n1 = a.size(), n2 = b.size();
        if (n1 + n2 > max) { // tokenizers' LongestFirst, verbatim
            boolean swap = n1 > n2;
            if (swap) {
                int t = n1;
                n1 = n2;
                n2 = t;
            }
            n2 = n1 > max ? n1 : Math.max(n1, max - n1);
            if (n1 + n2 > max) {
                n1 = max / 2;
                n2 = n1 + max % 2;
            }
            if (swap) {
                int t = n1;
                n1 = n2;
                n2 = t;
            }
            a = a.subList(0, Math.min(n1, a.size()));
            b = b.subList(0, Math.min(n2, b.size()));
        }
        return assemble(pair, a, b);
    }

    private static int specials(List<TemplateItem> t) {
        return (int) t.stream().filter(i -> i.specialId() != null).count();
    }

    private Encoding assemble(List<TemplateItem> template, List<Long> a, List<Long> b) {
        List<Long> ids = new ArrayList<>();
        List<Long> types = new ArrayList<>();
        for (TemplateItem item : template) {
            List<Long> seq;
            if (item.specialId() != null) {
                Long id = specialIds.get(item.specialId());
                if (id == null) throw new IllegalStateException("unknown special token " + item.specialId());
                seq = List.of(id);
            } else {
                seq = "A".equals(item.sequence()) ? a : b;
            }
            for (Long id : seq) {
                ids.add(id);
                types.add((long) item.typeId());
            }
        }
        return new Encoding(ids.stream().mapToLong(Long::longValue).toArray(),
                types.stream().mapToLong(Long::longValue).toArray());
    }

    // ================================================================== normalizers

    private static UnaryOperator<String> normalizer(JsonNode n) {
        if (n == null || n.isNull()) return s -> s;
        String type = n.path("type").asText();
        switch (type) {
            case "Sequence": {
                List<UnaryOperator<String>> parts = new ArrayList<>();
                for (JsonNode c : n.get("normalizers")) parts.add(normalizer(c));
                return s -> {
                    for (UnaryOperator<String> p : parts) s = p.apply(s);
                    return s;
                };
            }
            case "BertNormalizer": {
                boolean clean = n.path("clean_text").asBoolean(true);
                boolean chinese = n.path("handle_chinese_chars").asBoolean(true);
                boolean lower = n.path("lowercase").asBoolean(true);
                boolean strip = n.path("strip_accents").isNull() || n.path("strip_accents").isMissingNode()
                        ? lower
                        : n.path("strip_accents").asBoolean();
                return s -> bertNormalize(s, clean, chinese, strip, lower);
            }
            case "Precompiled": {
                PrecompiledCharsmap map = new PrecompiledCharsmap(n.get("precompiled_charsmap").asText());
                return map::normalize;
            }
            case "Replace": {
                JsonNode p = n.get("pattern");
                String content = n.get("content").asText();
                if (p.has("Regex")) {
                    Pattern re = Pattern.compile(p.get("Regex").asText());
                    return s -> re.matcher(s).replaceAll(java.util.regex.Matcher.quoteReplacement(content));
                }
                String lit = p.get("String").asText();
                return s -> s.replace(lit, content);
            }
            case "Lowercase":
                return s -> s.toLowerCase(Locale.ROOT);
            case "NFC":
                return s -> Normalizer.normalize(s, Normalizer.Form.NFC);
            case "NFD":
                return s -> Normalizer.normalize(s, Normalizer.Form.NFD);
            case "NFKC":
                return s -> Normalizer.normalize(s, Normalizer.Form.NFKC);
            case "NFKD":
                return s -> Normalizer.normalize(s, Normalizer.Form.NFKD);
            case "StripAccents":
                return HfTokenizer::stripAccents;
            case "Strip":
                return String::strip;
            default:
                throw new IllegalArgumentException("unsupported normalizer: " + type);
        }
    }

    private static String stripAccents(String s) {
        String d = Normalizer.normalize(s, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(d.length());
        d.codePoints().filter(cp -> Character.getType(cp) != Character.NON_SPACING_MARK).forEach(sb::appendCodePoint);
        return sb.toString();
    }

    private static boolean isControl(int cp) {
        if (cp == '\t' || cp == '\n' || cp == '\r') return false;
        int t = Character.getType(cp);
        return t == Character.CONTROL || t == Character.FORMAT || t == Character.PRIVATE_USE || t == Character.SURROGATE
                || t == Character.UNASSIGNED;
    }

    private static boolean isWhitespace(int cp) {
        return cp == ' ' || cp == '\t' || cp == '\n' || cp == '\r' || Character.getType(cp) == Character.SPACE_SEPARATOR;
    }

    private static boolean isChinese(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF) || (cp >= 0x20000 && cp <= 0x2A6DF)
                || (cp >= 0x2A700 && cp <= 0x2B73F) || (cp >= 0x2B740 && cp <= 0x2B81F)
                || (cp >= 0x2B820 && cp <= 0x2CEAF) || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0x2F800 && cp <= 0x2FA1F);
    }

    private static String bertNormalize(String s, boolean clean, boolean chinese, boolean strip, boolean lower) {
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            if (clean) {
                if (cp == 0 || cp == 0xFFFD || isControl(cp)) return;
                if (isWhitespace(cp)) {
                    sb.append(' ');
                    return;
                }
            }
            if (chinese && isChinese(cp)) {
                sb.append(' ').appendCodePoint(cp).append(' ');
                return;
            }
            sb.appendCodePoint(cp);
        });
        String out = sb.toString();
        if (strip) out = stripAccents(out);
        if (lower) out = out.toLowerCase(Locale.ROOT);
        return out;
    }

    // ================================================================== pre-tokenizers

    private static boolean isPunctuation(int cp) {
        if ((cp >= 33 && cp <= 47) || (cp >= 58 && cp <= 64) || (cp >= 91 && cp <= 96) || (cp >= 123 && cp <= 126)) {
            return true;
        }
        int t = Character.getType(cp);
        return t == Character.CONNECTOR_PUNCTUATION || t == Character.DASH_PUNCTUATION || t == Character.START_PUNCTUATION
                || t == Character.END_PUNCTUATION || t == Character.INITIAL_QUOTE_PUNCTUATION
                || t == Character.FINAL_QUOTE_PUNCTUATION || t == Character.OTHER_PUNCTUATION;
    }

    private static PreTokenizer preTokenizer(JsonNode n) {
        if (n == null || n.isNull()) return s -> List.of(s);
        String type = n.path("type").asText();
        switch (type) {
            case "BertPreTokenizer":
                return HfTokenizer::bertSplit;
            case "WhitespaceSplit":
                return s -> {
                    List<String> out = new ArrayList<>();
                    for (String w : s.strip().split("\\s+")) if (!w.isEmpty()) out.add(w);
                    return out;
                };
            case "Metaspace": {
                String repl = n.path("replacement").asText("▁");
                String scheme = n.has("prepend_scheme") ? n.get("prepend_scheme").asText()
                        : (n.path("add_prefix_space").asBoolean(true) ? "always" : "never");
                return s -> metaspace(s, repl, scheme);
            }
            case "Sequence": {
                List<PreTokenizer> parts = new ArrayList<>();
                for (JsonNode c : n.get("pretokenizers")) parts.add(preTokenizer(c));
                return s -> {
                    List<String> cur = List.of(s);
                    for (PreTokenizer p : parts) {
                        List<String> next = new ArrayList<>();
                        for (String piece : cur) next.addAll(p.split(piece));
                        cur = next;
                    }
                    return cur;
                };
            }
            default:
                throw new IllegalArgumentException("unsupported pre_tokenizer: " + type);
        }
    }

    private static List<String> bertSplit(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        s.codePoints().forEach(cp -> {
            if (isWhitespace(cp)) {
                if (!cur.isEmpty()) out.add(cur.toString());
                cur.setLength(0);
            } else if (isPunctuation(cp)) {
                if (!cur.isEmpty()) out.add(cur.toString());
                cur.setLength(0);
                out.add(new String(Character.toChars(cp)));
            } else {
                cur.appendCodePoint(cp);
            }
        });
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }

    /** Replace spaces with ▁, optionally prepend one, split so every piece starts with ▁. */
    private static List<String> metaspace(String s, String repl, String scheme) {
        if (s.isEmpty()) return List.of();
        String r = s.replace(" ", repl);
        if (!"never".equals(scheme) && !r.startsWith(repl)) r = repl + r;
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 1; i <= r.length(); i++) {
            if (i == r.length() || r.startsWith(repl, i)) {
                if (i > start) out.add(r.substring(start, i));
                start = i;
            }
        }
        return out;
    }

    // ================================================================== models

    private static Model model(JsonNode n) {
        String type = n.path("type").asText();
        return switch (type) {
            case "WordPiece" -> wordPiece(n);
            case "Unigram" -> unigram(n);
            default -> throw new IllegalArgumentException("unsupported tokenizer model: " + type);
        };
    }

    private static Model wordPiece(JsonNode n) {
        Map<String, Long> vocab = new HashMap<>();
        n.get("vocab").properties().forEach(e -> vocab.put(e.getKey(), e.getValue().asLong()));
        long unk = vocab.get(n.path("unk_token").asText("[UNK]"));
        String prefix = n.path("continuing_subword_prefix").asText("##");
        int maxChars = n.path("max_input_chars_per_word").asInt(100);
        return (word, out) -> {
            if (word.codePointCount(0, word.length()) > maxChars) {
                out.add(unk);
                return;
            }
            List<Long> pieces = new ArrayList<>();
            int start = 0;
            while (start < word.length()) {
                int end = word.length();
                Long found = null;
                int foundEnd = start;
                while (end > start) {
                    String sub = (start > 0 ? prefix : "") + word.substring(start, end);
                    Long id = vocab.get(sub);
                    if (id != null) {
                        found = id;
                        foundEnd = end;
                        break;
                    }
                    end = word.offsetByCodePoints(end, -1);
                }
                if (found == null) {
                    out.add(unk);
                    return;
                }
                pieces.add(found);
                start = foundEnd;
            }
            out.addAll(pieces);
        };
    }

    private static Model unigram(JsonNode n) {
        Map<String, double[]> vocab = new HashMap<>(); // piece -> {id, score}
        double minScore = Double.MAX_VALUE;
        int maxLen = 1;
        int idx = 0;
        for (JsonNode entry : n.get("vocab")) {
            String piece = entry.get(0).asText();
            double score = entry.get(1).asDouble();
            vocab.put(piece, new double[] {idx++, score});
            minScore = Math.min(minScore, score);
            maxLen = Math.max(maxLen, piece.codePointCount(0, piece.length()));
        }
        long unkId = n.path("unk_id").asLong(0);
        double unkScore = minScore - 10.0; // HF K_UNK_PENALTY
        int maxPiece = maxLen;
        return (word, out) -> {
            int[] cps = word.codePoints().toArray();
            int len = cps.length;
            double[] best = new double[len + 1];
            int[] from = new int[len + 1];
            long[] tok = new long[len + 1];
            java.util.Arrays.fill(best, Double.NEGATIVE_INFINITY);
            best[0] = 0;
            for (int i = 0; i < len; i++) {
                if (best[i] == Double.NEGATIVE_INFINITY) continue;
                boolean singleFound = false;
                StringBuilder sb = new StringBuilder();
                for (int j = i; j < Math.min(len, i + maxPiece); j++) {
                    sb.appendCodePoint(cps[j]);
                    double[] v = vocab.get(sb.toString());
                    if (v != null) {
                        if (j == i) singleFound = true;
                        double cand = best[i] + v[1];
                        if (cand > best[j + 1]) {
                            best[j + 1] = cand;
                            from[j + 1] = i;
                            tok[j + 1] = (long) v[0];
                        }
                    }
                }
                if (!singleFound) {
                    double cand = best[i] + unkScore;
                    if (cand > best[i + 1]) {
                        best[i + 1] = cand;
                        from[i + 1] = i;
                        tok[i + 1] = unkId;
                    }
                }
            }
            List<Long> rev = new ArrayList<>();
            for (int pos = len; pos > 0; pos = from[pos]) rev.add(tok[pos]);
            Long prev = null;
            for (int i = rev.size() - 1; i >= 0; i--) {
                long id = rev.get(i);
                if (id == unkId && prev != null && prev == unkId) continue; // fuse_unk
                out.add(id);
                prev = id;
            }
        };
    }
}
