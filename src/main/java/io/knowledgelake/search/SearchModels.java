package io.knowledgelake.search;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Request and response shapes of the search core (REST/MCP serialize them in snake_case). */
public final class SearchModels {
    private SearchModels() {}

    public static class SearchRequest {
        public String q = "";
        public Filters filters;
        public String mode = "hybrid"; // hybrid | text | vector
        public boolean rerank = true;
        public int k = 10;
        public int expandLinks = 0;
        public Integer maxChunksPerConcept;

        public SearchRequest() {}

        public SearchRequest(String q) {
            this.q = q;
        }

        public SearchRequest k(int k) {
            this.k = k;
            return this;
        }

        public SearchRequest mode(String mode) {
            this.mode = mode;
            return this;
        }

        public SearchRequest filters(Filters f) {
            this.filters = f;
            return this;
        }

        public SearchRequest expandLinks(int n) {
            this.expandLinks = n;
            return this;
        }

        public SearchRequest rerank(boolean r) {
            this.rerank = r;
            return this;
        }

        /** Throws IllegalArgumentException with a client-facing message. */
        public void validate() {
            if (q == null) throw new IllegalArgumentException("q is required");
            if (k < 1 || k > 100) throw new IllegalArgumentException("k must be between 1 and 100");
            if (expandLinks < 0 || expandLinks > 1) throw new IllegalArgumentException("expand_links must be 0 or 1");
            if (!List.of("hybrid", "text", "vector").contains(mode)) throw new IllegalArgumentException("mode must be hybrid, text or vector");
            if (maxChunksPerConcept != null && (maxChunksPerConcept < 1 || maxChunksPerConcept > 10)) {
                throw new IllegalArgumentException("max_chunks_per_concept must be between 1 and 10");
            }
        }
    }

    public record ChunkHit(String chunkId, String headingPath, String text, String snippet, double score,
                           Map<String, Object> breakdown) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ConceptHit(String conceptId, String title, String type, String bundle, String dir, String scope,
                             String description, List<String> tags, String resourceUrl, String sourceId,
                             LocalDateTime updated, double score, boolean pinned, Map<String, String> via,
                             List<ChunkHit> chunks) {}

    public record SearchResponse(String query, Map<String, Object> parsed, List<ConceptHit> results, int candidates,
                                 Map<String, Double> timingsMs) {}
}
