package org.km.llmwiki.wiki;

import java.util.List;

/**
 * Triage read projection over one lint run (#383 list/filter/detail, #384 repair
 * capability). Same workspace scope, deterministic finding order, and redaction
 * boundary as the underlying report.
 */
public record VaultLintTriageReport(long workspaceId, int checkedPageCount,
                                    List<VaultLintTriageFinding> findings) {
    public VaultLintTriageReport {
        findings = List.copyOf(findings);
    }
}
