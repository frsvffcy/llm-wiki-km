package org.km.llmwiki.ai.ask;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.km.llmwiki.ai.answer.AnswerContextProvenance;
import org.km.llmwiki.ai.answer.AnswerProviderMetadata;
import org.km.llmwiki.rag.EvidenceKind;

import java.util.List;

/** Safe, provider-neutral response projection for the Ask REST API. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AskApiResponse(
        AskStatus status,
        String answer,
        boolean insufficientEvidence,
        List<Citation> citations,
        ProviderMetadata providerMetadata,
        ExecutionMetadata executionMetadata
) {

    public AskApiResponse {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    static AskApiResponse from(AskResult result) {
        return new AskApiResponse(
                result.status(),
                result.answerText().orElse(null),
                result.insufficientEvidence(),
                result.citations().stream().map(Citation::from).toList(),
                result.providerMetadata().map(ProviderMetadata::from).orElse(null),
                ExecutionMetadata.from(result.executionMetadata()));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Citation(String citationId, EvidenceKind evidenceKind, Provenance provenance) {
        static Citation from(AskCitation citation) {
            return new Citation(citation.citationId(), citation.evidenceKind(),
                    Provenance.from(citation.provenance()));
        }
    }

    /** Human-usable provenance; it deliberately has no internal content or hash fields. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Provenance(
            String type,
            String title,
            String path,
            Integer revision,
            String documentName,
            Long documentId,
            Long sourceChunkId,
            Integer chunkNo,
            Integer pageNo,
            String section,
            String headingPath
    ) {
        static Provenance from(AnswerContextProvenance provenance) {
            return switch (provenance) {
                case AnswerContextProvenance.Wiki wiki -> new Provenance(
                        "WIKI", wiki.title(), safeWikiPath(wiki.path()), wiki.revision(),
                        null, null, null, null, null, null, null);
                case AnswerContextProvenance.Source source -> new Provenance(
                        "SOURCE", null, null, null, source.documentName(), source.documentId(),
                        source.sourceChunkId(), source.chunkNo(), source.pageNo(), source.section(),
                        source.headingPath());
            };
        }

        private static String safeWikiPath(String path) {
            if (path == null || path.isBlank() || path.indexOf('\0') >= 0
                    || path.startsWith("/") || path.startsWith("\\")
                    || path.matches("^[A-Za-z]:[\\\\/].*")) {
                return null;
            }
            String normalized = path.replace('\\', '/');
            if (normalized.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) {
                return null;
            }
            for (String segment : normalized.split("/", -1)) {
                if (segment.equals("..")) {
                    return null;
                }
            }
            return normalized;
        }
    }

    public record ProviderMetadata(String provider, String model) {
        static ProviderMetadata from(AnswerProviderMetadata metadata) {
            return new ProviderMetadata(metadata.provider(), metadata.model());
        }
    }

    public record ExecutionMetadata(
            int retrievedEvidenceItems,
            int contextEvidenceItems,
            int contextCodePoints,
            boolean contextTruncated
    ) {
        static ExecutionMetadata from(AskExecutionMetadata metadata) {
            return new ExecutionMetadata(metadata.retrievedEvidenceItems(),
                    metadata.contextEvidenceItems(), metadata.contextCodePoints(),
                    metadata.contextTruncated());
        }
    }
}
