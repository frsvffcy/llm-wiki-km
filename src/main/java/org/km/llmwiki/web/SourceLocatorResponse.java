package org.km.llmwiki.web;

/**
 * Safe projection of a cited Source Chunk locator: display metadata from the persisted chunk
 * and an authoritative bounded preview only while the chunk still matches canonical
 * authority. Never carries filesystem paths, vendor ids, raw parser metadata, or exception
 * details.
 */
@com.fasterxml.jackson.annotation.JsonInclude(
        com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record SourceLocatorResponse(
        long sourceChunkId,
        Long documentId,
        String documentName,
        int chunkNo,
        Integer pageNo,
        String section,
        String headingPath,
        String currentness,
        String notCurrentReason,
        String preview,
        boolean previewTruncated) {
}
