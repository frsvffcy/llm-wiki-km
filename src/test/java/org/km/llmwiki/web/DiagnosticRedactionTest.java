package org.km.llmwiki.web;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract tests for the single application-owned diagnostic redaction policy: every string
 * that crosses a persistence or REST boundary must be bounded, deterministic, and free of
 * paths, secret-like assignments, bearer tokens, backend record identities, and SQL or
 * provider internals. These tests actively falsify redaction with hostile inputs.
 */
@Tag("unit")
class DiagnosticRedactionTest {

    private static final String FALLBACK = "Request validation failed";

    @Test
    void redactsAbsolutePosixPathsWithinAMessage() {
        String sanitized = DiagnosticRedaction.publicMessage(
                "rootPath exists and is not a directory: /Users/toddyeh/workspace/secret-project",
                FALLBACK);
        assertThat(sanitized).isEqualTo("rootPath exists and is not a directory: [REDACTED]");
        assertThat(sanitized).doesNotContain("toddyeh", "secret-project");
    }

    @Test
    void redactsTmpAndVarAbsolutePaths() {
        assertThat(DiagnosticRedaction.publicMessage("locked /tmp/build-321/cache.db", FALLBACK))
                .isEqualTo("locked [REDACTED]");
        assertThat(DiagnosticRedaction.publicMessage("failed /var/folders/xx/y/z db", FALLBACK))
                .isEqualTo("failed [REDACTED] db");
    }

    @Test
    void redactsWindowsAbsolutePaths() {
        assertThat(DiagnosticRedaction.publicMessage("drive C:\\Users\\x\\vault blocked", FALLBACK))
                .isEqualTo("drive [REDACTED] blocked");
    }

    @Test
    void redactsAuthorizationBearerHeader() {
        assertThat(DiagnosticRedaction.publicMessage(
                "provider call failed with Authorization: Bearer abc.def.ghi", FALLBACK))
                .isEqualTo("provider call failed with Authorization=[REDACTED]");
    }

    @Test
    void redactsApiKeyAssignmentsAndOpenAiStyleKeys() {
        assertThat(DiagnosticRedaction.publicMessage(
                "api_key=sk-proj-abcdef123456 rejected", FALLBACK))
                .isEqualTo("api_key=[REDACTED] rejected");
        assertThat(DiagnosticRedaction.publicMessage(
                "call used sk-proj-abcdef123456 rejected", FALLBACK))
                .isEqualTo("call used [REDACTED] rejected");
    }

    @Test
    void redactsTokenLikeAssignments() {
        assertThat(DiagnosticRedaction.publicMessage("token=Zx91aaa.bbb rejected", FALLBACK))
                .isEqualTo("token=[REDACTED] rejected");
    }

    @Test
    void collapsesMessagesCarryingSqlFragmentsToTheFallback() {
        assertThat(DiagnosticRedaction.publicMessage(
                "SELECT title, content FROM knowledge_page WHERE workspace_id = 1", FALLBACK))
                .isEqualTo(FALLBACK);
        assertThat(DiagnosticRedaction.publicMessage(
                "INSERT INTO search_index_identity failed", FALLBACK))
                .isEqualTo(FALLBACK);
        assertThat(DiagnosticRedaction.publicMessage(
                "CREATE TRIGGER fail_rebuild BEFORE INSERT ON search_index_identity", FALLBACK))
                .isEqualTo(FALLBACK);
        assertThat(DiagnosticRedaction.publicMessage(
                "DELETE FROM knowledge_page WHERE id = 42", FALLBACK))
                .isEqualTo(FALLBACK);
        assertThat(DiagnosticRedaction.publicMessage(
                "update knowledge_page set content = 'x' where id = 42", FALLBACK))
                .isEqualTo(FALLBACK);
        assertThat(DiagnosticRedaction.publicMessage(
                "DROP TRIGGER fail_rebuild failed", FALLBACK))
                .isEqualTo(FALLBACK);
        assertThat(DiagnosticRedaction.publicMessage(
                "jdbc:sqlite:/tmp/x.db is locked", FALLBACK))
                .isEqualTo(FALLBACK);
    }

    @Test
    void redactsBearerJwtStyleTokensWithoutAssignmentSyntax() {
        assertThat(DiagnosticRedaction.publicMessage(
                "Invalid token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghij refused",
                FALLBACK))
                .isEqualTo("Invalid token [REDACTED] refused");
    }

    @Test
    void redactsRecordIdentitiesWithAndWithoutTheHashPrefix() {
        assertThat(DiagnosticRedaction.publicMessage("record RID #12:0 conflicts with #19:42",
                FALLBACK))
                .isEqualTo("record [REDACTED] conflicts with [REDACTED]");
        assertThat(DiagnosticRedaction.publicMessage("stale record #3:71 blocked", FALLBACK))
                .isEqualTo("stale record [REDACTED] blocked");
        assertThat(DiagnosticRedaction.publicMessage("stale record RID 3:71 blocked", FALLBACK))
                .isEqualTo("stale record [REDACTED] blocked");
    }

    @Test
    void redactsWellKnownPathRootsWithoutASegment() {
        assertThat(DiagnosticRedaction.publicMessage("locked /tmp and /Users", FALLBACK))
                .isEqualTo("locked [REDACTED] and [REDACTED]");
    }

    @Test
    void redactsUrlEncodedPaths() {
        assertThat(DiagnosticRedaction.publicMessage(
                "denied %2FUsers%2Fx%2Fvault blocked", FALLBACK))
                .isEqualTo("denied [REDACTED] blocked");
    }

    @Test
    void collapsesMessagesCarryingProviderRawResponseFragmentsToTheFallback() {
        assertThat(DiagnosticRedaction.publicMessage(
                "provider replied 400 {\"error\":{\"message\":\"Bad Gateway upstream\"}}",
                FALLBACK))
                .isEqualTo(FALLBACK);
        assertThat(DiagnosticRedaction.publicMessage(
                "at org.km.llmwiki.rag.FusedEvidenceService.fuse(FusedEvidenceService.java:1)",
                FALLBACK))
                .isEqualTo(FALLBACK);
    }

    @Test
    void neverIncludesNestedCauseContentEvenWhenTheTopLevelMessageIsSafe() {
        RuntimeException nested = new IllegalStateException(
                "Authorization: Bearer nested-secret-token");
        RuntimeException failure = new IllegalStateException("Authority revalidation rejected",
                nested);
        assertThat(DiagnosticRedaction.persistedFailure("fts_rebuild_failed", failure, FALLBACK))
                .isEqualTo("fts_rebuild_failed: Authority revalidation rejected")
                .doesNotContain("nested-secret-token", "Bearer", "Authorization");
    }

    @Test
    void persistedFailureUsesStableReasonAndNeverTheExceptionClassName() {
        class VaultHashMismatchException extends RuntimeException {
            VaultHashMismatchException(String message) {
                super(message);
            }
        }
        String detail = DiagnosticRedaction.persistedFailure("fts_rebuild_failed",
                new VaultHashMismatchException("Vault Markdown hash differs from content hash"),
                FALLBACK);
        assertThat(detail).isEqualTo("fts_rebuild_failed: Vault Markdown hash differs from content hash");
        assertThat(detail).doesNotContain("VaultHashMismatchException", "Exception");
    }

    @Test
    void fallsBackWhenTheMessageIsMissingOrBlank() {
        assertThat(DiagnosticRedaction.persistedFailure("fts_rebuild_failed",
                new IllegalStateException(), FALLBACK))
                .isEqualTo("fts_rebuild_failed: " + FALLBACK);
        assertThat(DiagnosticRedaction.publicMessage("   ", FALLBACK)).isEqualTo(FALLBACK);
        assertThat(DiagnosticRedaction.publicMessage(null, FALLBACK)).isEqualTo(FALLBACK);
    }

    @Test
    void boundsLengthAndStaysDeterministic() {
        String hostile = "rejection for /Users/x/vault with token=abcdef123456 "
                + "x".repeat(500);
        String first = DiagnosticRedaction.publicMessage(hostile, FALLBACK);
        String second = DiagnosticRedaction.publicMessage(hostile, FALLBACK);
        assertThat(first).isEqualTo(second);
        assertThat(first.length()).isLessThanOrEqualTo(DiagnosticRedaction.MAX_LENGTH);
        assertThat(first).doesNotContain("/Users/x", "abcdef123456");
    }

    @Test
    void isLocaleIndependentForCjkContent() {
        String sanitized = DiagnosticRedaction.publicMessage(
                "找不到 Source Chunk：42 rootPath /Users/x/y", FALLBACK);
        assertThat(sanitized).isEqualTo("找不到 Source Chunk：42 rootPath [REDACTED]");
        String repeated = DiagnosticRedaction.publicMessage(
                "找不到 Source Chunk：42 rootPath /Users/x/y", FALLBACK);
        assertThat(repeated).isEqualTo(sanitized);
    }

    @Test
    void keepsSafeResourceIdentifiersAndValidationWording() {
        assertThat(DiagnosticRedaction.publicMessage("Document not found: 42", FALLBACK))
                .isEqualTo("Document not found: 42");
        assertThat(DiagnosticRedaction.publicMessage("size must be between 1 and 200", FALLBACK))
                .isEqualTo("size must be between 1 and 200");
        assertThat(DiagnosticRedaction.publicMessage("Unknown status filter: DRAFT", FALLBACK))
                .isEqualTo("Unknown status filter: DRAFT");
    }

    @Test
    void rejectsNonPositiveBounds() {
        assertThatThrownBy(() -> DiagnosticRedaction.sanitize("x", FALLBACK, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
