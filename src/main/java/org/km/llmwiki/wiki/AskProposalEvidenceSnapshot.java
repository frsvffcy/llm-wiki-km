package org.km.llmwiki.wiki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Versioned durable ASK citation snapshot (#649, Option A).
 *
 * <p>Ingress-time validation already proves currentness, but durable governance
 * (Review → Approve → Draft → Publish) must be able to re-derive the exact
 * identities that were validated. The legacy {@code ASK_CITATIONS_JSON} was a
 * bare array without {@code wikiRevision}, so a saved proposal could not be
 * revalidated after the Wiki page advanced. New writes use
 * {@code {"version":1,"citations":[...]}} with persisted {@code wikiRevision}
 * (and {@code knowledgeId} when resolvable); legacy bare-array rows are parsed
 * as {@code legacy=true} and always fail closed (never invent a revision).
 *
 * <p>Authority: this parser is the single typed boundary for
 * {@code ASK_CITATIONS_JSON}. Malformed content fails closed as
 * {@code ASK_CITATION_INVALID}; provider/model-generated metadata is never
 * treated as currentness authority.
 */
public record AskProposalEvidenceSnapshot(int version, boolean legacy,
                                          List<AskEvidenceCitation> citations) {

    public static final int CURRENT_VERSION = 1;

    public AskProposalEvidenceSnapshot {
        citations = List.copyOf(citations);
    }

    /** Typed citation identity persisted in the durable snapshot. */
    public record AskEvidenceCitation(String evidenceId, String kind, Long sourceChunkId,
                                      String wikiPath, Integer wikiRevision, String knowledgeId) {
    }

    /**
     * Parses persisted {@code ASK_CITATIONS_JSON}. Legacy bare-array rows return
     * {@code legacy=true}; new versioned objects require {@code version=1}.
     * Malformed content throws {@link AskCitationInvalidException} (fail-closed).
     */
    public static AskProposalEvidenceSnapshot parse(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            throw new AskCitationInvalidException(List.of("MALFORMED_ASK_CITATIONS"));
        }
        try {
            JsonNode root = mapper.readTree(json);
            if (root.isArray()) {
                return parseLegacyArray(root);
            }
            if (root.isObject()) {
                return parseVersioned(root);
            }
            throw new AskCitationInvalidException(List.of("MALFORMED_ASK_CITATIONS"));
        } catch (AskCitationInvalidException invalid) {
            throw invalid;
        } catch (Exception malformed) {
            throw new AskCitationInvalidException(List.of("MALFORMED_ASK_CITATIONS"));
        }
    }

    private static AskProposalEvidenceSnapshot parseLegacyArray(JsonNode root) {
        if (!root.isArray() || root.isEmpty() || root.size() > 20) {
            throw new AskCitationInvalidException(List.of("MALFORMED_ASK_CITATIONS"));
        }
        List<AskEvidenceCitation> citations = new ArrayList<>();
        for (JsonNode node : root) {
            citations.add(parseCitationNode(node, true));
        }
        return new AskProposalEvidenceSnapshot(0, true, citations);
    }

    private static AskProposalEvidenceSnapshot parseVersioned(JsonNode root) {
        JsonNode versionNode = root.get("version");
        if (versionNode == null || !versionNode.isIntegralNumber()
                || versionNode.asInt() != CURRENT_VERSION) {
            throw new AskCitationInvalidException(List.of("MALFORMED_ASK_CITATIONS"));
        }
        JsonNode citationsNode = root.get("citations");
        if (citationsNode == null || !citationsNode.isArray()
                || citationsNode.isEmpty() || citationsNode.size() > 20) {
            throw new AskCitationInvalidException(List.of("MALFORMED_ASK_CITATIONS"));
        }
        List<AskEvidenceCitation> citations = new ArrayList<>();
        for (JsonNode node : citationsNode) {
            citations.add(parseCitationNode(node, false));
        }
        return new AskProposalEvidenceSnapshot(CURRENT_VERSION, false, citations);
    }

    private static AskEvidenceCitation parseCitationNode(JsonNode node, boolean legacy) {
        if (node == null || !node.isObject()) {
            throw new AskCitationInvalidException(List.of("MALFORMED_ASK_CITATIONS"));
        }
        JsonNode kindNode = node.get("kind");
        if (kindNode == null || !kindNode.isTextual() || kindNode.asText().isBlank()) {
            throw new AskCitationInvalidException(List.of("MALFORMED_ASK_CITATIONS"));
        }
        String kind = kindNode.asText().toUpperCase();
        String evidenceId = textOrNull(node.get("evidenceId"));
        if ("SOURCE".equals(kind)) {
            JsonNode chunkNode = node.get("sourceChunkId");
            if (chunkNode == null || !chunkNode.isIntegralNumber()
                    || !chunkNode.canConvertToLong() || chunkNode.asLong() <= 0
                    || chunkNode.asLong() > Integer.MAX_VALUE) {
                throw new AskCitationInvalidException(List.of("MALFORMED_ASK_CITATIONS"));
            }
            return new AskEvidenceCitation(evidenceId, "SOURCE", chunkNode.asLong(),
                    null, null, null);
        }
        if ("WIKI".equals(kind)) {
            String path = textOrNull(node.get("wikiPath"));
            if (path == null || path.isBlank() || path.startsWith("/") || path.contains("..")) {
                throw new AskCitationInvalidException(List.of("MALFORMED_ASK_CITATIONS"));
            }
            JsonNode revisionNode = node.get("wikiRevision");
            if (revisionNode == null || revisionNode.isNull()) {
                if (legacy) {
                    // Legacy rows never persisted the validated revision: keep the
                    // citation for fail-closed legacy handling, never invent one.
                    return new AskEvidenceCitation(evidenceId, "WIKI", null,
                            path, null, textOrNull(node.get("knowledgeId")));
                }
                throw new AskCitationInvalidException(List.of("MALFORMED_ASK_CITATIONS"));
            }
            if (!revisionNode.isIntegralNumber() || !revisionNode.canConvertToInt()
                    || revisionNode.asInt() < 1) {
                throw new AskCitationInvalidException(List.of("MALFORMED_ASK_CITATIONS"));
            }
            String knowledgeId = textOrNull(node.get("knowledgeId"));
            if (knowledgeId != null && knowledgeId.isBlank()) {
                throw new AskCitationInvalidException(List.of("MALFORMED_ASK_CITATIONS"));
            }
            return new AskEvidenceCitation(evidenceId, "WIKI", null,
                    path, revisionNode.asInt(), knowledgeId);
        }
        throw new AskCitationInvalidException(List.of("MALFORMED_ASK_CITATIONS"));
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isTextual() ? node.asText() : null;
    }

    /**
     * Builds the versioned snapshot JSON for a newly validated ingress request.
     * {@code validatedRevisions} carries the ask-time current revision per WIKI
     * path; {@code knowledgeIds} carries the resolved knowledgeId when available.
     */
    public static String serializeNew(List<AskCitationInput> citations,
                                      Map<String, Integer> validatedRevisions,
                                      Map<String, String> knowledgeIds,
                                      ObjectMapper mapper) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("version", CURRENT_VERSION);
            ArrayNode array = root.putArray("citations");
            for (AskCitationInput citation : citations) {
                ObjectNode node = array.addObject();
                if (citation.evidenceId() != null) {
                    node.put("evidenceId", citation.evidenceId());
                } else {
                    node.putNull("evidenceId");
                }
                String kind = citation.kind() == null ? null : citation.kind().toUpperCase();
                node.put("kind", kind);
                if ("SOURCE".equals(kind)) {
                    node.put("sourceChunkId", citation.sourceChunkId());
                } else if ("WIKI".equals(kind)) {
                    node.put("wikiPath", citation.wikiPath());
                    Integer revision = validatedRevisions.get(citation.wikiPath());
                    if (revision == null) {
                        throw new IllegalStateException(
                                "WIKI citation missing validated revision for " + citation.wikiPath());
                    }
                    node.put("wikiRevision", revision);
                    String knowledgeId = knowledgeIds.get(citation.wikiPath());
                    if (knowledgeId != null) {
                        node.put("knowledgeId", knowledgeId);
                    }
                }
            }
            return mapper.writeValueAsString(root);
        } catch (AskCitationInvalidException invalid) {
            throw invalid;
        } catch (Exception serializationFailure) {
            throw new IllegalStateException(
                    "ask proposal evidence snapshot serialization failed", serializationFailure);
        }
    }
}
