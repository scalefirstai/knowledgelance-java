package io.knowledgelake.okf;

import io.knowledgelake.okf.Okf.Chunk;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;

/**
 * Splits markdown on headings, then packs blocks into ~maxTokens chunks with overlap; tables and
 * fenced code stay whole. Each chunk carries its heading path ("Runbook > Cut-off > Late trades");
 * when the document has no H1 the concept title is the root, so every chunk names its page.
 */
public final class Chunker {
    private Chunker() {}

    private record Block(String text, boolean atomic) {}

    private record Section(List<String> headings, List<String> lines) {}

    public static List<Chunk> chunk(String body, String title, int maxTokens, double overlap) {
        List<Section> sections = new ArrayList<>();
        List<int[]> levels = new ArrayList<>(); // parallel to stack: heading level
        List<String> stack = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        boolean inFence = false;
        boolean hasH1 = false;
        for (String line : body.split("\n", -1)) {
            if (Okf.FENCE.matcher(line).find()) inFence = !inFence;
            Matcher m = inFence ? null : Okf.HEADING.matcher(line);
            if (m != null && m.matches()) {
                sections.add(new Section(List.copyOf(stack), cur));
                cur = new ArrayList<>();
                int level = m.group(1).length();
                hasH1 |= level == 1;
                while (!stack.isEmpty() && levels.get(levels.size() - 1)[0] >= level) {
                    stack.remove(stack.size() - 1);
                    levels.remove(levels.size() - 1);
                }
                stack.add(m.group(2));
                levels.add(new int[] {level});
            } else {
                cur.add(line);
            }
        }
        sections.add(new Section(List.copyOf(stack), cur));

        int overlapTokens = (int) (maxTokens * overlap);
        List<Chunk> chunks = new ArrayList<>();
        for (Section section : sections) {
            List<String> headings = new ArrayList<>();
            if (!hasH1) headings.add(title);
            headings.addAll(section.headings());
            headings.removeIf(String::isEmpty);
            String headingPath = String.join(" > ", headings);
            Packer packer = new Packer(chunks, headingPath, maxTokens, overlapTokens);
            for (Block block : blocks(section.lines())) {
                List<String> pieces = block.atomic() || Okf.countTokens(block.text()) <= maxTokens
                        ? List.of(block.text())
                        : splitLong(block.text(), maxTokens, overlapTokens);
                for (String piece : pieces) packer.add(piece);
            }
            packer.emit();
        }
        if (chunks.isEmpty()) chunks.add(new Chunk(0, title, title)); // empty body: still findable by title
        return chunks;
    }

    /** Accumulates pieces for one section and emits chunks with carried-over overlap. */
    private static final class Packer {
        private final List<Chunk> out;
        private final String headingPath;
        private final int maxTokens;
        private final int overlapTokens;
        private final List<String> pending = new ArrayList<>();
        private int pendingTokens = 0;
        private String carry = "";

        Packer(List<Chunk> out, String headingPath, int maxTokens, int overlapTokens) {
            this.out = out;
            this.headingPath = headingPath;
            this.maxTokens = maxTokens;
            this.overlapTokens = overlapTokens;
        }

        void add(String piece) {
            int n = Okf.countTokens(piece);
            if (!pending.isEmpty() && pendingTokens + n > maxTokens) emit();
            pending.add(piece);
            pendingTokens += n;
        }

        void emit() {
            if (pending.isEmpty()) return;
            String text = String.join("\n\n", pending);
            if (!carry.isEmpty()) text = carry + " …\n\n" + text;
            out.add(new Chunk(out.size(), headingPath, text));
            String last = pending.get(pending.size() - 1).stripLeading();
            carry = last.startsWith("|") || last.startsWith("```") || last.startsWith("~~~") ? "" : tail(last, overlapTokens);
            pending.clear();
            pendingTokens = 0;
        }
    }

    /** Group section lines into paragraphs, tables and fenced code blocks. */
    private static List<Block> blocks(List<String> lines) {
        List<Block> blocks = new ArrayList<>();
        List<String> buf = new ArrayList<>();
        String kind = null; // para | table | code
        for (String line : lines) {
            if ("code".equals(kind)) {
                buf.add(line);
                if (Okf.FENCE.matcher(line).find()) {
                    flush(blocks, buf, kind);
                    kind = null;
                }
                continue;
            }
            if (Okf.FENCE.matcher(line).find()) {
                flush(blocks, buf, kind);
                kind = "code";
                buf.add(line);
                continue;
            }
            if (line.stripLeading().startsWith("|")) {
                if (!"table".equals(kind)) {
                    flush(blocks, buf, kind);
                    kind = "table";
                }
                buf.add(line);
                continue;
            }
            if ("table".equals(kind)) {
                flush(blocks, buf, kind);
                kind = null;
            }
            if (line.isBlank()) {
                flush(blocks, buf, kind);
                kind = null;
                continue;
            }
            kind = "para";
            buf.add(line);
        }
        flush(blocks, buf, kind);
        return blocks;
    }

    private static void flush(List<Block> blocks, List<String> buf, String kind) {
        if (!buf.isEmpty() && buf.stream().anyMatch(s -> !s.isBlank())) {
            String text = String.join("\n", buf).replaceAll("^\n+|\n+$", "");
            blocks.add(new Block(text, "table".equals(kind) || "code".equals(kind)));
        }
        buf.clear();
    }

    /** Window an over-long paragraph by words, with overlap. */
    private static List<String> splitLong(String text, int maxTokens, int overlapTokens) {
        String[] words = text.strip().split("\\s+");
        List<String> out = new ArrayList<>();
        int step = Math.max(1, maxTokens - overlapTokens);
        for (int start = 0; start < words.length; start += step) {
            int end = Math.min(words.length, start + maxTokens);
            out.add(String.join(" ", Arrays.copyOfRange(words, start, end)));
            if (end >= words.length) break;
        }
        return out;
    }

    private static String tail(String text, int nTokens) {
        if (nTokens <= 0 || text.isBlank()) return "";
        String[] words = text.strip().split("\\s+");
        return String.join(" ", Arrays.copyOfRange(words, Math.max(0, words.length - nTokens), words.length));
    }
}
