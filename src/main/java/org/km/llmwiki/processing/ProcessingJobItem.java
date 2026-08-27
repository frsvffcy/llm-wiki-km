package org.km.llmwiki.processing;

import org.km.llmwiki.source.DocumentAnalysisTarget;

public record ProcessingJobItem(long id, DocumentAnalysisTarget document) {
}
