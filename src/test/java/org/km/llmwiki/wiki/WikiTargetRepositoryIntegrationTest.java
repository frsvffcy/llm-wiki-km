package org.km.llmwiki.wiki;

import org.junit.jupiter.api.Test;
import org.km.llmwiki.ai.LlmProposalAction;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.main.web-application-type=none",
        "app.persistence.sqlite.path=target/test-data/wiki-targets-${random.uuid}/knowledge.db"
})
class WikiTargetRepositoryIntegrationTest extends IsolatedIntegrationTest {

    private static final String HASH = "0123456789abcdef".repeat(4);

    @Autowired
    private WikiTargetRepository repository;

    @Autowired
    private WikiTargetResolver resolver;

    @Test
    void resolvesCreateUniquelyThenFailsAfterCanonicalPathIsIndexed() {
        long workspaceId = insertWorkspace("ACTIVE", "target-create");
        WikiDraft create = draft(LlmProposalAction.CREATE, WikiPageType.CONCEPT, "Target Resolution",
                WikiDraftTarget.createNew("vault/concepts/target-resolution.md"));

        WikiTargetSnapshot target = resolver.resolveCreate(workspaceId, create);

        assertThat(target.kind()).isEqualTo(WikiTargetSnapshot.Kind.CREATE_NEW);
        assertThat(target.logicalRelativePath()).isEqualTo("vault/concepts/target-resolution.md");
        insertPage(workspaceId, "target-resolution", "Target Resolution", WikiPageType.CONCEPT,
                PageStatus.PUBLISHED, HASH);
        assertThatThrownBy(() -> resolver.resolveCreate(workspaceId, create))
                .isInstanceOf(WikiTargetResolutionException.class)
                .extracting(error -> ((WikiTargetResolutionException) error).reason())
                .isEqualTo(WikiTargetResolutionException.Reason.CREATE_TARGET_EXISTS);
    }

    @Test
    void resolvesMergeByExactStableIdentityAndNormalizedTitleWithoutMutatingCatalog() {
        long workspaceId = insertWorkspace("ACTIVE", "target-merge");
        insertPage(workspaceId, "deployment-runbook", "Deployment Runbook", WikiPageType.HOWTO,
                PageStatus.PUBLISHED, HASH.toUpperCase());
        int countBefore = countPages();

        WikiTargetSnapshot byIdentity = resolver.resolveMerge(workspaceId,
                draft(LlmProposalAction.MERGE, WikiPageType.HOWTO, "Candidate Notes",
                        WikiDraftTarget.existingReference("wiki:deployment-runbook")));
        WikiTargetSnapshot byTitle = resolver.resolveMerge(workspaceId,
                draft(LlmProposalAction.MERGE, WikiPageType.HOWTO, "Candidate Notes",
                        WikiDraftTarget.existingReference("  DEPLOYMENT   RUNBOOK ")));

        assertThat(byIdentity).isEqualTo(byTitle);
        assertThat(byIdentity.stableIdentifier()).isEqualTo("deployment-runbook");
        assertThat(byIdentity.title()).isEqualTo("Deployment Runbook");
        assertThat(byIdentity.logicalRelativePath()).isEqualTo("vault/howtos/deployment-runbook.md");
        assertThat(byIdentity.currentContentHash()).isEqualTo(HASH);
        assertThat(countPages()).isEqualTo(countBefore);
    }

    @Test
    void exactLookupRetainsAllWorkspaceCandidatesSoResolverFailsClosed() {
        long activeWorkspaceId = insertWorkspace("ACTIVE", "target-active");
        long foreignWorkspaceId = insertWorkspace("INACTIVE", "target-foreign");
        insertPage(activeWorkspaceId, "shared-local", "Shared Page", WikiPageType.CONCEPT,
                PageStatus.PUBLISHED, HASH);
        insertPage(foreignWorkspaceId, "shared-foreign", "Shared Page", WikiPageType.CONCEPT,
                PageStatus.PUBLISHED, HASH);

        assertThat(repository.findExact(WikiTargetReference.parse("Shared Page")))
                .extracting(WikiTargetRecord::workspaceId)
                .containsExactly(activeWorkspaceId, foreignWorkspaceId);
        assertThatThrownBy(() -> resolver.resolveMerge(activeWorkspaceId,
                draft(LlmProposalAction.MERGE, WikiPageType.CONCEPT, "Candidate",
                        WikiDraftTarget.existingReference("Shared Page"))))
                .isInstanceOf(WikiTargetResolutionException.class)
                .extracting(error -> ((WikiTargetResolutionException) error).reason())
                .isEqualTo(WikiTargetResolutionException.Reason.CROSS_WORKSPACE_AMBIGUITY);
    }

    private long insertWorkspace(String status, String name) {
        return insert("""
                INSERT INTO workspace (name, root_path, inbox_path, archive_path, vault_path, data_path, status,
                    created_at, updated_at)
                VALUES (:name, :rootPath, :inboxPath, :archivePath, :vaultPath, :dataPath, :status,
                    '2026-08-29T00:00:00Z', '2026-08-29T00:00:00Z')
                """, "name", name, "rootPath", "/tmp/" + name,
                "inboxPath", "/tmp/" + name + "/inbox", "archivePath", "/tmp/" + name + "/archive",
                "vaultPath", "/tmp/" + name + "/vault", "dataPath", "/tmp/" + name + "/data",
                "status", status);
    }

    private void insertPage(long workspaceId, String stableIdentifier, String title,
                            WikiPageType pageType, PageStatus status, String hash) {
        String path = new WikiPathContract().resolveLogicalPath(pageType, title);
        db().sql("""
                        INSERT INTO knowledge_page (workspace_id, knowledge_id, title, normalized_title, type,
                            markdown_path, status, content_hash, created_at, updated_at)
                        VALUES (:workspaceId, :knowledgeId, :title, :normalizedTitle, :type,
                            :markdownPath, :status, :contentHash,
                            '2026-08-29T00:00:00Z', '2026-08-29T00:00:00Z')
                        """)
                .param("workspaceId", workspaceId)
                .param("knowledgeId", stableIdentifier)
                .param("title", title)
                .param("normalizedTitle", WikiTargetReference.normalizeTitle(title))
                .param("type", pageType.name())
                .param("markdownPath", path)
                .param("status", status.name())
                .param("contentHash", hash)
                .update();
    }

    private long insert(String sql, Object... parameters) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        var statement = db().sql(sql);
        for (int index = 0; index < parameters.length; index += 2) {
            statement = statement.param((String) parameters[index], parameters[index + 1]);
        }
        statement.update(keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new AssertionError("Test insert did not return an id");
        }
        return key.longValue();
    }

    private int countPages() {
        return db().sql("SELECT COUNT(*) FROM knowledge_page").query(Integer.class).single();
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
}
