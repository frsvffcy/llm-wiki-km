package org.km.llmwiki.wiki;

import java.util.List;

/**
 * Deterministic Vault Lint result for one workspace: the checked page count and the
 * sorted, typed findings. No timestamps inside the report — the same canonical snapshot
 * always produces an identical report (#379).
 */
public record VaultLintReport(long workspaceId, int checkedPageCount,
                              List<VaultLintFinding> findings) {

    public VaultLintReport {
        findings = List.copyOf(findings);
    }
}
