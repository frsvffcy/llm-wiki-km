package org.km.llmwiki.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Loads the active workspace's prompt and allow-listed, non-sensitive analysis settings. */
@Service
public class DocumentAnalysisConfigurationLoader {

    static final Path PROMPT_RELATIVE_PATH = Path.of("prompts", "document-analysis.md");

    private final WorkspaceService workspaceService;
    private final AnalysisSettingsRepository settingsRepository;
    private final DocumentAnalysisPromptTemplate promptTemplate;

    public DocumentAnalysisConfigurationLoader(WorkspaceService workspaceService,
                                               AnalysisSettingsRepository settingsRepository,
                                               ObjectMapper objectMapper) {
        this.workspaceService = workspaceService;
        this.settingsRepository = settingsRepository;
        this.promptTemplate = new DocumentAnalysisPromptTemplate(objectMapper);
    }

    public LoadedDocumentAnalysisConfiguration load(DocumentAnalysisRequest request) {
        AnalysisSettings settings = loadSettings();
        return load(request, settings);
    }

    /**
     * Resolves the non-secret settings before evidence is selected for an analysis request.
     */
    public AnalysisSettings loadSettings() {
        WorkspaceResponse workspace = activeWorkspace();
        return settingsRepository.resolve(workspace.id());
    }

    /**
     * Renders the prompt after the selected evidence has been placed on the request.
     */
    public LoadedDocumentAnalysisConfiguration load(DocumentAnalysisRequest request, AnalysisSettings settings) {
        return new LoadedDocumentAnalysisConfiguration(loadPrompt(request), settings);
    }

    private DocumentAnalysisPrompt loadPrompt(DocumentAnalysisRequest request) {
        WorkspaceResponse workspace = activeWorkspace();
        if (workspace.configPath() == null || workspace.configPath().isBlank()) {
            throw new PromptLoadException(PromptLoadErrorCode.PROMPT_TEMPLATE_INVALID,
                    "Active workspace has no config directory");
        }
        Path configDirectory = Path.of(workspace.configPath()).toAbsolutePath().normalize();
        Path promptPath = configDirectory.resolve(PROMPT_RELATIVE_PATH).normalize();
        if (!promptPath.startsWith(configDirectory)) {
            throw new PromptLoadException(PromptLoadErrorCode.PROMPT_TEMPLATE_INVALID,
                    "Document analysis prompt path escapes workspace config directory");
        }
        if (!Files.isRegularFile(promptPath)) {
            throw new PromptLoadException(PromptLoadErrorCode.PROMPT_TEMPLATE_NOT_FOUND,
                    "Document analysis prompt template was not found: " + promptPath);
        }
        try {
            Path realConfigDirectory = configDirectory.toRealPath();
            Path realPromptPath = promptPath.toRealPath();
            if (!realPromptPath.startsWith(realConfigDirectory)) {
                throw new PromptLoadException(PromptLoadErrorCode.PROMPT_TEMPLATE_INVALID,
                        "Document analysis prompt template escapes workspace config directory");
            }
            DocumentAnalysisPrompt prompt = promptTemplate.render(Files.readString(realPromptPath), realPromptPath, request);
            return prompt;
        } catch (IOException exception) {
            throw new PromptLoadException(PromptLoadErrorCode.PROMPT_TEMPLATE_INVALID,
                    "Document analysis prompt template could not be read", exception);
        }
    }

    private WorkspaceResponse activeWorkspace() {
        return workspaceService.findActiveWithoutValidation()
                .orElseThrow(NoActiveWorkspaceException::new);
    }
}
