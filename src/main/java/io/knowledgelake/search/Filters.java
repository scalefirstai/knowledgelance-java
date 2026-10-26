package io.knowledgelake.search;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.knowledgelake.okf.Okf;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Metadata filters. Lists accept a single string too ({@code "type": "Runbook"}); dates are ISO
 * strings. Field names match the REST/MCP contract.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Filters {
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    public List<String> type;

    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    public List<String> bundle;

    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    public List<String> scope;

    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    public List<String> tags;

    public String dir;
    public String after;
    public String before;

    public Filters() {}

    public Filters(List<String> type, List<String> bundle, List<String> scope, List<String> tags,
                   String dir, String after, String before) {
        this.type = blankToNull(type);
        this.bundle = blankToNull(bundle);
        this.scope = blankToNull(scope);
        this.tags = blankToNull(tags);
        this.dir = dir == null || dir.isBlank() ? null : dir;
        this.after = after == null || after.isBlank() ? null : after;
        this.before = before == null || before.isBlank() ? null : before;
    }

    private static List<String> blankToNull(List<String> l) {
        return l == null || l.isEmpty() ? null : l;
    }

    public LocalDateTime afterTs() {
        return Okf.parseTimestamp(after);
    }

    public LocalDateTime beforeTs() {
        return Okf.parseTimestamp(before);
    }

    /** Explicit (API) filters win over inline query filters, field by field. */
    public Filters merged(Filters explicit) {
        if (explicit == null) return this;
        return new Filters(
                explicit.type != null ? explicit.type : type,
                explicit.bundle != null ? explicit.bundle : bundle,
                explicit.scope != null ? explicit.scope : scope,
                explicit.tags != null ? explicit.tags : tags,
                explicit.dir != null ? explicit.dir : dir,
                explicit.after != null ? explicit.after : after,
                explicit.before != null ? explicit.before : before);
    }
}
