package org.km.llmwiki.system;

import org.km.llmwiki.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only deployment profile surface for operators (#418 §A/I).
 *
 * <p>Reports the current explicit profile and its support state as an
 * operator-safe projection. The endpoint lives under {@code /api/v1} so the
 * owner boundary (#417) guards it exactly like every other system surface; it
 * performs no mutation and exposes no paths, secrets, or provider material.
 */
@RestController
@RequestMapping("/api/v1/system")
public class DeploymentReadinessController {

    private final DeploymentReadinessService readinessService;

    public DeploymentReadinessController(DeploymentReadinessService readinessService) {
        this.readinessService = readinessService;
    }

    @GetMapping("/deployment")
    public ApiResponse<DeploymentReadiness> deployment() {
        return new ApiResponse<>(readinessService.current());
    }
}
