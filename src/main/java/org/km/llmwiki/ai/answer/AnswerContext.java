package org.km.llmwiki.ai.answer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Bounded, provider-neutral evidence context controlled by the application.
 */
public record AnswerContext(List<AnswerContextBlock> blocks, AnswerContextUsage usage) {

    public static final int MAX_REFERENCES = 64;
    private static final Pattern CITATION_ID = Pattern.compile("E[1-9][0-9]*");

    public AnswerContext {
        if (blocks == null) {
            throw new IllegalArgumentException("context blocks must not be null");
        }
        if (blocks.size() > MAX_REFERENCES) {
            throw new IllegalArgumentException("context blocks must not exceed " + MAX_REFERENCES);
        }
        if (blocks.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("context blocks must not contain null");
        }
        if (usage == null) {
            throw new IllegalArgumentException("context usage must not be null");
        }
        blocks = List.copyOf(blocks);
        Set<String> citationIds = new HashSet<>();
        Set<String> authorityIdentities = new HashSet<>();
        blocks.forEach(block -> {
            if (!CITATION_ID.matcher(block.citationId()).matches()
                    || !citationIds.add(block.citationId())
                    || !authorityIdentities.add(block.authorityIdentity())) {
                throw new IllegalArgumentException("context citation ids must be unique and valid");
            }
        });
    }

    public AnswerContext(List<AnswerContextBlock> blocks) {
        this(blocks, usageFor(blocks));
    }

    /** Source-compatible constructor for the reference-only STORY-601 boundary. */
    public AnswerContext(Collection<AnswerContextReference> references) {
        this(fromReferences(referenceList(references)).blocks());
    }

    public static AnswerContext empty() {
        return new AnswerContext(List.of(), AnswerContextUsage.empty());
    }

    /** Compatibility factory for the reference-only boundary introduced by STORY-601. */
    public static AnswerContext fromReferences(List<AnswerContextReference> references) {
        if (references == null) {
            throw new IllegalArgumentException("context references must not be null");
        }
        if (references.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("context references must not contain null");
        }
        List<AnswerContextBlock> blocks = new ArrayList<>();
        for (int index = 0; index < references.size(); index++) {
            AnswerContextReference reference = references.get(index);
            blocks.add(new AnswerContextBlock("E" + (index + 1),
                    org.km.llmwiki.rag.EvidenceKind.WIKI,
                    reference.stableId(), "reference-only context", false,
                    reference.contentHash(), new AnswerContextProvenance.Wiki(
                            "Reference", "", null)));
        }
        return new AnswerContext(blocks);
    }

    /** Stable references exposed to the provider-neutral Answer contract. */
    public List<AnswerContextReference> references() {
        return blocks.stream()
                .map(block -> new AnswerContextReference(block.authorityIdentity(), block.contentHash()))
                .toList();
    }

    /**
     * Validates and deterministically de-duplicates application citation IDs from an answer.
     * Unknown or malformed IDs are rejected instead of being retained.
     */
    public List<String> normalizeCitationIds(Collection<String> citationIds) {
        if (citationIds == null) {
            throw new CitationValidationException("citation ids must not be null");
        }
        Set<String> known = blocks.stream().map(AnswerContextBlock::citationId).collect(java.util.stream.Collectors.toSet());
        List<String> normalized = new ArrayList<>();
        for (String raw : citationIds) {
            if (raw == null) {
                throw new CitationValidationException("citation id must not be null");
            }
            String id = raw.strip();
            if (!CITATION_ID.matcher(id).matches() || !known.contains(id)) {
                throw new CitationValidationException("unknown or malformed citation id: " + id);
            }
            if (!normalized.contains(id)) {
                normalized.add(id);
            }
        }
        return List.copyOf(normalized);
    }

    public AnswerContextBlock blockForCitation(String citationId) {
        return blocks.stream()
                .filter(block -> block.citationId().equals(citationId))
                .findFirst()
                .orElseThrow(() -> new CitationValidationException(
                        "unknown or malformed citation id: " + citationId));
    }

    private static AnswerContextUsage usageFor(List<AnswerContextBlock> blocks) {
        if (blocks == null) {
            throw new IllegalArgumentException("context blocks must not be null");
        }
        return new AnswerContextUsage(blocks.size(),
                blocks.stream().filter(Objects::nonNull)
                        .mapToInt(block -> block.content().codePointCount(0, block.content().length()))
                        .sum(), blocks.stream().anyMatch(block -> block != null && block.contentTruncated()));
    }

    private static List<AnswerContextReference> referenceList(Collection<AnswerContextReference> references) {
        if (references == null) {
            throw new IllegalArgumentException("context references must not be null");
        }
        return new ArrayList<>(references);
    }
}
