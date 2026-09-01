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

    private final AskService askService;

    public AskController(AskService askService) {
        this.askService = askService;
    }

    @PostMapping
    public ApiResponse<AskApiResponse> ask(@RequestBody JsonNode body) {
        AskApiRequest request = AskApiRequest.fromJson(body);
        AskResult result = askService.ask(request.toApplicationRequest());
        if (result.status() == AskStatus.FAILED) {
            throw new AskApiException(result.failure()
                    .orElseThrow(() -> new IllegalStateException("failed Ask result has no failure"))
                    .type());
        }
        return new ApiResponse<>(AskApiResponse.from(result));
    }
}
