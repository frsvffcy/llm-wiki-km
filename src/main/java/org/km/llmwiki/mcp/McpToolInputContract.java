package org.km.llmwiki.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Single authority for one tool's input contract. The same field list drives both the
 * advertised JSON Schema projection ({@code tools/list}) and the strict runtime validation
 * ({@code tools/call}), so the two can never drift apart.
 *
 * <p>Validation is fail-closed and coercion-free: a JSON value is accepted only when its
 * actual type matches the declared field type. Numbers never become strings, strings never
 * become integers, and booleans/objects/arrays never enter the application through
 * {@code asText}/{@code asInt}/{@code asLong} coercion. Unknown properties are rejected
 * ({@code additionalProperties: false} is enforced, not merely advertised), as are missing
 * required fields, nulls for required fields, integer overflow, and fractions for integer
 * fields. Rejection messages carry only the field name (bounded) and the violated rule —
 * never raw JSON, paths, or exception chains.
 *
 * <p>Integer fields follow the JSON Schema 2020-12 mathematical-integer semantics: a JSON
 * number is accepted exactly when its mathematical value has no fractional part ({@code 2},
 * {@code 2.0}, and {@code 1e2} are the same set) and converts exactly into the declared
 * int/long range — there is no representation-class gate and no truncation, so the
 * advertised schema and this validator accept the identical numeric set (#348).
 *
 * <p>Structural acceptance is exact: the runtime never accepts an input the advertised
 * schema deems invalid. Enum matching is canonical exact — there is deliberately no
 * case-insensitive alias path, because JSON Schema {@code enum} cannot express one and
 * any such alias would reopen the drift this contract closes. Defaults apply to absent
 * fields only; present values — including blanks — are validated. The required-string
 * non-blank rule is expressed by the advertised {@code pattern} keyword and enforced at
 * runtime by the same character set, so the two cannot drift.
 */
public record McpToolInputContract(
        String toolName,
        List<McpFieldContract> fields
) {
    /**
     * ECMA-262 WhiteSpace plus LineTerminator: exactly the set the JSON Schema
     * {@code \s} class matches. The same source text drives the advertised
     * {@code pattern} keyword ({@link #jsonSchema()}) and the runtime blank check
     * below, so the required-string non-blank rule cannot drift between schema and
     * validator. Astral characters are content in both dialects (the set is BMP-only),
     * so surrogate pairs never change the blank verdict.
     */
    static final String NON_BLANK_PATTERN = "[^\\u0009-\\u000D\\u0020\\u00A0\\u1680"
            + "\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]";

    private static final java.util.regex.Pattern NON_BLANK =
            java.util.regex.Pattern.compile(NON_BLANK_PATTERN);

    public McpToolInputContract {
        Objects.requireNonNull(toolName, "tool name must not be null");
        fields = List.copyOf(Objects.requireNonNull(fields, "fields must not be null"));
        List<String> names = fields.stream().map(McpFieldContract::name).toList();
        if (new java.util.HashSet<>(names).size() != names.size()) {
            throw new IllegalArgumentException("duplicate field in tool contract: " + toolName);
        }
    }

    public McpValidatedArguments validate(JsonNode arguments) {
        // An explicit JSON null root is a present, mistyped value — not an absent object —
        // exactly as the advertised {type: object} sees it. Java-null and missing nodes
        // still mean "no arguments were supplied".
        if (arguments != null && !arguments.isObject() && !arguments.isMissingNode()) {
            throw new McpToolInputException("arguments must be an object");
        }
        Map<String, McpFieldContract> byName = new LinkedHashMap<>();
        for (McpFieldContract field : fields) {
            byName.put(field.name(), field);
        }
        if (arguments != null && arguments.isObject()) {
            Iterator<String> names = arguments.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!byName.containsKey(name)) {
                    throw new McpToolInputException("unsupported argument: " + safeName(name));
                }
            }
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (McpFieldContract field : fields) {
            JsonNode node = arguments == null ? null : arguments.get(field.name());
            Object value = validateField(field, node);
            if (value != null) {
                values.put(field.name(), value);
            }
        }
        return new McpValidatedArguments(values);
    }

    private static Object validateField(McpFieldContract field, JsonNode node) {
        if (node == null || node.isMissingNode()) {
            if (field.required()) {
                throw new McpToolInputException(field.name() + " is required");
            }
            return field.defaultValue();
        }
        // An explicit JSON null is a present value, not an absent field: it fails the
        // declared type exactly as the advertised schema sees it (only absent fields take
        // defaults).
        return switch (field.type()) {
            case STRING -> validateString(field, node);
            case INTEGER -> validateInteger(field, node);
        };
    }

    private static String validateString(McpFieldContract field, JsonNode node) {
        if (!node.isTextual()) {
            throw new McpToolInputException(field.name() + " must be a string");
        }
        String value = node.textValue();
        if (field.required() && !NON_BLANK.matcher(value).find()) {
            throw new McpToolInputException(field.name() + " is required");
        }
        if (!field.required() && field.allowedValues().isEmpty()
                && !NON_BLANK.matcher(value).find()) {
            return (String) field.defaultValue();
        }
        if (field.maxCodePoints() != null
                && value.codePointCount(0, value.length()) > field.maxCodePoints()) {
            throw new McpToolInputException(field.name() + " must not exceed "
                    + field.maxCodePoints() + " Unicode code points");
        }
        if (!field.allowedValues().isEmpty()) {
            if (!field.allowedValues().contains(value)) {
                throw new McpToolInputException(field.name() + " is invalid");
            }
            return value;
        }
        return value;
    }

    /**
     * Mathematical-integer contract (#348): JSON Schema 2020-12 matches {@code integer}
     * against the numeric <em>value</em>, not its representation — {@code 2}, {@code 2.0},
     * and {@code 1e2} are the same integer — so the runtime validates the exact decimal
     * value the wire parsed (never a Jackson node class, never a lossy double cast), then
     * converts exactly into the target range. Fractions reject deterministically, values
     * beyond int/long reject before any narrowing, and non-numeric types never coerce.
     */
    private static Number validateInteger(McpFieldContract field, JsonNode node) {
        if (!node.isNumber()) {
            throw new McpToolInputException(field.name() + " must be an integer");
        }
        java.math.BigDecimal decimal;
        try {
            decimal = node.decimalValue();
        } catch (NumberFormatException unparsable) {
            // No exact numeric value exists (e.g. a non-finite double): fail closed.
            throw new McpToolInputException(field.name() + " must be an integer");
        }
        if (decimal.remainder(java.math.BigDecimal.ONE).signum() != 0) {
            throw new McpToolInputException(field.name() + " must be an integer");
        }
        java.math.BigInteger exact;
        try {
            exact = decimal.toBigIntegerExact();
        } catch (ArithmeticException unparsable) {
            throw new McpToolInputException(field.name() + " must be an integer");
        }
        if (field.intRange()) {
            if (exact.compareTo(java.math.BigInteger.valueOf(Integer.MAX_VALUE)) > 0
                    || exact.compareTo(java.math.BigInteger.valueOf(Integer.MIN_VALUE)) < 0) {
                throw new McpToolInputException(field.name() + " is out of range");
            }
            int value = exact.intValueExact();
            checkRange(field, value);
            return value;
        }
        if (exact.compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE)) > 0
                || exact.compareTo(java.math.BigInteger.valueOf(Long.MIN_VALUE)) < 0) {
            throw new McpToolInputException(field.name() + " is out of range");
        }
        long value = exact.longValueExact();
        checkRange(field, value);
        return value;
    }

    private static void checkRange(McpFieldContract field, long value) {
        if ((field.minValue() != null && value < field.minValue())
                || (field.maxValue() != null && value > field.maxValue())) {
            if (field.minValue() != null && field.maxValue() != null) {
                throw new McpToolInputException(field.name() + " must be between "
                        + field.minValue() + " and " + field.maxValue());
            }
            // Wording mirrors the application contracts the tools reuse: a zero floor is
            // "must be >= 0" (SearchService page), a unit floor is "must be positive"
            // (documentId/chunkId).
            if (Long.valueOf(0).equals(field.minValue())) {
                throw new McpToolInputException(field.name() + " must be >= 0");
            }
            throw new McpToolInputException(field.name() + " must be positive");
        }
    }

    private static String safeName(String name) {
        if (name.length() <= 64 && name.chars().allMatch(code ->
                code >= 0x21 && code < 0x7F)) {
            return name;
        }
        return "<invalid argument name>";
    }

    /** JSON Schema projection of this contract for {@code tools/list}. */
    public Map<String, Object> jsonSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (McpFieldContract field : fields) {
            Map<String, Object> property = new LinkedHashMap<>();
            if (field.type() == McpFieldType.STRING) {
                property.put("type", "string");
                if (field.maxCodePoints() != null) {
                    property.put("maxLength", field.maxCodePoints());
                }
                if (field.required()) {
                    property.put("pattern", NON_BLANK_PATTERN);
                }
            } else {
                property.put("type", "integer");
                if (field.minValue() != null) {
                    property.put("minimum", field.minValue());
                }
                if (field.maxValue() != null) {
                    property.put("maximum", field.maxValue());
                } else if (field.intRange()) {
                    property.put("maximum", (long) Integer.MAX_VALUE);
                } else {
                    // Java long target: the type cap is part of the advertised contract so
                    // the schema rejects what the runtime cannot represent (a BigInteger
                    // beyond long range satisfies minimum but fails canConvertToLong).
                    property.put("maximum", Long.MAX_VALUE);
                }
            }
            if (!field.allowedValues().isEmpty()) {
                property.put("enum", List.copyOf(field.allowedValues()));
            }
            if (field.defaultValue() != null) {
                property.put("default", field.defaultValue());
            }
            properties.put(field.name(), property);
            if (field.required()) {
                required.add(field.name());
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }
}
