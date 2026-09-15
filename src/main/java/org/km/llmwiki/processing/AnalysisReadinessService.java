package org.km.llmwiki.processing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.km.llmwiki.ai.AnalysisSettings;
import org.km.llmwiki.ai.DocumentAnalysisConfigurationLoader;
import org.km.llmwiki.ai.DocumentAnalysisMetadata;
import org.km.llmwiki.ai.DocumentAnalysisRequest;
import org.km.llmwiki.ai.PromptLoadErrorCode;
import org.km.llmwiki.ai.PromptLoadException;
import org.km.llmwiki.ai.SourceChunkEvidence;
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.stereotype.Service;

/**
 * Read-only probe of Document Analysis prerequisites for the active workspace.
 *
 * <p>Never contacts an LLM provider and never mutates filesystem or canonical state. The
 * prompt is validated by rendering the workspace template with a synthetic request through
 * the production {@link DocumentAnalysisConfigurationLoader}, so readiness reflects the
 * same typed {@link PromptLoadErrorCode} contract that analysis jobs persist.
 */
@Service
public class AnalysisReadinessService {

    private final WorkspaceService workspaceService;
    private final DocumentAnalysisConfigurationLoader configurationLoader;

    public AnalysisReadinessService(WorkspaceService workspaceService,
                                    DocumentAnalysisConfigurationLoader configurationLoader) {
        this.workspaceService = workspaceService;
        this.configurationLoader = configurationLoader;
    }

    public AnalysisReadinessResponse readiness() {
        WorkspaceResponse workspace = workspaceService.findActiveWithoutValidation()
                .orElseThrow(NoActiveWorkspaceException::new);
        boolean workspaceReady = isWorkspaceReady(workspace);

        SettingsProbe settings = probeSettings();
        PromptProbe prompt = probePrompt(settings.settingsOrFallback());

        boolean analysisReady = workspaceReady
                && "READY".equals(prompt.status())
                && settings.valid();
        return new AnalysisReadinessResponse(
                workspace.id(),
                workspaceReady,
                prompt.status(),
                prompt.errorCode(),
                prompt.errorMessage(),
                settings.valid(),
                settings.errorCode(),
                settings.valid() ? settings.settings().provider() : null,
                settings.valid() ? settings.settings().model() : null,
                settings.valid() ? settings.settings().maximumEvidenceChunks() : null,
                analysisReady);
    }

    private static boolean isWorkspaceReady(WorkspaceResponse workspace) {
        if (workspace.rootPath() == null || workspace.rootPath().isBlank()) {
            return false;
        }
        try {
            return Files.isDirectory(Path.of(workspace.rootPath()));
        } catch (Exception ignored) {
            return false;
        }
    }

    private SettingsProbe probeSettings() {
        try {
            AnalysisSettings settings = configurationLoader.loadSettings();
            return new SettingsProbe(true, null, settings);
        } catch (PromptLoadException exception) {
            PromptLoadErrorCode code = exception.errorCode();
            String errorCode = code == null
                    ? PromptLoadErrorCode.ANALYSIS_SETTING_INVALID.name() : code.name();
            return new SettingsProbe(false, errorCode, null);
        }
    }

    private PromptProbe probePrompt(AnalysisSettings settings) {
        try {
            configurationLoader.load(syntheticRequest(), settings);
            return new PromptProbe("READY", null, null);
        } catch (PromptLoadException exception) {
            PromptLoadErrorCode code = exception.errorCode();
            if (code == null) {
                return new PromptProbe("INVALID",
                        PromptLoadErrorCode.PROMPT_TEMPLATE_INVALID.name(),
                        "Document analysis prompt template is invalid");
            }
            return switch (code) {
                case PROMPT_TEMPLATE_NOT_FOUND -> new PromptProbe("MISSING", code.name(),
                        "Document analysis prompt template is missing "
                                + "(config/prompts/document-analysis.md); "
                                + "repair the workspace or restore the file");
                case PROMPT_TEMPLATE_INVALID -> new PromptProbe("INVALID", code.name(),
                        "Document analysis prompt template is invalid");
                case PROMPT_VARIABLE_MISSING -> new PromptProbe("INVALID", code.name(),
                        "Document analysis prompt is missing required variables");
                case ANALYSIS_SETTING_INVALID -> new PromptProbe("INVALID", code.name(),
                        "Document analysis settings are invalid");
            };
        }
    }

    private static DocumentAnalysisRequest syntheticRequest() {
        return new DocumentAnalysisRequest(
                new DocumentAnalysisMetadata(1, "readiness-probe", "text/plain", "readiness"),
                List.of(new SourceChunkEvidence(1, 0, "readiness", "readiness")));
    }

    private record SettingsProbe(boolean valid, String errorCode, AnalysisSettings settings) {
        private AnalysisSettings settingsOrFallback() {
            if (settings != null) {
                return settings;
            }
            return new AnalysisSettings(AnalysisSettings.DEFAULT_PROVIDER,
                    AnalysisSettings.DEFAULT_MODEL,
                    AnalysisSettings.DEFAULT_MAXIMUM_EVIDENCE_CHUNKS);
        }
    }

    private record PromptProbe(String status, String errorCode, String errorMessage) {
    }
}
