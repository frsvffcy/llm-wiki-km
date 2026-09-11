package org.km.llmwiki.mcp;

import java.util.List;
import java.util.Objects;

/**
 * One field of a tool input contract. The same record drives both the advertised JSON Schema
 * projection and the strict runtime validation, so the two can never drift apart.
 *
 * <ul>
 *   <li>STRING fields accept only JSON strings (no number/boolean/object coercion) and are
 *   measured in Unicode code points.</li>
 *   <li>INTEGER fields accept only JSON integral numbers that fit the Java target
 *   ({@code long} unless {@link #intRange()} is set, which additionally requires an
 *   {@code int} fit); fractions, numeric strings, and booleans are rejected, as is any
 *   overflow beyond the Java type.</li>
 *   <li>{@code allowedValues} holds the canonical enum spellings. Matching is exact unless
 *   {@code caseInsensitive} mirrors an application contract that normalizes first (such as
 *   {@code SearchCorpus}); in that case the normalized spelling is what the application
 *   receives.</li>
 *   <li>{@code defaultValue} is applied when the field is absent (or blank, for optional
 *   strings); required fields have no default and reject blank values.</li>
 * </ul>
 */
public record McpFieldContract(
        String name,
        McpFieldType type,
        boolean required,
        Integer maxCodePoints,
        Long minValue,
        Long maxValue,
        boolean intRange,
        List<String> allowedValues,
        boolean caseInsensitive,
        Object defaultValue
) {
    public McpFieldContract {
        Objects.requireNonNull(name, "field name must not be null");
        Objects.requireNonNull(type, "field type must not be null");
        if (name.isBlank() || name.length() > 64) {
            throw new IllegalArgumentException("field name is invalid");
        }
        allowedValues = List.copyOf(allowedValues == null ? List.of() : allowedValues);
        if (type == McpFieldType.STRING && (minValue != null || maxValue != null || intRange)) {
            throw new IllegalArgumentException("string field must not carry integer bounds");
        }
        if (type == McpFieldType.INTEGER && maxCodePoints != null) {
            throw new IllegalArgumentException("integer field must not carry a code-point bound");
        }
        if (!allowedValues.isEmpty() && type != McpFieldType.STRING) {
            throw new IllegalArgumentException("only string fields may carry allowed values");
        }
        if (defaultValue != null) {
            if (type == McpFieldType.STRING && !(defaultValue instanceof String)) {
                throw new IllegalArgumentException("string field default must be a string");
            }
            if (type == McpFieldType.INTEGER && !(defaultValue instanceof Long)
                    && !(defaultValue instanceof Integer)) {
                throw new IllegalArgumentException("integer field default must be numeric");
            }
        }
    }

    public static McpFieldContract requiredString(String name, int maxCodePoints) {
        return new McpFieldContract(name, McpFieldType.STRING, true, maxCodePoints, null, null,
                false, List.of(), false, null);
    }

    public static McpFieldContract optionalString(String name, Integer maxCodePoints,
                                                  Object defaultValue) {
        return new McpFieldContract(name, McpFieldType.STRING, false, maxCodePoints, null, null,
                false, List.of(), false, defaultValue);
    }

    public static McpFieldContract optionalEnum(String name, List<String> allowedValues,
                                                boolean caseInsensitive, Object defaultValue) {
        return new McpFieldContract(name, McpFieldType.STRING, false, null, null, null,
                false, allowedValues, caseInsensitive, defaultValue);
    }

    public static McpFieldContract requiredLong(String name, long minValue) {
        return new McpFieldContract(name, McpFieldType.INTEGER, true, null, minValue, null,
                false, List.of(), false, null);
    }

    public static McpFieldContract optionalLong(String name, long minValue, Object defaultValue) {
        return new McpFieldContract(name, McpFieldType.INTEGER, false, null, minValue, null,
                false, List.of(), false, defaultValue);
    }

    public static McpFieldContract optionalInt(String name, int minValue, int maxValue,
                                               int defaultValue) {
        return new McpFieldContract(name, McpFieldType.INTEGER, false, null, (long) minValue,
                (long) maxValue, true, List.of(), false, defaultValue);
    }

    /** Integer with only a lower bound; the int target itself caps the upper end. */
    public static McpFieldContract optionalInt(String name, int minValue, int defaultValue) {
        return new McpFieldContract(name, McpFieldType.INTEGER, false, null, (long) minValue,
                null, true, List.of(), false, defaultValue);
    }
}
