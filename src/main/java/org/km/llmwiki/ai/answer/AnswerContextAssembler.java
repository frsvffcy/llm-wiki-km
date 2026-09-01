package org.km.llmwiki.ai.answer;

import org.km.llmwiki.rag.EvidenceBundle;
import org.km.llmwiki.rag.EvidenceItem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Converts one already-retrieved EvidenceBundle into bounded provider-neutral context. */
public final class AnswerContextAssembler {

    private final AnswerContextBudget budget;

    public AnswerContextAssembler() {
        this(AnswerContextBudget.DEFAULT);
    }

    public AnswerContextAssembler(AnswerContextBudget budget) {
        this.budget = budget;
    }

    public AnswerContext assemble(EvidenceBundle bundle) {
        if (bundle == null) {
            throw new IllegalArgumentException("evidence bundle must not be null");
        }
        if (bundle.insufficientEvidence() || bundle.items().isEmpty()) {
            return AnswerContext.empty();
        }

        List<AnswerContextBlock> blocks = new ArrayList<>();
        Set<String> normalizedIdentities = new HashSet<>();
        int usedCodePoints = 0;
        boolean truncated = false;

        for (int index = 0; index < bundle.items().size(); index++) {
            EvidenceItem item = bundle.items().get(index);
            String identity = normalizedIdentity(item);
            if (!normalizedIdentities.add(identity)) {
                continue;
            }
            if (blocks.size() >= budget.maxEvidenceItems()) {
                truncated = true;
                break;
            }
            int remaining = budget.maxTotalCodePoints() - usedCodePoints;
            if (remaining <= 0) {
                truncated = true;
                break;
            }

            int itemLimit = Math.min(budget.maxCodePointsPerItem(), remaining);
            int originalCodePoints = item.content().codePointCount(0, item.content().length());
            boolean itemTruncated = originalCodePoints > itemLimit;
            String content = truncate(item.content(), itemLimit);
            if (content.isBlank()) {
                truncated = true;
                break;
            }
            blocks.add(new AnswerContextBlock("E" + (blocks.size() + 1), item.kind(),
                    identity, content, item.contentTruncated() || itemTruncated,
                    item.contentHash(), provenance(item)));
            usedCodePoints += content.codePointCount(0, content.length());
            truncated |= item.contentTruncated() || itemTruncated;

            if (usedCodePoints >= budget.maxTotalCodePoints() && hasFurtherUnique(bundle.items(), index + 1,
                    normalizedIdentities)) {
                truncated = true;
                break;
            }
        }

        return new AnswerContext(blocks,
                new AnswerContextUsage(blocks.size(), usedCodePoints, truncated));
    }

    private static String normalizedIdentity(EvidenceItem item) {
        return item.kind().name() + ":" + item.stableId().strip();
    }

    private static boolean hasFurtherUnique(List<EvidenceItem> items, int fromIndex,
                                            Set<String> normalizedIdentities) {
        for (int index = fromIndex; index < items.size(); index++) {
            if (!normalizedIdentities.contains(normalizedIdentity(items.get(index)))) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String content, int maxCodePoints) {
        int count = content.codePointCount(0, content.length());
        if (count <= maxCodePoints) {
            return content;
        }
        return content.substring(0, content.offsetByCodePoints(0, maxCodePoints));
    }

    private static AnswerContextProvenance provenance(EvidenceItem item) {
        return switch (item.kind()) {
            case WIKI -> new AnswerContextProvenance.Wiki(item.title(), item.path(), item.revision());
            case SOURCE_CHUNK -> new AnswerContextProvenance.Source(item.documentName(), item.documentId(),
                    item.sourceChunkId(), item.chunkNo(), item.pageNo(), item.section(), item.headingPath());
        };
    }
}
