package org.km.llmwiki.ai.answer;

/** Provider-neutral provenance projection; prompt serialization deliberately omits internal hashes. */
public sealed interface AnswerContextProvenance
        permits AnswerContextProvenance.Wiki, AnswerContextProvenance.Source {

    record Wiki(String title, String path, Integer revision) implements AnswerContextProvenance {
        public Wiki {
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("wiki title must not be blank");
            }
            if (path == null) {
                throw new IllegalArgumentException("wiki path must not be null");
            }
        }
    }

    record Source(String documentName, Long documentId, Long sourceChunkId, Integer chunkNo,
                  Integer pageNo, String section, String headingPath)
            implements AnswerContextProvenance {
        public Source {
            if (documentName == null || documentName.isBlank()
                    || documentId == null || sourceChunkId == null || chunkNo == null) {
                throw new IllegalArgumentException("source document and chunk provenance are required");
            }
        }
    }
}
