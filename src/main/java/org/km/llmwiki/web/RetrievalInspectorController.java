package org.km.llmwiki.web;

import org.km.llmwiki.rag.RetrievalInspectionReport;
import org.km.llmwiki.rag.RetrievalInspectorService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Adapter-only read-only Retrieval Inspector endpoint. All retrieval, fusion, authority, and
 * currentness policy lives in the application boundary; this controller validates public input
 * and maps the safe report projection. The inspect flow never mutates canonical state and never
 * calls an answer provider.
 */
@RestController
@RequestMapping("/api/v1/retrieval")
public class RetrievalInspectorController {

    private final RetrievalInspectorService inspectorService;

    public RetrievalInspectorController(RetrievalInspectorService inspectorService) {
        this.inspectorService = inspectorService;
    }

    @GetMapping("/inspect")
    public ApiResponse<RetrievalInspectionResponse> inspect(
            @RequestParam(required = false) String question,
            @RequestParam(required = false) String mode) {
        RetrievalInspectionReport report = inspectorService.inspect(
                RetrievalInspectionMapper.validate(question, mode));
        return new ApiResponse<>(RetrievalInspectionMapper.toResponse(report));
    }
}
