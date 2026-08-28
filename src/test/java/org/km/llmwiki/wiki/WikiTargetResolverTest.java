package org.km.llmwiki.wiki;

import org.junit.jupiter.api.Test;
import org.km.llmwiki.ai.LlmProposalAction;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WikiTargetResolverTest {

    private static final long ACTIVE_WORKSPACE_ID = 7L;
    private static final String HASH = "A".repeat(64);

    private final WikiPathContract pathContract = new WikiPathContract();
    private final FakeCatalog catalog = new FakeCatalog();
    private final WikiTargetResolver resolver = new WikiTargetResolver(pathContract::resolveLogicalPath, catalog);

    @Test
    void resolvesCreateToTheOnlyCanonicalNonExistingActiveWorkspaceTarget() {
        WikiTargetSnapshot target = resolver.resolveCreate(ACTIVE_WORKSPACE_ID,
                draft(LlmProposalAction.CREATE, WikiPageType.CONCEPT, "Spring Boot",
                        WikiDraftTarget.createNew("vault/concepts/spring-boot.md")));

        assertThat(target).isEqualTo(WikiTargetSnapshot.createNew(
                "Spring Boot", WikiPageType.CONCEPT, "vault/concepts/spring-boot.md"));
        assertThat(catalog.requestedWorkspaceId).isEqualTo(ACTIVE_WORKSPACE_ID);
        assertThat(catalog.requestedPath).isEqualTo("vault/concepts/spring-boot.md");
    }

    @Test
    void failsCreateWhenCanonicalTargetExistsOrDraftPathIsNotAuthoritative() {
        catalog.existingPath = true;
        assertFailure(() -> resolver.resolveCreate(ACTIVE_WORKSPACE_ID,
                        draft(LlmProposalAction.CREATE, WikiPageType.CONCEPT, "Spring Boot",
                                WikiDraftTarget.createNew("vault/concepts/spring-boot.md"))),
                WikiTargetResolutionException.Reason.CREATE_TARGET_EXISTS);

        catalog.existingPath = false;
        assertFailure(() -> resolver.resolveCreate(ACTIVE_WORKSPACE_ID,
                        draft(LlmProposalAction.CREATE, WikiPageType.CONCEPT, "Spring Boot",
                                WikiDraftTarget.createNew("vault/concepts/client-path.md"))),
                WikiTargetResolutionException.Reason.CANONICAL_INVARIANT_VIOLATION);
    }

    @Test
    void resolvesOnePublishedExistingTargetWithStableIdentityPathAndNormalizedHash() {
        catalog.candidates.add(target(ACTIVE_WORKSPACE_ID, "wiki-42", "Deployment Runbook",
                WikiPageType.HOWTO, PageStatus.PUBLISHED, HASH));

        WikiTargetSnapshot target = resolver.resolveMerge(ACTIVE_WORKSPACE_ID,
                draft(LlmProposalAction.MERGE, WikiPageType.HOWTO, "New Deployment Notes",
                        WikiDraftTarget.existingReference("wiki:wiki-42")));

        assertThat(target.kind()).isEqualTo(WikiTargetSnapshot.Kind.EXISTING);
        assertThat(target.stableIdentifier()).isEqualTo("wiki-42");
        assertThat(target.title()).isEqualTo("Deployment Runbook");
        assertThat(target.pageType()).isEqualTo(WikiPageType.HOWTO);
        assertThat(target.logicalRelativePath()).isEqualTo("vault/howtos/deployment-runbook.md");
        assertThat(target.currentContentHash()).isEqualTo(HASH.toLowerCase());
        assertThat(catalog.requestedReference).isEqualTo(
                new WikiTargetReference(WikiTargetReference.Kind.STABLE_IDENTIFIER, "wiki-42"));
    }

    @Test
    void failsClosedForMissingMultipleOrCrossWorkspaceCandidates() {
        WikiDraft merge = draft(LlmProposalAction.MERGE, WikiPageType.CONCEPT, "Candidate",
                WikiDraftTarget.existingReference("Shared Title"));
        assertFailure(() -> resolver.resolveMerge(ACTIVE_WORKSPACE_ID, merge),
                WikiTargetResolutionException.Reason.TARGET_NOT_FOUND);

        catalog.candidates.add(target(ACTIVE_WORKSPACE_ID, "one", "Shared Title",
                WikiPageType.CONCEPT, PageStatus.PUBLISHED, HASH));
        catalog.candidates.add(target(ACTIVE_WORKSPACE_ID, "two", "Shared Title",
                WikiPageType.TECHNOLOGY, PageStatus.PUBLISHED, HASH));
        assertFailure(() -> resolver.resolveMerge(ACTIVE_WORKSPACE_ID, merge),
                WikiTargetResolutionException.Reason.TARGET_AMBIGUOUS);

        catalog.candidates.clear();
        catalog.candidates.add(target(99L, "foreign", "Shared Title",
                WikiPageType.CONCEPT, PageStatus.PUBLISHED, HASH));
        assertFailure(() -> resolver.resolveMerge(ACTIVE_WORKSPACE_ID, merge),
                WikiTargetResolutionException.Reason.CROSS_WORKSPACE_AMBIGUITY);

        catalog.candidates.add(target(ACTIVE_WORKSPACE_ID, "local", "Shared Title",
                WikiPageType.CONCEPT, PageStatus.PUBLISHED, HASH));
        assertFailure(() -> resolver.resolveMerge(ACTIVE_WORKSPACE_ID, merge),
                WikiTargetResolutionException.Reason.CROSS_WORKSPACE_AMBIGUITY);
    }

    @Test
    void rejectsInvisiblePageTypeMismatchAndNonCanonicalIndexedTarget() {
        WikiDraft merge = draft(LlmProposalAction.MERGE, WikiPageType.CONCEPT, "Candidate",
                WikiDraftTarget.existingReference("wiki:target"));
        catalog.candidates.add(target(ACTIVE_WORKSPACE_ID, "target", "Target",
                WikiPageType.CONCEPT, PageStatus.DELETED, HASH));
        assertFailure(() -> resolver.resolveMerge(ACTIVE_WORKSPACE_ID, merge),
                WikiTargetResolutionException.Reason.TARGET_NOT_VISIBLE);

        catalog.candidates.clear();
        catalog.candidates.add(target(ACTIVE_WORKSPACE_ID, "target", "Target",
                WikiPageType.TECHNOLOGY, PageStatus.PUBLISHED, HASH));
        assertFailure(() -> resolver.resolveMerge(ACTIVE_WORKSPACE_ID, merge),
                WikiTargetResolutionException.Reason.TARGET_PAGE_TYPE_MISMATCH);

        assertThatThrownBy(() -> new WikiTargetRecord(ACTIVE_WORKSPACE_ID, "target", "Target",
                WikiPageType.CONCEPT, "vault/concepts/not-target.md", PageStatus.PUBLISHED, HASH))
                .isInstanceOf(WikiTargetResolutionException.class)
                .extracting(error -> ((WikiTargetResolutionException) error).reason())
                .isEqualTo(WikiTargetResolutionException.Reason.CANONICAL_INVARIANT_VIOLATION);
    }

    private static WikiTargetRecord target(long workspaceId, String stableIdentifier, String title,
                                           WikiPageType pageType, PageStatus status, String hash) {
        String path = new WikiPathContract().resolveLogicalPath(pageType, title);
        return new WikiTargetRecord(workspaceId, stableIdentifier, title, pageType, path, status, hash);
    }

    private static WikiDraft draft(LlmProposalAction action, WikiPageType pageType, String title,
                                   WikiDraftTarget target) {
        List<Long> sourceChunkIds = List.of(11L);
        return new WikiDraft(9L, action, pageType, title, target,
                new WikiDraftFrontmatter(title, pageType, "Summary", List.of(), List.of(),
                        List.of(3L), sourceChunkIds),
                List.of(new WikiDraftSection("Summary", "Content")), List.of(),
                List.of(new WikiDraftEvidence(11L, 1, null, null, null, "Evidence")), sourceChunkIds,
                new WikiDraftContentContract("wiki-draft/v1", List.of("title"),
                        List.of("Summary"), true, true));
    }

    private static void assertFailure(Runnable operation, WikiTargetResolutionException.Reason reason) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(WikiTargetResolutionException.class)
                .extracting(error -> ((WikiTargetResolutionException) error).reason())
                .isEqualTo(reason);
    }

    private static final class FakeCatalog implements WikiTargetCatalog {
        private boolean existingPath;
        private long requestedWorkspaceId;
        private String requestedPath;
        private WikiTargetReference requestedReference;
        private final List<WikiTargetRecord> candidates = new ArrayList<>();

        @Override
        public boolean existsAtCanonicalPath(long workspaceId, String logicalRelativePath) {
            requestedWorkspaceId = workspaceId;
            requestedPath = logicalRelativePath;
            return existingPath;
        }

        @Override
        public List<WikiTargetRecord> findExact(WikiTargetReference reference) {
            requestedReference = reference;
            return List.copyOf(candidates);
        }
    }
}
