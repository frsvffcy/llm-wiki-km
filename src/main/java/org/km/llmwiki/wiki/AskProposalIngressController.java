package org.km.llmwiki.wiki;

import org.km.llmwiki.web.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The governed Ask -> Proposal mutation command (#374). Deliberately a separate
 * endpoint from the read-only {@code POST /api/v1/ask}: creating a proposal is an
 * explicit, user-triggered governed action, never an Ask side effect.
 */
@RestController
@RequestMapping("/api/v1/ask/proposals")
public class AskProposalIngressController {

    private final AskProposalIngressService ingressService;

    public AskProposalIngressController(AskProposalIngressService ingressService) {
        this.ingressService = ingressService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AskProposalIngressResponse>> create(
            @RequestBody CreateAskProposalRequest request) {
        AskProposalIngressResponse response = ingressService.createIngress(request);
        HttpStatus status = response.duplicate() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(new ApiResponse<>(response));
    }
}
