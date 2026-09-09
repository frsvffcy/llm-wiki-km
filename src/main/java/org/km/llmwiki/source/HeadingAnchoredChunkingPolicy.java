package org.km.llmwiki.source;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Version 2 chunking policy: structure-aware chunking that keeps a heading bound to its
 * first following paragraph, emits TABLE/FIGURE/CAPTION blocks as standalone atomic chunks,
 * flushes at page boundaries and never splits an individual block.
 */
@Component
public class HeadingAnchoredChunkingPolicy implements ChunkingPolicy {

    static final String CHUNK_POLICY_VERSION = "chunk-policy-v2-heading-anchor";

    private final ExtractedContentNormalizer normalizer;

    public HeadingAnchoredChunkingPolicy(ExtractedContentNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    @Override
    public String version() {
        return CHUNK_POLICY_VERSION;
    }

    @Override
    public List<SourceChunkDraft> chunk(ParsedDocument parsed,
                                        ExtractedContentNormalizer.CanonicalNormalization canonicalNormalization) {
        List<ChunkingPolicySupport.ChunkCandidate> candidates = new ArrayList<>();
        List<String> headingLevels = new ArrayList<>();
        ChunkingPolicySupport.ChunkAccumulator accumulator = null;
        boolean headingJustStarted = false;
        Integer previousPageNo = null;
        for (ParsedBlock block : parsed.blocks()) {
            if (!java.util.Objects.equals(previousPageNo, block.pageNo())) {
                accumulator = flush(candidates, accumulator, canonicalNormalization);
                headingJustStarted = false;
            }
            previousPageNo = block.pageNo();
            switch (block.kind()) {
                case HEADING -> {
                    accumulator = flush(candidates, accumulator, canonicalNormalization);
                    ChunkingPolicySupport.updateHeadingLevels(headingLevels, block.headingLevel(),
                            block.headingTitle());
                    accumulator = newAccumulator(block, headingLevels);
                    accumulator.append(block.text());
                    headingJustStarted = true;
                }
                case TABLE, FIGURE, CAPTION -> {
                    accumulator = flush(candidates, accumulator, canonicalNormalization);
                    accumulator = newAccumulator(block, headingLevels);
                    accumulator.append(block.text());
                    flush(candidates, accumulator, canonicalNormalization);
                    accumulator = null;
                    headingJustStarted = false;
                }
                case PARAGRAPH -> {
                    if (accumulator == null) {
                        accumulator = newAccumulator(block, headingLevels);
                    }
                    if (!headingJustStarted && !accumulator.isEmpty()
                            && accumulator.lengthWith(block.text()) > SourceChunker.TARGET_MAX_CHUNK_LENGTH) {
                        accumulator = flush(candidates, accumulator, canonicalNormalization);
                        accumulator = newAccumulator(block, headingLevels);
                    }
                    accumulator.append(block.text());
                    headingJustStarted = false;
                }
            }
        }
        flush(candidates, accumulator, canonicalNormalization);
        return ChunkingPolicySupport.retainOriginalEvidence(candidates);
    }

    private ChunkingPolicySupport.ChunkAccumulator flush(List<ChunkingPolicySupport.ChunkCandidate> candidates,
                                                         ChunkingPolicySupport.ChunkAccumulator accumulator,
                                                         ExtractedContentNormalizer.CanonicalNormalization canonicalNormalization) {
        if (accumulator != null && !accumulator.isEmpty()) {
            candidates.add(toCandidate(accumulator, canonicalNormalization));
        }
        return null;
    }

    private ChunkingPolicySupport.ChunkAccumulator newAccumulator(ParsedBlock block,
                                                                  List<String> headingLevels) {
        return new ChunkingPolicySupport.ChunkAccumulator(block.pageNo(),
                ChunkingPolicySupport.currentSection(headingLevels),
                ChunkingPolicySupport.currentHeadingPath(headingLevels));
    }

    private ChunkingPolicySupport.ChunkCandidate toCandidate(ChunkingPolicySupport.ChunkAccumulator accumulator,
                                                             ExtractedContentNormalizer.CanonicalNormalization canonicalNormalization) {
        String originalContent = accumulator.content();
        String normalizedContent = normalizer.normalizeChunk(originalContent, canonicalNormalization);
        return new ChunkingPolicySupport.ChunkCandidate(accumulator.pageNo(), accumulator.section(),
                accumulator.headingPath(), originalContent, normalizedContent,
                ChunkingPolicySupport.sha256(normalizedContent), CHUNK_POLICY_VERSION);
    }
}
