package org.km.llmwiki.ai;

import java.nio.file.Path;

/** Traceable, rendered prompt prepared from a workspace-local template. */
public record DocumentAnalysisPrompt(String identifier, String version, String contentHash,
                                     Path sourcePath, String renderedTemplate) {
}
