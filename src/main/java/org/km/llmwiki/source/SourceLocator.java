package org.km.llmwiki.source;

/**
 * Provider-neutral navigation contract for one cited Source Chunk. Locators are produced only
 * from canonical, authority-validated data at inspection time; they never participate in
 * citation identity, dedupe, ranking, or authority, and they never carry filesystem paths,
 * vendor record ids, or parser internals. Future layout-aware parsers may extend this
 * contract with structural block ids or bounding regions without changing citation identity;
 * a parser without layout capability keeps those fields absent instead of fabricating
 * precision.
 */
public record SourceLocator(
        long sourceChunkId,
        long documentId,
        String documentName,
        int chunkNo,
        Integer pageNo,
        String section,
        String headingPath,
        ChunkCurrentness currentness,
        String notCurrentReason,
        String preview,
        boolean previewTruncated) {

    public SourceLocator {
        if (currentness == null) {
            throw new IllegalArgumentException("currentness is required");
        }
        if (currentness == ChunkCurrentness.NOT_CURRENT
                && (notCurrentReason == null || notCurrentReason.isBlank())) {
            throw new IllegalArgumentException("not-current locators require a reason code");
        }
        if (currentness == ChunkCurrentness.CURRENT && notCurrentReason != null) {
            throw new IllegalArgumentException("current locators must not carry a rejection reason");
        }
        if (currentness == ChunkCurrentness.CURRENT && preview == null) {
            throw new IllegalArgumentException("current locators require an authoritative preview");
        }
        if (currentness == ChunkCurrentness.NOT_CURRENT && preview != null) {
            throw new IllegalArgumentException("not-current locators must not expose content");
        }
    }
}
