package org.km.llmwiki.search;

import org.km.llmwiki.web.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.km.llmwiki.search.embedding.EmbeddingProjectionJobService;
import org.km.llmwiki.search.embedding.EmbeddingProjectionJobQueryService;
import org.km.llmwiki.search.embedding.EmbeddingProjectionJobStatusResponse;
import org.km.llmwiki.search.embedding.EmbeddingProjectionReadiness;
import org.km.llmwiki.search.embedding.EmbeddingProjectionReadinessRepository;
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceService;

@RestController
@RequestMapping("/api/v1/search/index")
public class SearchIndexController {

    private final FtsRebuildService rebuildService;
    private final SearchHealthService healthService;
    private final EmbeddingProjectionJobService embeddingJobs;
    private final EmbeddingProjectionReadinessRepository embeddingReadiness;
    private final EmbeddingProjectionJobQueryService embeddingJobQuery;
    private final WorkspaceService workspaceService;

    public SearchIndexController(FtsRebuildService rebuildService, SearchHealthService healthService,
                                 EmbeddingProjectionJobService embeddingJobs,
                                 EmbeddingProjectionReadinessRepository embeddingReadiness,
                                 EmbeddingProjectionJobQueryService embeddingJobQuery,
                                 WorkspaceService workspaceService) {
        this.rebuildService = rebuildService;
        this.healthService = healthService;
        this.embeddingJobs = embeddingJobs;
        this.embeddingReadiness = embeddingReadiness;
        this.embeddingJobQuery = embeddingJobQuery;
        this.workspaceService = workspaceService;
    }

    @PostMapping("/rebuild")
    public ResponseEntity<ApiResponse<FtsRebuildCreatedResponse>> rebuild(
            @RequestBody(required = false) FtsRebuildRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new ApiResponse<>(rebuildService.start(request)));
    }

    @GetMapping("/health")
    public ApiResponse<SearchHealthResult> health(@RequestParam(required = false) String corpus) {
        return new ApiResponse<>(healthService.check(corpus));
    }

    @PostMapping("/embedding/rebuild")
    public ResponseEntity<ApiResponse<EmbeddingProjectionJobService.EmbeddingProjectionJobCreatedResponse>> embeddingRebuild(
            @RequestParam(required = false) String corpus) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new ApiResponse<>(
                embeddingJobs.startRebuild(SearchCorpus.from(corpus))));
    }

    @GetMapping("/embedding/rebuild/{jobId}")
    public ApiResponse<EmbeddingProjectionJobStatusResponse> embeddingRebuildStatus(
            @org.springframework.web.bind.annotation.PathVariable String jobId) {
        return new ApiResponse<>(embeddingJobQuery.find(jobId));
    }

    @GetMapping("/embedding/readiness")
    public ApiResponse<java.util.List<EmbeddingProjectionReadiness>> embeddingReadiness() {
        long workspaceId = workspaceService.findActiveWithoutValidation().orElseThrow(NoActiveWorkspaceException::new).id();
        return new ApiResponse<>(embeddingReadiness.findAll(workspaceId));
    }
}
