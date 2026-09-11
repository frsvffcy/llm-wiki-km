package org.km.llmwiki.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Semantic parity for #341: the advertised {@code inputSchema} and the runtime
 * {@link McpToolInputContract#validate} must agree on structural acceptance for every
 * representative fixture — proven by a real JSON Schema 2020-12 validator (pinned
 * {@code com.networknt:json-schema-validator:1.5.9}, test scope), never by comparing the
 * schema object to itself. Either side may only reject what the other rejects.
 */
@Tag("unit")
class McpToolSchemaParityTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonSchemaFactory FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private static final Map<String, JsonSchema> SCHEMAS = new ConcurrentHashMap<>();

    private static JsonSchema schemaFor(String tool) {
        return SCHEMAS.computeIfAbsent(tool, name -> FACTORY.getSchema(
                JSON.valueToTree(McpCapabilityManifest.contractFor(name).jsonSchema())));
    }

    private record Fixture(String tool, String rawArguments) {
        @Override
        public String toString() {
            return tool + " " + rawArguments;
        }
    }

    static Stream<Fixture> fixtures() {
        String query256 = "a".repeat(256);
        String query257 = "a".repeat(257);
        String astral256 = "😀".repeat(256);
        String astral257 = "😀".repeat(257);
        String mixed256 = "a".repeat(255) + "😀";
        String mixed257 = "a".repeat(256) + "😀";
        String question4000 = "a".repeat(4_000);
        String question4001 = "a".repeat(4_001);
        String astral4000 = "😀".repeat(4_000);
        return Stream.of(
                // km_search: minimal, full, and every structural rejection.
                new Fixture("km_search", "{\"query\":\"q\"}"),
                new Fixture("km_search",
                        "{\"query\":\"q\",\"corpus\":\"SOURCE\",\"pageType\":\"HOWTO\","
                                + "\"documentId\":7,\"page\":2,\"size\":50}"),
                new Fixture("km_search", "{\"query\":\"q\",\"corpus\":\"wiki\"}"),
                new Fixture("km_search", "{\"query\":\"q\",\"corpus\":\"Wiki\"}"),
                new Fixture("km_search", "{\"query\":\"q\",\"corpus\":\"\"}"),
                new Fixture("km_search", "{\"query\":\"q\",\"corpus\":\"   \"}"),
                new Fixture("km_search", "{\"query\":\"q\",\"corpus\":null}"),
                new Fixture("km_search", "{\"query\":\"q\",\"pageType\":\"concept\"}"),
                new Fixture("km_search", "{\"query\":\"q\",\"pageType\":\"CONCEPT \"}"),
                new Fixture("km_search", "{\"query\":\"q\",\"pageType\":null}"),
                new Fixture("km_search", "{}"),
                new Fixture("km_search", "{\"query\":\"\"}"),
                new Fixture("km_search", "{\"query\":\"   \"}"),
                new Fixture("km_search", "{\"query\":null}"),
                new Fixture("km_search", "{\"query\":123}"),
                new Fixture("km_search", "{\"query\":\"" + query256 + "\"}"),
                new Fixture("km_search", "{\"query\":\"" + query257 + "\"}"),
                new Fixture("km_search", "{\"query\":\"" + astral256 + "\"}"),
                new Fixture("km_search", "{\"query\":\"" + astral257 + "\"}"),
                new Fixture("km_search", "{\"query\":\"" + mixed256 + "\"}"),
                new Fixture("km_search", "{\"query\":\"" + mixed257 + "\"}"),
                new Fixture("km_search", "{\"query\":\"q\",\"size\":1}"),
                new Fixture("km_search", "{\"query\":\"q\",\"size\":200}"),
                new Fixture("km_search", "{\"query\":\"q\",\"size\":0}"),
                new Fixture("km_search", "{\"query\":\"q\",\"size\":201}"),
                new Fixture("km_search", "{\"query\":\"q\",\"size\":999999}"),
                new Fixture("km_search", "{\"query\":\"q\",\"size\":\"200\"}"),
                new Fixture("km_search", "{\"query\":\"q\",\"size\":1.5}"),
                new Fixture("km_search", "{\"query\":\"q\",\"size\":null}"),
                new Fixture("km_search", "{\"query\":\"q\",\"page\":null}"),
                new Fixture("km_search", "{\"query\":\"q\",\"documentId\":null}"),
                new Fixture("km_search", "{\"query\":\"q\",\"documentId\":-5}"),
                new Fixture("km_search",
                        "{\"query\":\"q\",\"documentId\":-9223372036854775809}"),
                new Fixture("km_search", "{\"query\":\"q\",\"page\":0}"),
                new Fixture("km_search", "{\"query\":\"q\",\"page\":-1}"),
                new Fixture("km_search", "{\"query\":\"q\",\"page\":2147483648}"),
                new Fixture("km_search", "{\"query\":\"q\",\"documentId\":1}"),
                new Fixture("km_search", "{\"query\":\"q\",\"documentId\":0}"),
                new Fixture("km_search", "{\"query\":\"q\",\"documentId\":\"7\"}"),
                new Fixture("km_search",
                        "{\"query\":\"q\",\"documentId\":9223372036854775808}"),
                new Fixture("km_search", "{\"query\":\"q\",\"size\":20,\"bogus\":1}"),
                // km_ask: defaults, exact enums, blanks, bounds, unknown fields.
                new Fixture("km_ask", "{\"question\":\"q\"}"),
                new Fixture("km_ask",
                        "{\"question\":\"q\",\"retrievalMode\":\"HYBRID_GRAPH\"}"),
                new Fixture("km_ask",
                        "{\"question\":\"q\",\"retrievalMode\":\"hybrid_fts\"}"),
                new Fixture("km_ask", "{\"question\":\"q\",\"retrievalMode\":\"\"}"),
                new Fixture("km_ask", "{\"question\":\"q\",\"retrievalMode\":null}"),
                new Fixture("km_ask", "{\"question\":\"q\",\"retrievalMode\":\"NOPE\"}"),
                new Fixture("km_ask",
                        "{\"question\":\"q\",\"retrievalMode\":\"HYBRID_FTS\",\"maxItems\":5}"),
                new Fixture("km_ask", "{\"question\":\"\u00A0\"}"),
                new Fixture("km_ask", "{\"question\":\"\"}"),
                new Fixture("km_ask", "{\"question\":\"" + question4000 + "\"}"),
                new Fixture("km_ask", "{\"question\":\"" + question4001 + "\"}"),
                new Fixture("km_ask", "{\"question\":\"" + astral4000 + "\"}"),
                new Fixture("km_ask", "{\"question\":null}"),
                // ECMA whitespace boundary characters: rejected on both planes when they
                // are the whole content (NBSP, BOM), accepted on both when outside the
                // ECMA set (FS, NEL) — the runtime and the pattern share one character set.
                new Fixture("km_ask", questionOf(0xFEFF)),
                new Fixture("km_ask", questionOf(0x1C)),
                new Fixture("km_ask", questionOf(0x85)),
                // km_retrieval_inspect: same question rules plus exact mode.
                new Fixture("km_retrieval_inspect", "{\"question\":\"q\"}"),
                new Fixture("km_retrieval_inspect",
                        "{\"question\":\"q\",\"mode\":\"hybrid_graph\"}"),
                new Fixture("km_retrieval_inspect", "{\"question\":\"q\",\"mode\":\"\"}"),
                new Fixture("km_retrieval_inspect",
                        "{\"question\":\"q\",\"mode\":\"REBUILD\"}"),
                // km_source_locator: numeric identity without coercion.
                new Fixture("km_source_locator", "{\"chunkId\":42}"),
                new Fixture("km_source_locator", "{\"chunkId\":1}"),
                new Fixture("km_source_locator", "{\"chunkId\":0}"),
                new Fixture("km_source_locator", "{\"chunkId\":\"42\"}"),
                new Fixture("km_source_locator", "{\"chunkId\":4.5}"),
                new Fixture("km_source_locator", "{\"chunkId\":null}"),
                new Fixture("km_source_locator", "{}"),
                // km_status: no arguments; anything present is rejected.
                new Fixture("km_status", "{}"),
                new Fixture("km_status", "{\"verbose\":true}"),
                // Non-object arguments are rejected by both planes.
                new Fixture("km_status", "[1]"),
                new Fixture("km_status", "null"),
                new Fixture("km_status", "\"km_status\""),
                new Fixture("km_status", "42"),
                new Fixture("km_search", "[{\"query\":\"q\"}]"),
                new Fixture("km_search", "null"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void schemaAndRuntimeAgreeOnStructuralAcceptance(Fixture fixture) {
        JsonNode arguments = parse(fixture.rawArguments());
        assertThat(runtimeValid(fixture.tool(), arguments))
                .as("runtime vs schema verdict for %s %s", fixture.tool(),
                        fixture.rawArguments())
                .isEqualTo(schemaValid(fixture.tool(), arguments));
    }

    /**
     * The single documented fail-closed deviation: JSON numbers with integral value but
     * non-integral representation ({@code 2.0}, {@code 1e2}) are integers per JSON Schema
     * 2020-12, so the schema accepts them — but the runtime contract requires an integral
     * <em>representation</em> (no coercion philosophy, pinned since #335) and rejects
     * them. The deviation direction is strictly fail-closed: the runtime never accepts
     * what the schema rejects. There is no standard schema keyword for representation
     * integrality, so this is asserted explicitly rather than hidden.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("integralFloatFixtures")
    void integralFloatRepresentationIsAFailClosedDeviation(Fixture fixture) {
        JsonNode arguments = parse(fixture.rawArguments());
        assertThat(runtimeValid(fixture.tool(), arguments))
                .as("runtime rejects %s %s", fixture.tool(), fixture.rawArguments())
                .isFalse();
        assertThat(schemaValid(fixture.tool(), arguments))
                .as("schema accepts %s %s per 2020-12 integer semantics", fixture.tool(),
                        fixture.rawArguments())
                .isTrue();
    }

    static Stream<Fixture> integralFloatFixtures() {
        return Stream.of(
                new Fixture("km_search", "{\"query\":\"q\",\"size\":2.0}"),
                new Fixture("km_search", "{\"query\":\"q\",\"size\":1e2}"),
                new Fixture("km_search", "{\"query\":\"q\",\"page\":100.0}"),
                new Fixture("km_source_locator", "{\"chunkId\":42.0}"));
    }

    @Test
    void everyToolHasAcceptAndRejectParityFixtures() {
        for (String tool : McpCapabilityManifest.tools().keySet()) {
            assertThat(schemaFor(tool)).isNotNull();
            assertThat(fixtures().anyMatch(fixture -> fixture.tool().equals(tool)
                            && runtimeValid(tool, parse(fixture.rawArguments()))))
                    .as("parity fixtures accept something for %s", tool)
                    .isTrue();
            assertThat(fixtures().anyMatch(fixture -> fixture.tool().equals(tool)
                            && !runtimeValid(tool, parse(fixture.rawArguments()))))
                    .as("parity fixtures reject something for %s", tool)
                    .isTrue();
        }
    }

    private static boolean schemaValid(String tool, JsonNode arguments) {
        return schemaFor(tool).validate(arguments).isEmpty();
    }

    private static boolean runtimeValid(String tool, JsonNode arguments) {
        try {
            McpCapabilityManifest.contractFor(tool).validate(arguments);
            return true;
        } catch (McpToolInputException invalid) {
            return false;
        }
    }

    private static String questionOf(int... codePoints) {
        try {
            return JSON.writeValueAsString(Map.of("question",
                    new String(codePoints, 0, codePoints.length)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("test JSON is malformed", exception);
        }
    }

    private static JsonNode parse(String raw) {
        try {
            return JSON.readTree(raw);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("test JSON is malformed", exception);
        }
    }
}
