package org.km.llmwiki.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentAnalysisConfigurationLoaderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsOnlyTheActiveWorkspacesPromptTemplate() throws Exception {
        Path workspaceA = Files.createDirectories(temporaryDirectory.resolve("workspace-a/config/prompts"));
        Path workspaceB = Files.createDirectories(temporaryDirectory.resolve("workspace-b/config/prompts"));
        Files.writeString(workspaceA.resolve("document-analysis.md"), """
                <!-- prompt-version: workspace-a -->
                {{document.metadata}}
                {{evidence}}
                """);
        Files.writeString(workspaceB.resolve("document-analysis.md"), """
                <!-- prompt-version: workspace-b -->
                {{document.metadata}}
                {{evidence}}
                """);

        WorkspaceService workspaceService = mock(WorkspaceService.class);
        AnalysisSettingsRepository settings = mock(AnalysisSettingsRepository.class);
        when(workspaceService.findActiveWithoutValidation()).thenReturn(Optional.of(workspace(11, workspaceA.getParent())));
        when(settings.resolve(11)).thenReturn(new AnalysisSettings("local", "test-model", 10));

        LoadedDocumentAnalysisConfiguration loaded = new DocumentAnalysisConfigurationLoader(
                workspaceService, settings, new ObjectMapper()).load(request());

        assertThat(loaded.prompt().identifier()).isEqualTo("document-analysis@workspace-a");
        assertThat(loaded.prompt().sourcePath()).startsWith(workspaceA);
        assertThat(loaded.settings()).isEqualTo(new AnalysisSettings("local", "test-model", 10));
    }

    @Test
    void failsWithAStableCodeWhenTheActiveWorkspaceHasNoPrompt() {
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        AnalysisSettingsRepository settings = mock(AnalysisSettingsRepository.class);
        Path configDirectory = temporaryDirectory.resolve("config");
        when(workspaceService.findActiveWithoutValidation()).thenReturn(Optional.of(workspace(11, configDirectory)));

        assertThatThrownBy(() -> new DocumentAnalysisConfigurationLoader(workspaceService, settings, new ObjectMapper())
                .load(request()))
                .isInstanceOf(PromptLoadException.class)
                .extracting(exception -> ((PromptLoadException) exception).errorCode())
                .isEqualTo(PromptLoadErrorCode.PROMPT_TEMPLATE_NOT_FOUND);
    }

    private static WorkspaceResponse workspace(long id, Path configDirectory) {
        return new WorkspaceResponse(id, "test", configDirectory.getParent().toString(), "inbox", "archive", "vault",
                "data", configDirectory.toString(), "ACTIVE", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z");
    }

    private static DocumentAnalysisRequest request() {
        return new DocumentAnalysisRequest(
                new DocumentAnalysisMetadata(7, "notes.md", "text/markdown", "document-hash"),
                List.of(new SourceChunkEvidence(41, 0, "chunk-hash", "內容")));
    }
}
