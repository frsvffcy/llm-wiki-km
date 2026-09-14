package org.km.llmwiki.wiki;

/**
 * One lint finding plus its backend-owned repair capability projection (#384). The
 * nested {@code finding} is the untouched #379 authority type; the Browser renders the
 * repair action if and only if {@code repairEligible} is true and otherwise shows the
 * refusal reason as explanation. The capability is recomputed per read and revalidated
 * at command time — it is never an authorization token.
 */
public record VaultLintTriageFinding(VaultLintFinding finding, boolean repairEligible,
                                     RepairRefusalReason repairRefusalReason) {
}
