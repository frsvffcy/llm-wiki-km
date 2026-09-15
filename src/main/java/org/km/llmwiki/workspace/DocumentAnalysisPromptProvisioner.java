package org.km.llmwiki.workspace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.springframework.stereotype.Component;

/**
 * Provisions the versioned default Document Analysis prompt for a workspace.
 *
 * <p>Authority for {@code config/prompts/document-analysis.md} bootstrap (issue #448, option A1):
 * a fresh workspace receives the bundled default template; an existing user prompt is never
 * silently overwritten. Recovery is deterministic: explicit repair provisions the default only
 * when the prompt file is absent. The prompt version and hash semantics stay with
 * {@code ai.DocumentAnalysisPromptTemplate}; this component only copies bytes.
 */
@Component
public class DocumentAnalysisPromptProvisioner {

    static final String CLASSPATH_RESOURCE = "prompts/document-analysis.md";
    static final Path PROMPT_RELATIVE_PATH = Path.of("config", "prompts", "document-analysis.md");

    /**
     * Copies the bundled default prompt when the workspace has no regular prompt file.
     *
     * @return {@code true} when the default was provisioned, {@code false} when a prompt
     *         already exists and was left untouched.
     */
    public boolean provisionIfMissing(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("workspace root must not be null");
        }
        Path target = root.resolve(PROMPT_RELATIVE_PATH);
        if (Files.isRegularFile(target)) {
            return false;
        }
        if (Files.exists(target)) {
            return false;
        }
        String defaultPrompt = loadDefaultPrompt();
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, defaultPrompt, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return true;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not provision default document analysis prompt",
                    exception);
        }
    }

    String loadDefaultPrompt() {
        try (InputStream stream = DocumentAnalysisPromptProvisioner.class
                .getClassLoader().getResourceAsStream(CLASSPATH_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "Default document analysis prompt is unavailable");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Default document analysis prompt could not be read", exception);
        }
    }
}
