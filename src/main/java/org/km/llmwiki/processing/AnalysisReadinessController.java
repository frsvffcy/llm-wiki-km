package org.km.llmwiki.processing;

import org.km.llmwiki.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analysis")
public class AnalysisReadinessController {

    private final AnalysisReadinessService readinessService;

    public AnalysisReadinessController(AnalysisReadinessService readinessService) {
        this.readinessService = readinessService;
    }

    /**
     * Read-only feature readiness for Document Analysis in the active workspace.
     * Reports workspace filesystem readiness separately from prompt/settings readiness;
     * an unconfigured optional analysis capability never changes overall application
     * availability reported by {@code GET /api/v1/system/status}.
     */
    @GetMapping("/readiness")
    public ApiResponse<AnalysisReadinessResponse> readiness() {
        return new ApiResponse<>(readinessService.readiness());
    }
}
