package org.km.llmwiki.wiki;

import org.km.llmwiki.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only Vault Lint triage surface (#383). Every response is a backend authority
 * projection of {@link VaultLintService#lintActiveWorkspace()}: the Browser renders,
 * filters, and navigates from it but never re-runs lint, never resolves link targets,
 * and never derives repair eligibility here (repair belongs to #384). No active
 * workspace yields the shared typed {@code NO_ACTIVE_WORKSPACE} failure.
 */
@RestController
@RequestMapping("/api/v1/vault-lint")
public class VaultLintController {

    private final VaultLintService lintService;

    public VaultLintController(VaultLintService lintService) {
        this.lintService = lintService;
    }

    @GetMapping("/findings")
    public ApiResponse<VaultLintReport> findings() {
        return new ApiResponse<>(lintService.lintActiveWorkspace());
    }
}
