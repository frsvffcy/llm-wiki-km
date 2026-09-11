package org.km.llmwiki.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The single JSON-RPC request-id grammar (#350): normal request validation and the
 * transport error-echo path must classify ids identically — String and integral-number
 * ids are VALID and echoable; boolean, object, array, fractional, and explicit-null ids
 * are INVALID and collapse to a null response id; a missing id is ABSENT (notification /
 * undetectable), never a reflected value.
 */
@Tag("unit")
class McpJsonRpcRequestIdTest {

    private static McpJsonRpc.RequestIdClassification classify(String rawRequest) {
        return McpJsonRpc.classifyRequestId(McpJsonRpc.parseValue(rawRequest));
    }

    @Test
    void missingIdIsAbsentWithoutValue() {
        var absent = classify("{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\"}");
        assertThat(absent.type()).isEqualTo(McpJsonRpc.RequestIdType.ABSENT);
        assertThat(absent.value()).isNull();
    }

    @Test
    void nullRequestIsAbsent() {
        var absent = McpJsonRpc.classifyRequestId(null);
        assertThat(absent.type()).isEqualTo(McpJsonRpc.RequestIdType.ABSENT);
        assertThat(absent.value()).isNull();
    }

    @Test
    void stringAndIntegralNumericIdsAreValidAndEchoExactValues() {
        var string = classify("{\"id\":\"req-1\"}");
        assertThat(string.type()).isEqualTo(McpJsonRpc.RequestIdType.VALID);
        assertThat(string.value().asText()).isEqualTo("req-1");
        var integral = classify("{\"id\":9}");
        assertThat(integral.type()).isEqualTo(McpJsonRpc.RequestIdType.VALID);
        assertThat(integral.value().asInt()).isEqualTo(9);
        var big = classify("{\"id\":123456789012345678901234567890}");
        assertThat(big.type()).isEqualTo(McpJsonRpc.RequestIdType.VALID);
    }

    @Test
    void structuredAndBooleanAndFractionalAndExplicitNullIdsAreInvalid() {
        for (String rawId : new String[]{"{\"x\":1}", "[1,2]", "true", "false", "9.5",
                "1.0", "null"}) {
            var classification = classify("{\"id\":" + rawId + "}");
            assertThat(classification.type())
                    .as("id %s must be INVALID", rawId)
                    .isEqualTo(McpJsonRpc.RequestIdType.INVALID);
            assertThat(classification.value()).as("no structured value escapes").isNull();
        }
    }

    @Test
    void echoableRequestIdCollapsesInvalidAndAbsentToNullAndEchoesValidValues() {
        assertThat(McpJsonRpc.echoableRequestId(
                McpJsonRpc.parseValue("{\"id\":{\"secret\":\"x\"}}"))).isNull();
        assertThat(McpJsonRpc.echoableRequestId(
                McpJsonRpc.parseValue("{\"id\":true}"))).isNull();
        assertThat(McpJsonRpc.echoableRequestId(
                McpJsonRpc.parseValue("{\"id\":[1,2]}"))).isNull();
        assertThat(McpJsonRpc.echoableRequestId(
                McpJsonRpc.parseValue("{\"method\":\"tools/list\"}"))).isNull();
        JsonNode echoed = McpJsonRpc.echoableRequestId(
                McpJsonRpc.parseValue("{\"id\":\"req-1\"}"));
        assertThat(echoed.asText()).isEqualTo("req-1");
    }
}
