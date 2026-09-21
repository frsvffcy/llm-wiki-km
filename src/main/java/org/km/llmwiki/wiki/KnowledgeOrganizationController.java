package org.km.llmwiki.wiki;

import org.km.llmwiki.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * #569 知識組織的唯讀建議邊界：自動分類只以 ephemeral suggestion 呈現，
 * 不持久化、不影響 retrieval；人類調整走 proposal tags PATCH。
 */
@RestController
@RequestMapping("/api/v1/organization")
public class KnowledgeOrganizationController {

    private final KnowledgeTagSuggestionService suggestionService;

    public KnowledgeOrganizationController(KnowledgeTagSuggestionService suggestionService) {
        this.suggestionService = suggestionService;
    }

    @GetMapping("/tag-suggestions")
    public ApiResponse<TagSuggestionsResponse> tagSuggestions(@RequestParam long documentId) {
        return new ApiResponse<>(suggestionService.suggest(documentId));
    }
}
