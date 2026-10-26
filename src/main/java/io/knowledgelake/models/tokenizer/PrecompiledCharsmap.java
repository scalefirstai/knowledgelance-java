package io.knowledgelake.models.tokenizer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.BreakIterator;
import java.util.Base64;

/**
 * SentencePiece "precompiled charsmap" normalizer, as used by XLM-R / bge-m3 tokenizers.
 *
 * <p>The blob is {@code uint32 trieSize | trie (darts-clone double array) | normalized strings}.
 * Normalization follows Hugging Face {@code spm_precompiled}: for each grapheme shorter than 6
 * bytes, the first common-prefix match in the trie replaces the whole grapheme; otherwise each
 * code point is looked up on its own and copied through when absent.
 */
final class PrecompiledCharsmap {
    private final int[] units;
    private final byte[] normalized;

    PrecompiledCharsmap(String base64) {
        byte[] blob = Base64.getDecoder().decode(base64);
        ByteBuffer bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        int trieSize = bb.getInt();
        units = new int[trieSize / 4];
        for (int i = 0; i < units.length; i++) units[i] = bb.getInt();
        normalized = new byte[blob.length - 4 - trieSize];
        bb.get(normalized);
    }

    // darts-clone unit accessors
    private static boolean hasLeaf(int u) {
        return ((u >>> 8) & 1) == 1;
    }

    private static int value(int u) {
        return u & 0x7fffffff;
    }

    private static int label(int u) {
        return u & (0x80000000 | 0xff);
    }

    private static int offset(int u) {
        return (u >>> 10) << ((u & (1 << 9)) >>> 6);
    }

    /** Value of the first (shortest) prefix of {@code key} present in the trie, or -1. */
    private int firstPrefixValue(byte[] key) {
        int nodePos = 0;
        int unit = units[nodePos];
        nodePos ^= offset(unit);
        for (byte b : key) {
            int c = b & 0xff;
            nodePos ^= c;
            if (nodePos < 0 || nodePos >= units.length) return -1;
            unit = units[nodePos];
            if (label(unit) != c) return -1;
            nodePos ^= offset(unit);
            if (hasLeaf(unit)) return value(units[nodePos]);
        }
        return -1;
    }

    private String transform(String chunk) {
        int idx = firstPrefixValue(chunk.getBytes(StandardCharsets.UTF_8));
        if (idx < 0) return null;
        int end = idx;
        while (end < normalized.length && normalized[end] != 0) end++;
        return new String(normalized, idx, end - idx, StandardCharsets.UTF_8);
    }

    String normalize(String original) {
        StringBuilder out = new StringBuilder(original.length());
        BreakIterator it = BreakIterator.getCharacterInstance();
        it.setText(original);
        int start = it.first();
        for (int end = it.next(); end != BreakIterator.DONE; start = end, end = it.next()) {
            String g = original.substring(start, end);
            if (g.getBytes(StandardCharsets.UTF_8).length < 6) {
                String norm = transform(g);
                if (norm != null) {
                    out.append(norm);
                    continue;
                }
            }
            for (int i = 0; i < g.length(); ) {
                int cp = g.codePointAt(i);
                String part = new String(Character.toChars(cp));
                String norm = transform(part);
                out.append(norm != null ? norm : part);
                i += Character.charCount(cp);
            }
        }
        return out.toString();
    }
}
