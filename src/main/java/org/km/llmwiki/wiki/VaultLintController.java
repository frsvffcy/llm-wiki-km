package org.km.llmwiki.wiki;

import org.km.llmwiki.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only Vault Lint triage surface (#383 list/filter/detail, #384 repair
 * capability). Every response is a backend authority projection of
 * {@link VaultLintService#lintActiveWorkspace()}: the Browser renders, filters, and
 * navigates from it but never re-runs lint, never resolves link targets, and never
 * derives repair eligibility here (each finding's capability arrives from
 * {@link VaultRepairService}). No active workspace yields the shared typed
 * {@code NO_ACTIVE_WORKSPACE} failure.
 */
@RestController
@RequestMapping("/api/v1/vault-lint")
public class VaultLintController {

    private final VaultLintService lintService;
    private final VaultRepairService repairService;

    public VaultLintController(VaultLintService lintService, VaultRepairService repairService) {
        this.lintService = lintService;
        this.repairService = repairService;
    }

    @GetMapping("/findings")
    public ApiResponse<VaultLintTriageReport> findings() {
        VaultLintReport report = lintService.lintActiveWorkspace();
        List<VaultLintTriageFinding> views = new ArrayList<>();
        for (VaultLintFinding finding : report.findings()) {
            RepairAssessment assessment =
                    repairService.assessFinding(report.workspaceId(), finding);
            if (assessment instanceof RepairAssessment.Eligible) {
                views.add(new VaultLintTriageFinding(finding, true, null));
            } else {
                RepairAssessment.Ineligible ineligible = (RepairAssessment.Ineligible) assessment;
                views.add(new VaultLintTriageFinding(finding, false, ineligible.reason()));
            }
        }
        return new ApiResponse<>(new VaultLintTriageReport(
                report.workspaceId(), report.checkedPageCount(), views));
    }
}
