package io.knowledgelake.index;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What one indexer run did. */
public final class IndexStats {
    public int seen;
    public int unchanged;
    public int indexed;
    public int aclOnly;
    public int deleted;
    public int invalid;
    public int reserved;
    public int chunksEmbedded;
    public int chunksCached;
    public double seconds;
    public final List<String> errors = new ArrayList<>();

    public Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seen", seen);
        m.put("unchanged", unchanged);
        m.put("indexed", indexed);
        m.put("acl_only", aclOnly);
        m.put("deleted", deleted);
        m.put("invalid", invalid);
        m.put("reserved", reserved);
        m.put("chunks_embedded", chunksEmbedded);
        m.put("chunks_cached", chunksCached);
        m.put("seconds", seconds);
        return m;
    }

    public Map<String, Object> asMap() {
        Map<String, Object> m = summary();
        m.put("errors", errors);
        return m;
    }
}
