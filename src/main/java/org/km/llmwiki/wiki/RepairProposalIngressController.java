package org.km.llmwiki.wiki;

import org.km.llmwiki.web.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The governed repair mutation command (#384). Deliberately separate from the read-only
 * triage surface: creating a repair proposal is an explicit, user-triggered governed
 * action that revalidates finding, currentness, and eligibility at command time — never
 * a lint side effect and never a vault write.
 */
@RestController
@RequestMapping("/api/v1/repair/proposals")
public class RepairProposalIngressController {

    private final RepairProposalIngressService ingressService;

    public RepairProposalIngressController(RepairProposalIngressService ingressService) {
        this.ingressService = ingressService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<RepairProposalIngressResponse>> create(
            @RequestBody CreateRepairProposalRequest request) {
        RepairProposalIngressResponse response = ingressService.createIngress(request);
        HttpStatus status = response.duplicate() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(new ApiResponse<>(response));
    }
}
