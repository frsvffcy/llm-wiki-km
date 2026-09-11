package org.km.llmwiki.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.ai.ask.AskApplicationService;
import org.km.llmwiki.ai.provider.ProviderEgressService;
import org.km.llmwiki.rag.RetrievalInspectorService;
import org.km.llmwiki.search.SearchService;
import org.km.llmwiki.source.SourceChunkLocatorService;
import org.km.llmwiki.system.SystemStatusService;
import org.km.llmwiki.wiki.WikiPageType;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Single-authority contract tests for #335, exact acceptance for #341: the JSON Schema
 * advertised by {@code tools/list} and the strict runtime validation executed by
 * {@code tools/call} come from one {@link McpToolInputContract} per tool — same object by
 * construction ({@code descriptor.inputContract()} IS {@code contractFor(name)}), so the
 * identity is structural and the tests pin the projection shape plus the challenge
 * behaviors (no coercion, canonical-exact enums, absent-only defaults, operator-safe
 * messages, no execution on invalid input).
 */
@Tag("unit")
class McpToolInputContractTest {

    @Test
    void manifestExposesExactlyTheFiveReadOnlyToolsWithTheirOwnContracts() {
        assertThat(McpCapabilityManifest.tools().keySet()).containsExactlyInAnyOrder(
                "km_status", "km_search", "km_retrieval_inspect", "km_source_locator",
                "km_ask");
        for (String tool : McpCapabilityManifest.tools().keySet()) {
            assertThat(McpCapabilityManifest.contractFor(tool)).isNotNull();
            assertThat(McpCapabilityManifest.contractFor(tool).toolName()).isEqualTo(tool);
        }
        assertThat(McpCapabilityManifest.contractFor("km_publish")).isNull();
    }

    @Test
    void advertisedSchemaIsProjectedFromTheSameContractObjectTheValidatorExecutes() {
        for (Map.Entry<String, McpToolDescriptor> entry
                : McpCapabilityManifest.tools().entrySet()) {
            Map<String, Object> fromDescriptor = entry.getValue().inputContract().jsonSchema();
            Map<String, Object> fromContract =
                    McpCapabilityManifest.contractFor(entry.getKey()).jsonSchema();
            assertThat(fromDescriptor).isEqualTo(fromContract);
            assertThat(fromDescriptor).containsKeys("type", "properties", "required",
                    "additionalProperties");
            assertThat(fromDescriptor.get("type")).isEqualTo("object");
            assertThat(fromDescriptor.get("additionalProperties")).isEqualTo(false);
        }
        Map<String, Object> searchSchema =
                McpCapabilityManifest.contractFor("km_search").jsonSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
                (Map<String, Object>) searchSchema.get("properties");
        assertThat(properties.get("query")).isEqualTo(Map.of(
                "type", "string", "maxLength", 256, "pattern",
                McpToolInputContract.NON_BLANK_PATTERN));
        assertThat(properties.get("corpus")).isEqualTo(Map.of(
                "type", "string", "enum", List.of("WIKI", "SOURCE", "ALL"), "default", "WIKI"));
        assertThat(properties.get("pageType")).isEqualTo(Map.of(
                "type", "string", "enum",
                Arrays.stream(WikiPageType.values()).map(Enum::name).toList()));
        assertThat(properties.get("documentId")).isEqualTo(Map.of(
                "type", "integer", "minimum", 1L, "maximum", Long.MAX_VALUE));
        assertThat(properties.get("page")).isEqualTo(Map.of(
                "type", "integer", "minimum", 0L, "maximum", (long) Integer.MAX_VALUE,
                "default", 1));
        assertThat(properties.get("size")).isEqualTo(Map.of(
                "type", "integer", "minimum", 1L, "maximum", 200L, "default", 20));
        assertThat(searchSchema.get("required")).isEqualTo(List.of("query"));
        Map<String, Object> locatorSchema =
                McpCapabilityManifest.contractFor("km_source_locator").jsonSchema();
        assertThat(locatorSchema.get("required")).isEqualTo(List.of("chunkId"));
        Map<String, Object> askSchema =
                McpCapabilityManifest.contractFor("km_ask").jsonSchema();
        assertThat(askSchema.get("required")).isEqualTo(List.of("question"));
        Map<String, Object> statusSchema =
                McpCapabilityManifest.contractFor("km_status").jsonSchema();
        assertThat(statusSchema.get("properties")).isEqualTo(Map.of());
        assertThat(statusSchema.get("required")).isEqualTo(List.of());
    }

    @Test
    void numericFieldsAreDeclaredAndValidatedAsIntegersWithoutCoercion() {
        assertThatThrownBy(() -> validate("km_source_locator", "{\"chunkId\":\"123\"}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("chunkId must be an integer");
        assertThatThrownBy(() -> validate("km_search", "{\"query\":\"q\",\"size\":\"200\"}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("size must be an integer");
        assertThatThrownBy(() -> validate("km_search", "{\"query\":\"q\",\"size\":1.5}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("size must be an integer");
        assertThatThrownBy(() ->
                validate("km_search", "{\"query\":\"q\",\"size\":\"2.0\"}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("size must be an integer");
        assertThatThrownBy(() ->
                validate("km_search", "{\"query\":\"q\",\"page\":2147483648}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("page is out of range");
        assertThatThrownBy(() ->
                validate("km_search", "{\"query\":\"q\",\"documentId\":9223372036854775808}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("documentId is out of range");
        assertThatThrownBy(() -> validate("km_search", "{\"query\":\"q\",\"corpus\":true}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("corpus must be a string");
    }

    /**
     * #348: integer fields follow JSON Schema 2020-12 mathematical-integer semantics —
     * an integral value in any JSON numeric representation ({@code 2.0}, {@code 1e2},
     * {@code -0.0}) is the same integer, while true fractions (even below double
     * precision, which the exact-BigDecimal wire parse preserves) reject without
     * truncation and oversized values reject without overflow.
     */
    @Test
    void integralFloatRepresentationsAreMathematicalIntegersWithExactConversions() {
        // Exact target types: int fields yield Integer, long fields yield Long (#348
        // challenge 9 — the application mapper sees the declared Java type).
        assertThat(validate("km_search", "{\"query\":\"q\",\"page\":2.0}")
                .view().get("page")).isEqualTo(2).isInstanceOf(Integer.class);
        assertThat(validate("km_search", "{\"query\":\"q\",\"size\":1e2}")
                .view().get("size")).isEqualTo(100).isInstanceOf(Integer.class);
        assertThat(validate("km_search", "{\"query\":\"q\",\"page\":-0.0}")
                .view().get("page")).isEqualTo(0).isInstanceOf(Integer.class);
        assertThat(validate("km_search", "{\"query\":\"q\",\"documentId\":42.0}")
                .view().get("documentId")).isEqualTo(42L).isInstanceOf(Long.class);
        assertThat(validate("km_source_locator", "{\"chunkId\":42.0}")
                .view().get("chunkId")).isEqualTo(42L).isInstanceOf(Long.class);

        // True fractions reject deterministically — including one that a double would
        // round to 1.0; the exact decimal parse keeps the fractional part visible.
        assertThatThrownBy(() -> validate("km_search", "{\"query\":\"q\",\"size\":2.5}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("size must be an integer");
        assertThatThrownBy(() ->
                validate("km_search", "{\"query\":\"q\",\"size\":1.0000000000000001}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("size must be an integer");

        // Exact range conversion: integral representations beyond int/long reject
        // without overflow wrapping (challenges 4/5), at any exponent magnitude.
        assertThatThrownBy(() ->
                validate("km_search", "{\"query\":\"q\",\"page\":2147483648.0}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("page is out of range");
        assertThatThrownBy(() ->
                validate("km_search",
                        "{\"query\":\"q\",\"documentId\":9223372036854775808.0}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("documentId is out of range");
        assertThatThrownBy(() -> validate("km_search", "{\"query\":\"q\",\"size\":1e300}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("size is out of range");
    }

    @Test
    void advertisedBoundsAndEnumsAreEnforcedAtTheMcpBoundary() {
        assertThatThrownBy(() -> validate("km_search", "{\"query\":\"q\",\"size\":999999}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("size must be between 1 and 200");
        assertThatThrownBy(() -> validate("km_search", "{\"query\":\"q\",\"page\":-1}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("page must be >= 0");
        assertThatThrownBy(() -> validate("km_search", "{\"query\":\"q\",\"documentId\":0}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("documentId must be positive");
        assertThatThrownBy(() -> validate("km_source_locator", "{\"chunkId\":0}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("chunkId must be positive");
        assertThatThrownBy(() ->
                validate("km_search", "{\"query\":\"q\",\"corpus\":\"UNKNOWN\"}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("corpus is invalid");
        assertThatThrownBy(() ->
                validate("km_ask", "{\"question\":\"q\",\"retrievalMode\":\"NOPE\"}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("retrievalMode is invalid");
        assertThatThrownBy(() ->
                validate("km_ask", "{\"question\":\"q\",\"retrievalMode\":\"hybrid_fts\"}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("retrievalMode is invalid");
        assertThatThrownBy(() ->
                validate("km_retrieval_inspect", "{\"question\":\"q\",\"mode\":\"REBUILD\"}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("mode is invalid");
    }

    @Test
    void requiredAndTypedStringRulesAreEnforced() {
        assertThatThrownBy(() -> validate("km_search", "{}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("query is required");
        assertThatThrownBy(() -> validate("km_search", "null"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("arguments must be an object");
        assertThatThrownBy(() -> validate("km_search", "{\"query\":\"   \"}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("query is required");
        assertThatThrownBy(() -> validate("km_search", "{\"query\":123}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("query must be a string");
        assertThatThrownBy(() -> validate("km_ask", jsonQuestion("question", 4_001)))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("question must not exceed 4000 Unicode code points");
        assertThatThrownBy(() ->
                validate("km_retrieval_inspect", jsonQuestion("question", 4_001)))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("question must not exceed 4000 Unicode code points");
        assertThatThrownBy(() -> validate("km_search", jsonQuestion("query", 257)))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("query must not exceed 256 Unicode code points");
    }

    @Test
    void unknownPropertiesAndNonObjectArgumentsAreRejectedBeforeExecution() {
        assertThatThrownBy(() -> validate("km_status", "{\"foo\":1}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("unsupported argument: foo");
        assertThatThrownBy(() -> validate("km_status", "{\"名稱\":1}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("unsupported argument: <invalid argument name>");
        assertThatThrownBy(() -> validate("km_ask",
                "{\"question\":\"q\",\"retrievalMode\":\"HYBRID_FTS\",\"maxItems\":5}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("unsupported argument: maxItems");
        assertThatThrownBy(() -> validate("km_search",
                "{\"query\":\"q\",\"size\":20,\"unexpected\":{\"x\":1}}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("unsupported argument: unexpected");
        assertThatThrownBy(() -> validate("km_status", "[1,2]"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("arguments must be an object");
        assertThatThrownBy(() -> validate("km_status", "\"km_status\""))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("arguments must be an object");
    }

    @Test
    void defaultsApplyToAbsentFieldsAndEnumsAreCanonicalExact() {
        McpValidatedArguments search = McpCapabilityManifest.contractFor("km_search")
                .validate(parse("{\"query\":\" q \",\"corpus\":\"WIKI\","
                        + "\"pageType\":\"CONCEPT\"}"));
        assertThat(search.string("query")).isEqualTo(" q ");
        assertThat(search.string("corpus")).isEqualTo("WIKI");
        assertThat(search.stringOr("pageType", null)).isEqualTo("CONCEPT");
        assertThat(search.longOrNull("documentId")).isNull();
        assertThat(search.intValue("page")).isEqualTo(1);
        assertThat(search.intValue("size")).isEqualTo(20);

        // Lower/mixed-case aliases are not part of the advertised schema and are rejected;
        // the REST/Search application layers keep their own normalization untouched.
        assertThatThrownBy(() -> validate("km_search",
                "{\"query\":\"q\",\"corpus\":\"wiki\"}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("corpus is invalid");
        assertThatThrownBy(() -> validate("km_search",
                "{\"query\":\"q\",\"pageType\":\"concept\"}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("pageType is invalid");
        assertThatThrownBy(() -> validate("km_search",
                "{\"query\":\"q\",\"corpus\":\"Wiki\"}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("corpus is invalid");

        // Present blanks are validated, never silently defaulted: the schema enum has no
        // blank member, so the runtime must reject them too.
        assertThatThrownBy(() -> validate("km_search",
                "{\"query\":\"q\",\"corpus\":\"\"}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("corpus is invalid");
        assertThatThrownBy(() -> validate("km_search",
                "{\"query\":\"q\",\"corpus\":\"   \"}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("corpus is invalid");
        assertThatThrownBy(() -> validate("km_search",
                "{\"query\":\"q\",\"pageType\":\"\"}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("pageType is invalid");

        McpValidatedArguments ask = McpCapabilityManifest.contractFor("km_ask")
                .validate(parse("{\"question\":\"q\"}"));
        assertThat(ask.string("retrievalMode")).isEqualTo("HYBRID_FTS");

        McpValidatedArguments inspect = McpCapabilityManifest.contractFor(
                "km_retrieval_inspect").validate(parse("{\"question\":\"q\"}"));
        assertThat(inspect.string("mode")).isEqualTo("HYBRID_GRAPH");

        McpValidatedArguments status = McpCapabilityManifest.contractFor("km_status")
                .validate(null);
        assertThat(status.view()).isEmpty();

        McpValidatedArguments locator = McpCapabilityManifest.contractFor(
                "km_source_locator").validate(parse("{\"chunkId\":42}"));
        assertThat(locator.longValue("chunkId")).isEqualTo(42L);
    }

    @Test
    void requiredBlankRuleUsesTheAdvertisedCharacterSet() {
        assertThatThrownBy(() -> validate("km_search", "{\"query\":\"\"}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("query is required");
        assertThatThrownBy(() -> validate("km_search", "{\"query\":\" \\t\\n \"}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("query is required");
        assertThatThrownBy(() -> validate("km_search", "{\"query\":\"\u00A0\"}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("query is required");
        assertThatThrownBy(() -> validate("km_search", "{\"query\":\"\uFEFF\"}"))
                .isInstanceOf(McpToolInputException.class)
                .hasMessage("query is required");
        // Control characters outside the ECMA whitespace set are content on both sides.
        assertThat(validate("km_search", "{\"query\":\"\\u001C\"}").string("query"))
                .isEqualTo("\u001C");
        assertThat(validate("km_search", "{\"query\":\"\\u0085\"}").string("query"))
                .isEqualTo("\u0085");
    }

    @Test
    void misdeclaredContractsFailFastAtDeclaration() {
        assertThatThrownBy(() -> McpFieldContract.optionalEnum("x", List.of("A"), "B"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid default for field: x");
        assertThatThrownBy(() -> McpFieldContract.optionalInt("x", 1, 200, 999))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid default for field: x");
        assertThatThrownBy(() -> McpFieldContract.optionalString("x", 3, "toolong"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid default for field: x");
        assertThatThrownBy(() -> new McpFieldContract("q", McpFieldType.STRING, true, 10,
                null, null, false, List.of(), "dflt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("required field must not carry a default: q");
        assertThatThrownBy(() -> new McpToolInputContract("km_x", List.of(
                McpFieldContract.optionalEnum("mode", List.of("A"), "B"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid default for field: mode");
    }

    @Test
    void executorRejectsInvalidInputBeforeAnyApplicationServiceRuns() {
        SearchService searchService = mock(SearchService.class);
        McpToolExecutor executor = new McpToolExecutor(
                mock(SystemStatusService.class), searchService,
                mock(RetrievalInspectorService.class),
                mock(SourceChunkLocatorService.class), mock(AskApplicationService.class),
                mock(ProviderEgressService.class));

        McpToolResult invalid = executor.execute("km_search",
                parse("{\"query\":\"q\",\"size\":\"200\"}"));
        assertThat(invalid.isError()).isTrue();
        assertThat(invalid.errorCode()).isEqualTo(McpToolError.INVALID_REQUEST);
        assertThat(invalid.message()).isEqualTo("size must be an integer");
        verifyNoInteractions(searchService);

        assertThatThrownBy(() -> executor.execute("km_publish", parse("{}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("unknown tool: km_publish");

        whenSearchReturnsNull(searchService);
        McpToolResult valid = executor.execute("km_search",
                parse("{\"query\":\"q\",\"corpus\":\"WIKI\"}"));
        assertThat(valid.isError()).isFalse();
        verify(searchService).search("q", "WIKI", null, null, 1, 20);
    }

    private static void whenSearchReturnsNull(SearchService searchService) {
        org.mockito.Mockito.when(searchService.search(any(), any(), any(), any(), anyInt(),
                anyInt())).thenReturn(null);
    }

    private static McpValidatedArguments validate(String tool, String rawArguments) {
        return McpCapabilityManifest.contractFor(tool).validate(parse(rawArguments));
    }

    private static JsonNode parse(String raw) {
        // Production parsing: exact BigDecimal floats (#348), so fixtures are the same
        // nodes the wire plane validates.
        JsonNode node = McpJsonRpc.parseValue(raw);
        if (node == null) {
            throw new IllegalStateException("test JSON is malformed: " + raw);
        }
        return node;
    }

    private static String jsonQuestion(String field, int codePoints) {
        return "{\"" + field + "\":\"" + "a".repeat(codePoints) + "\"}";
    }
}
