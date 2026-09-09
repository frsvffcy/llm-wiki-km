package org.km.llmwiki.source;

import java.util.List;

/**
 * Versioned, parser-neutral policy that turns a structure-preserving parse result into
 * chunk drafts. Policies consume typed blocks from {@link ParsedDocument}; they never
 * reverse-engineer structure from flat text. A policy version identifies the deterministic
 * chunking behavior for provenance; the active version stamps every persisted chunk so a
 * later policy switch stays auditable and downstream rebuilds remain explicit.
 */
public interface ChunkingPolicy {

    String version();

    List<SourceChunkDraft> chunk(ParsedDocument parsed,
                                 ExtractedContentNormalizer.CanonicalNormalization canonicalNormalization);
}
