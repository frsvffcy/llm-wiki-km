package org.km.llmwiki.wiki;

import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.workspace.WorkspaceRepository;
import org.km.llmwiki.workspace.WorkspaceRow;

import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublishedWikiContentReaderTest {

    private static final long WORKSPACE_ID = 7L;
    private static final String KNOWLEDGE_ID = "wiki-reader";
    private static final String TITLE = "Wiki Reader";
    private static final String LOGICAL_PATH = "vault/concepts/wiki-reader.md";

    private WorkspaceRepository workspaceRepository;
    private WikiPathContract pathContract;
    private PublishedWikiContentReader reader;

    @TempDir
    Path tempDirectory;

    @BeforeEach
    void setUp() {
        workspaceRepository = mock(WorkspaceRepository.class);
        pathContract = mock(WikiPathContract.class);
        reader = new PublishedWikiContentReader(workspaceRepository, pathContract);
    }

    @Test
    void classifiesCanonicalHashDriftAsExpectedValidationFailure() throws Exception {
        Path target = Files.writeString(tempDirectory.resolve("wiki-reader.md"), markdown("drifted"));
        stubCanonicalPath(target);
        StoredPublishedWiki page = page(WikiContentHash.sha256(markdown("trusted")));

        assertThatThrownBy(() -> reader.readSearchableContent(page))
                .isInstanceOf(PublishedWikiValidationException.class)
                .hasMessageContaining("hash differs");
    }

    @Test
    void classifiesFilesystemPermissionFailureAsUnavailable() {
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace()));
        when(pathContract.validateLogicalPath(LOGICAL_PATH)).thenReturn(WikiPageType.CONCEPT);
        when(pathContract.resolveLogicalPath(WikiPageType.CONCEPT, TITLE))
                .thenReturn(LOGICAL_PATH);
        when(pathContract.resolveAndValidateRealPath(Path.of(workspace().vaultPath()), LOGICAL_PATH))
                .thenThrow(new WikiPathValidationException(
                        WikiPathValidationException.Reason.OUTSIDE_VAULT_BOUNDARY,
                        "permission denied", new AccessDeniedException(LOGICAL_PATH)));

        assertThatThrownBy(() -> reader.readSearchableContent(page("0".repeat(64))))
                .isInstanceOf(PublishedWikiUnavailableException.class)
                .hasMessageContaining("could not be resolved")
                .hasRootCauseInstanceOf(AccessDeniedException.class);
    }

    @Test
    void classifiesWorkspaceDatabaseFailureAsUnavailable() {
        when(workspaceRepository.findById(WORKSPACE_ID))
                .thenThrow(new DataAccessException("database unavailable"));

        assertThatThrownBy(() -> reader.readSearchableContent(page("0".repeat(64))))
                .isInstanceOf(PublishedWikiUnavailableException.class)
                .hasMessageContaining("workspace authority")
                .hasRootCauseInstanceOf(DataAccessException.class);
    }

    private void stubCanonicalPath(Path target) {
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace()));
        when(pathContract.validateLogicalPath(LOGICAL_PATH)).thenReturn(WikiPageType.CONCEPT);
        when(pathContract.resolveLogicalPath(WikiPageType.CONCEPT, TITLE))
                .thenReturn(LOGICAL_PATH);
        when(pathContract.resolveAndValidateRealPath(Path.of(workspace().vaultPath()), LOGICAL_PATH))
                .thenReturn(target);
    }

    private StoredPublishedWiki page(String contentHash) {
        return new StoredPublishedWiki(1L, WORKSPACE_ID, KNOWLEDGE_ID, TITLE, "wiki reader",
                WikiPageType.CONCEPT, LOGICAL_PATH, PageStatus.PUBLISHED, contentHash, 1,
                "2026-08-31T00:00:00Z", "2026-08-31T00:00:00Z");
    }

    private WorkspaceRow workspace() {
        return new WorkspaceRow(WORKSPACE_ID, "Reader", tempDirectory.toString(),
                tempDirectory.resolve("inbox").toString(),
                tempDirectory.resolve("archive").toString(),
                tempDirectory.resolve("vault").toString(),
                tempDirectory.resolve("data").toString(),
                tempDirectory.resolve("config").toString(), "ACTIVE",
                "2026-08-31T00:00:00Z", "2026-08-31T00:00:00Z", null);
    }

    private static String markdown(String body) {
        return """
                ---
                id: "wiki-reader"
                title: "Wiki Reader"
                type: "CONCEPT"
                status: "PUBLISHED"
                ---

                # Wiki Reader

                %s
                """.formatted(body);
    }
}
