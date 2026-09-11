package org.km.llmwiki.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 */
public record McpToolInputContract(
        String toolName,
        List<McpFieldContract> fields
) {
    public McpToolInputContract {
        Objects.requireNonNull(toolName, "tool name must not be null");
        fields = List.copyOf(Objects.requireNonNull(fields, "fields must not be null"));
        List<String> names = fields.stream().map(McpFieldContract::name).toList();
        if (new java.util.HashSet<>(names).size() != names.size()) {
            throw new IllegalArgumentException("duplicate field in tool contract: " + toolName);
        }
    }

    public McpValidatedArguments validate(JsonNode arguments) {
        if (arguments != null && !arguments.isObject() && !arguments.isMissingNode()
                && !arguments.isNull()) {
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
        if (node == null || node.isNull() || node.isMissingNode()) {
            if (field.required()) {
                throw new McpToolInputException(field.name() + " is required");
            }
            return field.defaultValue();
        }
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
        if (value.isBlank()) {
            if (field.required()) {
                throw new McpToolInputException(field.name() + " is required");
            }
            return (String) field.defaultValue();
        }
        if (field.maxCodePoints() != null
                && value.codePointCount(0, value.length()) > field.maxCodePoints()) {
            throw new McpToolInputException(field.name() + " must not exceed "
                    + field.maxCodePoints() + " Unicode code points");
        }
        if (!field.allowedValues().isEmpty()) {
            String candidate = field.caseInsensitive()
                    ? value.strip().toUpperCase(Locale.ROOT) : value;
            if (!field.allowedValues().contains(candidate)) {
                throw new McpToolInputException(field.name() + " is invalid");
            }
            return candidate;
        }
        return value;
    }

    private static Number validateInteger(McpFieldContract field, JsonNode node) {
        if (!node.isIntegralNumber()) {
            throw new McpToolInputException(field.name() + " must be an integer");
        }
        if (field.intRange()) {
            if (!node.canConvertToInt()) {
                throw new McpToolInputException(field.name() + " is out of range");
            }
            int value = node.intValue();
            checkRange(field, value);
            return value;
        }
        if (!node.canConvertToLong()) {
            throw new McpToolInputException(field.name() + " is out of range");
        }
        long value = node.longValue();
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
            if (field.minValue() == 0) {
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
            } else {
                property.put("type", "integer");
                if (field.minValue() != null) {
                    property.put("minimum", field.minValue());
                }
                if (field.maxValue() != null) {
                    property.put("maximum", field.maxValue());
                } else if (field.intRange()) {
                    property.put("maximum", (long) Integer.MAX_VALUE);
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
