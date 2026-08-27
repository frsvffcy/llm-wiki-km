package org.km.llmwiki.source;

import org.km.llmwiki.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentExtractionController {

    private final ExtractedContentService extractedContentService;

    public DocumentExtractionController(ExtractedContentService extractedContentService) {
        this.extractedContentService = extractedContentService;
    }

    @PostMapping("/{documentId}/extract")
    public ApiResponse<ExtractionResponse> extract(@PathVariable long documentId) {
        return new ApiResponse<>(extractedContentService.extract(documentId));
    }

    @GetMapping("/{documentId}/extracted-content")
    public ApiResponse<ExtractedContentPreviewResponse> preview(
            @PathVariable long documentId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return new ApiResponse<>(extractedContentService.preview(documentId, page, size));
    }
}
