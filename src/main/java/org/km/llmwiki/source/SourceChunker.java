package org.km.llmwiki.source;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Version 1 chunking policy: the exact behavior the original flat-text chunker always had
 * (page-, paragraph- and Markdown-heading-aware accumulation with a bounded target chunk
 * length), now expressed over typed structural blocks instead of reverse-engineering
 * structure from text.
 */
@Component
public class SourceChunker implements ChunkingPolicy {

    static final String CHUNK_POLICY_VERSION = "chunk-policy-v1-current";
    static final int TARGET_MAX_CHUNK_LENGTH = 3_000;

    private final ExtractedContentNormalizer normalizer;

    public SourceChunker(ExtractedContentNormalizer normalizer) {
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
        Integer previousPageNo = null;
        for (ParsedBlock block : parsed.blocks()) {
            if (!java.util.Objects.equals(previousPageNo, block.pageNo())) {
                if (accumulator != null && !accumulator.isEmpty()) {
                    candidates.add(toCandidate(accumulator, canonicalNormalization));
                }
                accumulator = null;
            }
            previousPageNo = block.pageNo();
            if (block.kind() == ParsedBlockKind.HEADING) {
                if (accumulator != null) {
                    candidates.add(toCandidate(accumulator, canonicalNormalization));
                }
                ChunkingPolicySupport.updateHeadingLevels(headingLevels, block.headingLevel(),
                        block.headingTitle());
                accumulator = newAccumulator(block, headingLevels);
                accumulator.append(block.text());
                continue;
            }
            if (accumulator == null) {
                accumulator = newAccumulator(block, headingLevels);
            }
            if (!accumulator.isEmpty()
                    && accumulator.lengthWith(block.text()) > TARGET_MAX_CHUNK_LENGTH) {
                candidates.add(toCandidate(accumulator, canonicalNormalization));
                accumulator = newAccumulator(block, headingLevels);
            }
            accumulator.append(block.text());
        }
        if (accumulator != null && !accumulator.isEmpty()) {
            candidates.add(toCandidate(accumulator, canonicalNormalization));
        }
        return ChunkingPolicySupport.retainOriginalEvidence(candidates);
    }

    /**
     * Legacy flat-text adapter kept for pre-existing callers; delegates through the same
     * application-owned segmentation rules and the version 1 policy.
     */
    List<SourceChunkDraft> chunk(String content,
                                 ExtractedContentNormalizer.CanonicalNormalization canonicalNormalization)
            throws DocumentParserResourceLimitException {
        return chunk(new ParsedDocument(content, java.util.Map.of()), canonicalNormalization);
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
