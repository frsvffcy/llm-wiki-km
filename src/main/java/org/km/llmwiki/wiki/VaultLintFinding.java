package org.km.llmwiki.wiki;

/**
 * One deterministic, read-only Vault health finding (#379). Findings are typed and
 * bounded: they never carry filesystem paths, raw exceptions, or free-form logs, and
 * they are ordered deterministically so the same canonical snapshot always yields the
 * same findings set in the same order.
 *
 * @param code       stable finding type
 * @param category   canonical content vs reference vs metadata vs derived projection
 * @param severity   ERROR blocks nothing but marks a structural defect; WARNING/INFO are health observations
 * @param knowledgeId canonical identity of the page the finding is about
 * @param logicalPath workspace-relative vault path (never a host filesystem path)
 * @param detail     bounded, human-readable, application-owned description
 */
public record VaultLintFinding(Code code, Category category, Severity severity,
                               String knowledgeId, String logicalPath, String detail)
        implements Comparable<VaultLintFinding> {

    public enum Code {
        BROKEN_INTERNAL_LINK,
        ORPHAN_PAGE,
        CANONICAL_CONTENT_INVALID,
        CANONICAL_CONTENT_UNREADABLE,
        DUPLICATE_IDENTITY,
        DANGLING_PROVENANCE
    }

    public enum Category {
        CANONICAL_CONTENT,
        REFERENCE,
        METADATA,
        DERIVED_PROJECTION
    }

    public enum Severity {
        ERROR,
        WARNING,
        INFO
    }

    public VaultLintFinding {
        if (code == null || category == null || severity == null) {
            throw new IllegalArgumentException("finding code/category/severity are required");
        }
        if (knowledgeId == null || knowledgeId.isBlank()) {
            throw new IllegalArgumentException("finding knowledgeId is required");
        }
        if (detail == null || detail.isBlank() || detail.length() > 256) {
            throw new IllegalArgumentException("finding detail must be 1..256 characters");
        }
        logicalPath = logicalPath == null ? "" : logicalPath;
    }

    @Override
    public int compareTo(VaultLintFinding other) {
        int byCode = code.compareTo(other.code);
        if (byCode != 0) return byCode;
        int byId = knowledgeId.compareTo(other.knowledgeId);
        if (byId != 0) return byId;
        return detail.compareTo(other.detail);
    }
}
