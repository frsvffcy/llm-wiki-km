package org.km.llmwiki.ai.ask;

import com.fasterxml.jackson.databind.JsonNode;
import org.km.llmwiki.web.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Thin HTTP adapter; retrieval and answer orchestration remain in AskService. */
@RestController
@RequestMapping("/api/v1/ask")
public class AskController {

    private final AskApplicationService askApplication;

    public AskController(AskApplicationService askApplication) {
        this.askApplication = askApplication;
    }

    @PostMapping
    public ApiResponse<AskApiResponse> ask(@RequestBody JsonNode body) {
        AskApiRequest request = askApplication.parseRequest(body);
        AskResult result = askApplication.execute(request);
        if (result.status() == AskStatus.FAILED) {
            throw askApplication.failure(result);
        }
        return new ApiResponse<>(askApplication.project(result));
    }
}
