package org.km.llmwiki.processing;

import org.km.llmwiki.web.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analysis")
public class DocumentAnalysisController {

    private final DocumentAnalysisJobService service;
    private final AnalysisJobQueryService jobQuery;

    public DocumentAnalysisController(DocumentAnalysisJobService service, AnalysisJobQueryService jobQuery) {
        this.service = service;
        this.jobQuery = jobQuery;
    }

    @PostMapping("/jobs")
    public ResponseEntity<ApiResponse<AnalysisJobCreatedResponse>> start() {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new ApiResponse<>(service.start()));
    }

    @GetMapping("/jobs/{jobId}")
    public ApiResponse<ProcessingJobStatusResponse> status(@PathVariable String jobId) {
        return new ApiResponse<>(jobQuery.find(jobId));
    }
}
