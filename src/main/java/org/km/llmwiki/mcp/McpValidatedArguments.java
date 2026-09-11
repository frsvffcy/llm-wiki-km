package org.km.llmwiki.mcp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Strictly validated tool arguments. Values are produced only by
 * {@link McpToolInputContract#validate}, so getters never coerce and never return
 * out-of-contract data. Callers read required values directly; optional values without a
 * default are absent from the map.
 */
public final class McpValidatedArguments {

    private final Map<String, Object> values;

    McpValidatedArguments(Map<String, Object> values) {
        this.values = Map.copyOf(values);
    }

    /** Required string field; always present after successful validation. */
    public String string(String name) {
        return stringOr(name, null);
    }

    /** Optional string field; the contract default was already applied during validation. */
    public String stringOr(String name, String fallback) {
        Object value = values.get(Objects.requireNonNull(name, "field name must not be null"));
        return value == null ? fallback : (String) value;
    }

    /** Required long field; always present after successful validation. */
    public long longValue(String name) {
        Object value = values.get(Objects.requireNonNull(name, "field name must not be null"));
        if (value == null) {
            throw new IllegalStateException("validated field is missing: " + name);
        }
        return ((Number) value).longValue();
    }

    /** Optional long field; empty when absent and default-free. */
    public Long longOrNull(String name) {
        Object value = values.get(Objects.requireNonNull(name, "field name must not be null"));
        return value == null ? null : ((Number) value).longValue();
    }

    /** Integer field with a contract default; always present after successful validation. */
    public int intValue(String name) {
        Object value = values.get(Objects.requireNonNull(name, "field name must not be null"));
        if (value == null) {
            throw new IllegalStateException("validated field is missing: " + name);
        }
        return ((Number) value).intValue();
    }

    Map<String, Object> view() {
        return new LinkedHashMap<>(values);
    }
}
