package org.km.llmwiki.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Fixed capability manifest of the read-only local MCP adapter. Tools reuse the existing
 * application contracts; there are no write tools, no config mutation, and no rebuild/repair
 * capability, and clients asking for them get a deterministic typed unavailable.
 */
public final class McpCapabilityManifest {

    public static final String TOOL_STATUS = "km_status";
    public static final String TOOL_SEARCH = "km_search";
    public static final String TOOL_RETRIEVAL_INSPECT = "km_retrieval_inspect";
    public static final String TOOL_SOURCE_LOCATOR = "km_source_locator";
    public static final String TOOL_ASK = "km_ask";

    private static final Map<String, McpToolDescriptor> TOOLS = build();

    private McpCapabilityManifest() {
    }

    public static Map<String, McpToolDescriptor> tools() {
        return TOOLS;
    }

    public static boolean isKnown(String toolName) {
        return TOOLS.containsKey(toolName);
    }

    /**
     * The single input contract authority for one tool: the same object drives the advertised
     * JSON Schema projection and the strict runtime validation.
     */
    public static McpToolInputContract contractFor(String toolName) {
        McpToolDescriptor descriptor = TOOLS.get(toolName);
        return descriptor == null ? null : descriptor.inputContract();
    }

    /**
     * MCP-spec-compliant projection of the same manifest: annotations as the spec's
     * readOnlyHint/destructiveHint/idempotentHint object and inputSchema projected from the
     * tool input contract — the same contract object the runtime validator executes, so the
     * advertised schema and the enforced rules cannot drift apart.
     */
    public static List<Map<String, Object>> specTools() {
        List<Map<String, Object>> tools = new ArrayList<>();
        tools().forEach((key, descriptor) -> {
            Map<String, Object> annotations = new java.util.LinkedHashMap<>();
            annotations.put("readOnlyHint", descriptor.readOnly());
            annotations.put("destructiveHint", descriptor.destructive());
            annotations.put("idempotentHint", descriptor.idempotent());
            Map<String, Object> tool = new java.util.LinkedHashMap<>();
            tool.put("name", descriptor.name());
            tool.put("description", descriptor.description()
                    + (descriptor.activeWorkspaceScoped() ? " [active workspace]"
                    : "") + (descriptor.askProviderEgressPossible()
                    ? " [may invoke provider; egress classification follows the "
                    + "application-owned transparency contract]" : ""));
            tool.put("inputSchema", descriptor.inputContract().jsonSchema());
            tool.put("annotations", annotations);
            tools.add(tool);
        });
        return tools;
    }

    private static Map<String, McpToolDescriptor> build() {
        Map<String, McpToolDescriptor> tools = new TreeMap<>();
        tools.put(TOOL_STATUS, new McpToolDescriptor(
                TOOL_STATUS, "系統狀態", "bounded provider-neutral system/workspace status",
                true, false, true, false, false,
                "no arguments", new McpToolInputContract(TOOL_STATUS, List.of())));
        tools.put(TOOL_SEARCH, new McpToolDescriptor(
                TOOL_SEARCH, "全文/語意搜尋",
                "workspace-scoped search over the current active workspace "
                        + "(reuses the existing search contract; no raw vendor scores)",
                true, false, true, true, false,
                "query ≤ 256 chars; page/size ≤ 200",
                new McpToolInputContract(TOOL_SEARCH, List.of(
                        McpFieldContract.requiredString("query", 256),
                        McpFieldContract.optionalEnum("corpus", searchCorpora(), "WIKI"),
                        McpFieldContract.optionalEnum("pageType", wikiPageTypes(), null),
                        McpFieldContract.optionalLong("documentId", 1L, null),
                        McpFieldContract.optionalInt("page", 0, 1),
                        McpFieldContract.optionalInt("size", 1, 200, 20)))));
        tools.put(TOOL_RETRIEVAL_INSPECT, new McpToolDescriptor(
                TOOL_RETRIEVAL_INSPECT, "檢索觀察",
                "read-only retrieval inspection (safe typed outcomes only; no raw scores)",
                true, false, true, true, false,
                "question ≤ 4000 code points; bounded mode",
                new McpToolInputContract(TOOL_RETRIEVAL_INSPECT, List.of(
                        McpFieldContract.requiredString("question", 4_000),
                        McpFieldContract.optionalEnum("mode", retrievalModes(),
                                "HYBRID_GRAPH")))));
        tools.put(TOOL_SOURCE_LOCATOR, new McpToolDescriptor(
                TOOL_SOURCE_LOCATOR, "來源定位",
                "read-only source-chunk locator with currentness semantics; "
                        + "no filesystem path authority",
                true, false, true, true, false,
                "chunkId (numeric, active workspace)",
                new McpToolInputContract(TOOL_SOURCE_LOCATOR, List.of(
                        McpFieldContract.requiredLong("chunkId", 1L)))));
        tools.put(TOOL_ASK, new McpToolDescriptor(
                TOOL_ASK, "Grounded Ask",
                "grounded ask over the current evidence pipeline; read-only for canonical "
                        + "knowledge, but MAY send bounded provider-bound context to a remote "
                        + "provider (egress classification follows the application-owned "
                        + "transparency contract)",
                true, false, true, true, true,
                "question ≤ 4000 code points",
                new McpToolInputContract(TOOL_ASK, List.of(
                        McpFieldContract.requiredString("question", 4_000),
                        McpFieldContract.optionalEnum("retrievalMode", retrievalModes(),
                                "HYBRID_FTS")))));
        return java.util.Collections.unmodifiableMap(tools);
    }

    private static List<String> searchCorpora() {
        return java.util.Arrays.stream(org.km.llmwiki.search.SearchCorpus.values())
                .map(Enum::name).toList();
    }

    private static List<String> wikiPageTypes() {
        return java.util.Arrays.stream(org.km.llmwiki.wiki.WikiPageType.values())
                .map(Enum::name).toList();
    }

    private static List<String> retrievalModes() {
        return java.util.Arrays.stream(org.km.llmwiki.rag.RetrievalMode.values())
                .map(Enum::name).toList();
    }
}
