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
 *   <li>{@code allowedValues} holds the canonical enum spellings. Matching is exact:
 *   there is no case-insensitive alias path, because JSON Schema {@code enum} cannot
 *   express one. Application layers behind the tools may keep their own normalization
 *   (such as {@code SearchCorpus}), but that is not part of the MCP advertised
 *   schema.</li>
 *   <li>{@code defaultValue} applies when the field is absent only — a present blank
 *   value is validated, never silently defaulted — and must itself satisfy the field
 *   constraints (canonical enum spelling, range, code-point bound); required fields carry
 *   no default. Violations fail fast at declaration time.</li>
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
        if (required && defaultValue != null) {
            throw new IllegalArgumentException(
                    "required field must not carry a default: " + name);
        }
        if (defaultValue instanceof String text) {
            checkStringDefault(name, maxCodePoints, allowedValues, text);
        } else if (defaultValue instanceof Number number) {
            checkIntegerDefault(name, minValue, maxValue, intRange, number);
        }
    }

    /**
     * Fail-fast misdeclaration guard: a default that violates its own field constraints
     * can never satisfy both the advertised schema and the runtime validator, so the
     * contract is rejected at declaration time instead of drifting at runtime.
     */
    private static void checkStringDefault(String name, Integer maxCodePoints,
                                           List<String> allowedValues, String value) {
        if (maxCodePoints != null
                && value.codePointCount(0, value.length()) > maxCodePoints) {
            throw new IllegalArgumentException("invalid default for field: " + name);
        }
        if (!allowedValues.isEmpty() && !allowedValues.contains(value)) {
            throw new IllegalArgumentException("invalid default for field: " + name);
        }
    }

    private static void checkIntegerDefault(String name, Long minValue, Long maxValue,
                                            boolean intRange, Number value) {
        long numeric = value.longValue();
        if (intRange
                && (numeric < Integer.MIN_VALUE || numeric > Integer.MAX_VALUE)) {
            throw new IllegalArgumentException("invalid default for field: " + name);
        }
        if ((minValue != null && numeric < minValue)
                || (maxValue != null && numeric > maxValue)) {
            throw new IllegalArgumentException("invalid default for field: " + name);
        }
    }

    public static McpFieldContract requiredString(String name, int maxCodePoints) {
        return new McpFieldContract(name, McpFieldType.STRING, true, maxCodePoints, null, null,
                false, List.of(), null);
    }

    public static McpFieldContract optionalString(String name, Integer maxCodePoints,
                                                  Object defaultValue) {
        return new McpFieldContract(name, McpFieldType.STRING, false, maxCodePoints, null, null,
                false, List.of(), defaultValue);
    }

    public static McpFieldContract optionalEnum(String name, List<String> allowedValues,
                                                Object defaultValue) {
        return new McpFieldContract(name, McpFieldType.STRING, false, null, null, null,
                false, allowedValues, defaultValue);
    }

    public static McpFieldContract requiredLong(String name, long minValue) {
        return new McpFieldContract(name, McpFieldType.INTEGER, true, null, minValue, null,
                false, List.of(), null);
    }

    public static McpFieldContract optionalLong(String name, long minValue, Object defaultValue) {
        return new McpFieldContract(name, McpFieldType.INTEGER, false, null, minValue, null,
                false, List.of(), defaultValue);
    }

    public static McpFieldContract optionalInt(String name, int minValue, int maxValue,
                                               int defaultValue) {
        return new McpFieldContract(name, McpFieldType.INTEGER, false, null, (long) minValue,
                (long) maxValue, true, List.of(), defaultValue);
    }

    /** Integer with only a lower bound; the int target itself caps the upper end. */
    public static McpFieldContract optionalInt(String name, int minValue, int defaultValue) {
        return new McpFieldContract(name, McpFieldType.INTEGER, false, null, (long) minValue,
                null, true, List.of(), defaultValue);
    }
}
