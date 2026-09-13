package org.km.llmwiki.wiki;

/**
 * One citation reference from an Ask result, exactly as the Browser received it:
 * SOURCE citations carry the chunk id; WIKI citations carry the logical vault path and
 * the revision at ask time (the backend resolves the page and detects staleness).
 */
public record AskCitationInput(String evidenceId, String kind, Long sourceChunkId,
                               String wikiPath, Integer wikiRevision) {
}
