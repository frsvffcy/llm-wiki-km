package org.km.llmwiki.wiki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.km.llmwiki.source.DocumentNotFoundException;
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * #569 自動分類建議的唯讀投影邊界（read-only；零寫入、零 retrieval side effect）。
 *
 * <p>建議來源只有已持久化的審核鏈資料：candidate controlled type（經
 * {@link CandidatePageTypeResolver} 推導 unambiguous 的建議 pageType；FACT／PROCEDURE
 * 等 ambiguous 者回 null，不虛構）＋該候選最新非 REJECTED analysis 提案的目前 tags。
 * LLM 只產生過 bounded suggestion；canonical durable tags 仍在 Wiki 側。
 */
@Service
public class KnowledgeTagSuggestionService {

    private final WorkspaceService workspaceService;
    private final KnowledgeTagSuggestionRepository repository;
    private final CandidatePageTypeResolver pageTypeResolver;
    private final ObjectMapper objectMapper;

    public KnowledgeTagSuggestionService(WorkspaceService workspaceService,
                                         KnowledgeTagSuggestionRepository repository,
                                         CandidatePageTypeResolver pageTypeResolver,
                                         ObjectMapper objectMapper) {
        this.workspaceService = workspaceService;
        this.repository = repository;
        this.pageTypeResolver = pageTypeResolver;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public TagSuggestionsResponse suggest(long documentId) {
        if (documentId <= 0) {
            throw new IllegalArgumentException("documentId must be positive");
        }
        WorkspaceResponse workspace = workspaceService.findActiveWithoutValidation()
                .orElseThrow(NoActiveWorkspaceException::new);
        KnowledgeTagSuggestionRepository.DocumentAnchor document = repository
                .findDocumentAnchor(workspace.id(), documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        var analysis = repository.findLatestSucceededAnalysis(documentId);
        if (analysis.isEmpty()) {
            return new TagSuggestionsResponse(documentId, null, null, false,
                    TagSuggestionsResponse.SuggestionFreshness.NO_ANALYSIS, List.of());
        }
        boolean current = !isNewer(document.updatedAt(), analysis.get().createdAt());
        var freshness = current ? TagSuggestionsResponse.SuggestionFreshness.CURRENT
                : TagSuggestionsResponse.SuggestionFreshness.DOCUMENT_CHANGED_AFTER_ANALYSIS;
        Map<Long, ProposalTags> proposalTags = latestProposalTags(workspace.id(), documentId);
        List<TagSuggestionsResponse.CandidateTagSuggestion> suggestions = new ArrayList<>();
        for (var candidate : repository.findCandidates(analysis.get().analysisId())) {
            suggestions.add(toSuggestion(candidate, proposalTags.get(candidate.candidateId())));
        }
        return new TagSuggestionsResponse(documentId, analysis.get().analysisId(), analysis.get().createdAt(),
                current, freshness, List.copyOf(suggestions));
    }

    private Map<Long, ProposalTags> latestProposalTags(long workspaceId, long documentId) {
        Map<Long, ProposalTags> latest = new LinkedHashMap<>();
        for (var row : repository.findAnalysisProposalTags(workspaceId, documentId)) {
            if (latest.containsKey(row.candidateId()) || "REJECTED".equals(row.status())) {
                continue;
            }
            latest.put(row.candidateId(), new ProposalTags(row.proposalId(), readTags(row.normalizedDataJson())));
        }
        return latest;
    }

    private TagSuggestionsResponse.CandidateTagSuggestion toSuggestion(
            KnowledgeTagSuggestionRepository.CandidateRow candidate, ProposalTags proposalTags) {
        String suggestedPageType = null;
        try {
            suggestedPageType = pageTypeResolver.resolve(candidate.type(), null).name();
        } catch (WikiDraftValidationException ambiguous) {
            // FACT／PROCEDURE 等 ambiguous mapping：不虛構建議，留 null 由人類在審核時決定。
            suggestedPageType = null;
        }
        List<String> tags = proposalTags == null ? List.of() : proposalTags.tags();
        var origin = proposalTags == null ? TagSuggestionsResponse.TagsOrigin.NONE
                : TagSuggestionsResponse.TagsOrigin.PROPOSAL;
        return new TagSuggestionsResponse.CandidateTagSuggestion(candidate.candidateId(), candidate.candidateNo(),
                candidate.title(), candidate.type().name(), candidate.confidence(), candidate.summary(),
                candidate.rationale(), suggestedPageType, tags, origin);
    }

    private List<String> readTags(String normalizedDataJson) {
        try {
            JsonNode data = objectMapper.readTree(normalizedDataJson);
            if (data == null || !data.isObject()) {
                return List.of();
            }
            return KnowledgeTagPolicy.normalizedTags(data);
        } catch (Exception exception) {
            // 歷史 normalized data 若無法解析：suggestion 缺席該候選的 tags，不讓讀取路徑炸掉寫入鏈。
            return List.of();
        }
    }

    /** ISO-8601 UTC 字串可直接字典序比較；任一為空 fail-closed 視為已變動（stale）。 */
    private static boolean isNewer(String first, String second) {
        if (first == null || second == null) {
            return true;
        }
        return first.compareTo(second) > 0;
    }

    private record ProposalTags(long proposalId, List<String> tags) {
    }
}
