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

@RestController
@RequestMapping("/api/v1/search/index")
public class SearchIndexController {

    private final FtsRebuildService rebuildService;
    private final SearchHealthService healthService;

    public SearchIndexController(FtsRebuildService rebuildService, SearchHealthService healthService) {
        this.rebuildService = rebuildService;
        this.healthService = healthService;
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
}
