package org.km.llmwiki.processing;

import org.km.llmwiki.web.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analysis")
public class DocumentAnalysisController {

    private final DocumentAnalysisJobService service;

    public DocumentAnalysisController(DocumentAnalysisJobService service) {
        this.service = service;
    }

    @PostMapping("/jobs")
    public ResponseEntity<ApiResponse<AnalysisJobCreatedResponse>> start() {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new ApiResponse<>(service.start()));
    }
}
