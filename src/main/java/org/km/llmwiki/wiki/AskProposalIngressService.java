package org.km.llmwiki.wiki;

import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Governed Ask -> Proposal ingress (#374). The Ask surface stays read-only; this
 * service is the separate, explicitly user-triggered mutation command. Backend
 * authority rules: every citation must resolve to a current workspace-scoped source
 * (typed fail-closed otherwise), repeated identical submissions deduplicate through a
 * per-workspace hash, and the created proposal enters the existing
 * Proposal -> Draft -> Human Review -> Publish lifecycle at REVIEW with no auto-publish.
 */
@Service
public class AskProposalIngressService {

    private static final int MAX_QUESTION = 4_000;
    private static final int MAX_ANSWER = 16_000;
    private static final int MAX_CITATIONS = 20;
    private static final int MAX_IDENTIFIER = 128;
    private static final int MAX_TITLE = 120;

    private final WorkspaceService workspaceService;
    private final KnowledgeProposalRepository proposalRepository;
    private final AskProposalIngressRepository ingressRepository;
    private final AskProposalEvidenceCurrentnessValidator evidenceValidator;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public AskProposalIngressService(WorkspaceService workspaceService,
                                     KnowledgeProposalRepository proposalRepository,
                                     AskProposalIngressRepository ingressRepository,
                                     AskProposalEvidenceCurrentnessValidator evidenceValidator,
                                     com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.workspaceService = workspaceService;
        this.proposalRepository = proposalRepository;
        this.ingressRepository = ingressRepository;
        this.evidenceValidator = evidenceValidator;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AskProposalIngressResponse createIngress(CreateAskProposalRequest request) {
        validate(request);
        WorkspaceResponse workspace = activeWorkspace();
        // #649: validate request citations before the dedup shortcut — a stale
        // source or Wiki revision must fail closed instead of returning a
        // "duplicate success" that masks stale evidence.
        List<Long> evidenceChunkIds = new ArrayList<>();
        List<String> citationIdentities = new ArrayList<>();
        java.util.Map<String, Integer> validatedWikiRevisions = new java.util.HashMap<>();
        java.util.Map<String, String> validatedWikiKnowledgeIds = new java.util.HashMap<>();
        validateCitations(workspace.id(), request.citations(), evidenceChunkIds,
                citationIdentities, validatedWikiRevisions, validatedWikiKnowledgeIds);

        String dedupHash = dedupHash(request);
        var existing = ingressRepository.findBySourceDedupHash(workspace.id(), dedupHash);
        if (existing.isPresent()) {
            // Identical hash only proves identical request identities; the
            // durable proposal may already be stale (source status drift, Wiki
            // revision advance, or legacy rows without a persisted revision).
            // Revalidate the persisted snapshot before returning dedup success.
            evidenceValidator.requireCurrent(workspace.id(), existing.get().id());
            return new AskProposalIngressResponse(
                    KnowledgeProposalReviewResponse.from(existing.get()), true);
        }

        String snapshotJson = AskProposalEvidenceSnapshot.serializeNew(request.citations(),
                validatedWikiRevisions, validatedWikiKnowledgeIds, objectMapper);

        long proposalId;
        try {
            proposalId = ingressRepository.insertAskProposal(workspace.id(), request,
                    normalizedData(request, evidenceChunkIds), snapshotJson,
                    evidenceChunkIds, dedupHash);
        } catch (DuplicateAskProposalException lostRace) {
            // Lost the dedup race: the request was already validated current
            // above, but the winner's durable snapshot must still be current.
            var winner = ingressRepository.findBySourceDedupHash(workspace.id(), dedupHash)
                    .orElseThrow(() -> new IllegalStateException(
                            "dedup race winner must exist", lostRace));
            evidenceValidator.requireCurrent(workspace.id(), winner.id());
            return new AskProposalIngressResponse(
                    KnowledgeProposalReviewResponse.from(winner), true);
        }
        return new AskProposalIngressResponse(
                KnowledgeProposalReviewResponse.from(proposalRepository
                        .findReviewableById(workspace.id(), proposalId).orElseThrow()),
                false);
    }

    private void validate(CreateAskProposalRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        requireBounded("question", request.question(), 1, MAX_QUESTION);
        requireBounded("answerText", request.answerText(), 1, MAX_ANSWER);
        requireBounded("provider", request.provider(), 1, MAX_IDENTIFIER);
        requireBounded("model", request.model(), 1, MAX_IDENTIFIER);
        if (request.question().indexOf('\n') >= 0) {
            throw new IllegalArgumentException("question must be a single line");
        }
        if (request.citations() == null || request.citations().isEmpty()
                || request.citations().size() > MAX_CITATIONS) {
            throw new IllegalArgumentException(
                    "citations must contain between 1 and " + MAX_CITATIONS + " entries");
        }
    }

    private void requireBounded(String name, String value, int min, int max) {
        if (value == null || value.strip().length() < min || value.codePointCount(0, value.length()) > max) {
            throw new IllegalArgumentException(
                    name + " must contain between " + min + " and " + max + " code points");
        }
    }

    /**
     * Every citation must resolve to a current workspace-scoped source right now:
     * unknown ids, stale chunks, and foreign-workspace references fail closed with the
     * offending identities listed — nothing is silently downgraded or dropped.
     *
     * <p>#649: validated WIKI revisions and knowledgeIds are collected for the
     * durable versioned snapshot, so later governance boundaries can revalidate
     * the exact identities proven current here.
     */
    private void validateCitations(long workspaceId, List<AskCitationInput> citations,
                                   List<Long> evidenceChunkIds, List<String> citationIdentities,
                                   java.util.Map<String, Integer> validatedWikiRevisions,
                                   java.util.Map<String, String> validatedWikiKnowledgeIds) {
        List<String> invalid = new ArrayList<>();
        for (AskCitationInput citation : citations) {
            if (citation == null || citation.kind() == null) {
                invalid.add("malformed");
                continue;
            }
            String kind = citation.kind().toUpperCase();
            if ("SOURCE".equals(kind)) {
                Long chunkId = citation.sourceChunkId();
                if (chunkId == null || chunkId <= 0 || chunkId > Integer.MAX_VALUE
                        || !evidenceValidator.sourceChunkIsCurrent(workspaceId, chunkId)) {
                    invalid.add("SOURCE_CHUNK:" + chunkId);
                    continue;
                }
                if (!evidenceChunkIds.contains(chunkId)) {
                    evidenceChunkIds.add(chunkId);
                }
                citationIdentities.add("SOURCE_CHUNK:" + chunkId);
            } else if ("WIKI".equals(kind)) {
                String path = citation.wikiPath();
                if (path == null || path.isBlank()
                        || path.startsWith("/") || path.contains("..")) {
                    invalid.add("WIKI:" + path);
                    continue;
                }
                var page = evidenceValidator.findCurrentWiki(
                        workspaceId, path, citation.wikiRevision());
                if (page.isEmpty()) {
                    invalid.add("WIKI:" + path
                            + (citation.wikiRevision() == null ? "" : "@r" + citation.wikiRevision()));
                    continue;
                }
                citationIdentities.add("WIKI:" + path + "@r" + page.get().revision());
                validatedWikiRevisions.put(path, page.get().revision());
                validatedWikiKnowledgeIds.put(path, page.get().knowledgeId());
            } else {
                invalid.add("UNKNOWN_KIND:" + citation.kind());
            }
        }
        if (!invalid.isEmpty()) {
            throw new AskCitationInvalidException(invalid);
        }
        if (evidenceChunkIds.isEmpty()) {
            throw new AskCitationInvalidException(List.of(
                    "no SOURCE_CHUNK citation: proposal evidence requires at least one"));
        }
        citationIdentities.sort(Comparator.naturalOrder());
    }

    private String normalizedData(CreateAskProposalRequest request, List<Long> chunkIds) {
        String title = request.question().strip().replaceAll("\\s+", " ");
        if (title.length() > MAX_TITLE) {
            title = title.substring(0, MAX_TITLE);
        }
        String summary = request.answerText().strip();
        if (summary.length() > 400) {
            summary = summary.substring(0, 400);
        }
        var data = objectMapper.createObjectNode();
        data.put("title", title);
        data.put("pageType", "CONCEPT");
        data.put("summary", summary);
        data.put("tags", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode());
        data.put("aliases", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode());
        var chunkArray = data.putArray("sourceChunkIds");
        chunkIds.forEach(chunkArray::add);
        var sections = data.putArray("sections");
        var answerSection = sections.addObject();
        answerSection.put("heading", "Answer");
        answerSection.put("content", request.answerText());
        try {
            return objectMapper.writeValueAsString(data);
        } catch (java.io.IOException serializationFailure) {
            throw new IllegalStateException(
                    "ask proposal normalized data serialization failed", serializationFailure);
        }
    }

    private String dedupHash(CreateAskProposalRequest request) {
        List<String> identities = new ArrayList<>();
        for (AskCitationInput citation : request.citations()) {
            if (citation == null) continue;
            if ("SOURCE".equalsIgnoreCase(citation.kind()) && citation.sourceChunkId() != null) {
                identities.add("SOURCE_CHUNK:" + citation.sourceChunkId());
            } else if ("WIKI".equalsIgnoreCase(citation.kind())) {
                identities.add("WIKI:" + citation.wikiPath() + "@r" + citation.wikiRevision());
            }
        }
        identities.sort(Comparator.naturalOrder());
        String payload = request.question().strip() + "\n" + request.answerText().strip()
                + "\n" + String.join(",", identities);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private WorkspaceResponse activeWorkspace() {
        return workspaceService.findActiveWithoutValidation()
                .orElseThrow(NoActiveWorkspaceException::new);
    }

    private String text(String value) {
        return value == null ? "" : value;
    }
}
