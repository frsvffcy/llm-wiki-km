package org.km.llmwiki.ai.answer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic, content-aware compaction candidates evaluated against the production
 * truncation baseline ({@code answer-context-compaction-corpus-v1}).
 *
 * <p>Candidates project each baseline block's content from the original evidence item, so a
 * policy can preserve facts the truncation baseline dropped (e.g. tail facts). The block
 * skeleton (citation ids, canonical identities, order, provenance, canonical content hash) is
 * carried through from the baseline unchanged — candidates can never add, remove, reorder, or
 * re-identify blocks, and compacted text is ephemeral provider payload that never becomes
 * evidence identity. Structured content (tables, code) and already-short evidence are NO-OP
 * by policy; that can make the projected context larger than the truncation baseline, which
 * is recorded honestly rather than treated as a failure.
 */
final class AnswerContextCompactionCandidates {

    private static final String ELLIPSIS = "\n…\n";

    private AnswerContextCompactionCandidates() {
    }

    interface Candidate {
        String name();

        /** Projects block content from the original evidence; the baseline block skeleton is fixed. */
        AnswerContext project(org.km.llmwiki.rag.EvidenceBundle bundle, AnswerContext baseline,
                              AnswerContextBudget budget);
    }

    /**
     * Head-tail window: for prose that exceeds the per-item budget, keep the leading and
     * trailing halves of the window projected from the original evidence. Preserves tail
     * facts that the truncation baseline drops; facts in the elided middle are honestly lost.
     */
    static final class HeadTailWindow implements Candidate {

        @Override
        public String name() {
            return "head-tail-window";
        }

        @Override
        public AnswerContext project(org.km.llmwiki.rag.EvidenceBundle bundle,
                                     AnswerContext baseline, AnswerContextBudget budget) {
            return projectBlocks(bundle, baseline, budget, this::headTailContent);
        }

        String headTailContent(String content, AnswerContextBudget budget) {
            int limit = budget.maxCodePointsPerItem();
            int count = content.codePointCount(0, content.length());
            if (isStructured(content) || count <= limit) {
                return content;
            }
            int separator = ELLIPSIS.codePointCount(0, ELLIPSIS.length());
            int half = Math.max(0, (limit - separator) / 2);
            String head = content.substring(0, content.offsetByCodePoints(0, half));
            String tail = content.substring(content.offsetByCodePoints(0, count - half));
            return head + ELLIPSIS + tail;
        }
    }

    /**
     * Sentence skeleton: for prose beyond the budget, keep heading lines plus the first and
     * last sentence of each paragraph; falls back to the head-tail window when the skeleton
     * still exceeds the per-item budget. Tables, code, and short content stay untouched.
     */
    static final class SentenceSkeleton implements Candidate {

        @Override
        public String name() {
            return "sentence-skeleton";
        }

        @Override
        public AnswerContext project(org.km.llmwiki.rag.EvidenceBundle bundle,
                                     AnswerContext baseline, AnswerContextBudget budget) {
            return projectBlocks(bundle, baseline, budget, this::skeletonContent);
        }

        private String skeletonContent(String content, AnswerContextBudget budget) {
            int limit = budget.maxCodePointsPerItem();
            int count = content.codePointCount(0, content.length());
            if (isStructured(content) || count <= limit) {
                return content;
            }
            List<String> sentences = sentences(content);
            List<String> skeleton = new ArrayList<>();
            for (int index = 0; index < sentences.size(); index++) {
                String sentence = sentences.get(index);
                boolean heading = sentence.strip().startsWith("#");
                boolean first = index == 0;
                boolean last = index == sentences.size() - 1;
                if (heading || first || last) {
                    skeleton.add(sentence);
                }
            }
            String compacted = String.join("\n", skeleton);
            if (compacted.isBlank()) {
                return content;
            }
            if (compacted.codePointCount(0, compacted.length()) > limit) {
                return new HeadTailWindow().headTailContent(content, budget);
            }
            return compacted;
        }

        private static List<String> sentences(String content) {
            List<String> sentences = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (int codePoint : content.codePoints().toArray()) {
                char character = Character.toChars(codePoint)[0];
                current.appendCodePoint(codePoint);
                if (".!?".indexOf(character) >= 0 || "。！？".indexOf(character) >= 0
                        || character == '\n') {
                    String sentence = current.toString().strip();
                    current.setLength(0);
                    if (!sentence.isEmpty()) {
                        sentences.add(sentence);
                    }
                }
            }
            String remainder = current.toString().strip();
            if (!remainder.isEmpty()) {
                sentences.add(remainder);
            }
            return sentences;
        }

    }

    static boolean isStructured(String content) {
        return isCode(content) || isTable(content);
    }

    private static boolean isCode(String content) {
        return content.contains("```");
    }

    private static boolean isTable(String content) {
        return content.lines().anyMatch(line -> line.startsWith("|") && line.contains("|---"))
                || content.lines().filter(line -> line.strip().startsWith("|")).count() >= 4;
    }

    /**
     * Shared projection loop: the baseline block skeleton is authoritative (identity order,
     * citation ids, provenance, canonical hash); only the content is re-projected from the
     * original evidence item, and projected blocks may exceed the per-item budget when the
     * policy keeps structured content intact (recorded, never hidden).
     */
    private static AnswerContext projectBlocks(org.km.llmwiki.rag.EvidenceBundle bundle,
                                               AnswerContext baseline,
                                               AnswerContextBudget budget,
                                               java.util.function.BiFunction<String,
                                                       AnswerContextBudget, String> projection) {
        Map<String, org.km.llmwiki.rag.EvidenceItem> originalContent = new LinkedHashMap<>();
        for (org.km.llmwiki.rag.EvidenceItem item : bundle.items()) {
            originalContent.putIfAbsent(item.kind().name() + ":" + item.stableId().strip(), item);
        }
        List<AnswerContextBlock> blocks = new ArrayList<>();
        for (AnswerContextBlock block : baseline.blocks()) {
            org.km.llmwiki.rag.EvidenceItem item = originalContent.get(block.authorityIdentity());
            String fullContent = item == null ? block.content() : item.content();
            String content = item == null ? block.content()
                    : projection.apply(fullContent, budget);
            boolean contentCompacted = !content.equals(fullContent);
            blocks.add(new AnswerContextBlock(block.citationId(), block.evidenceKind(),
                    block.authorityIdentity(), content, contentCompacted, block.contentHash(),
                    block.provenance()));
        }
        int usedCodePoints = blocks.stream()
                .mapToInt(projected -> projected.content().codePointCount(0,
                        projected.content().length()))
                .sum();
        boolean anyCompacted = blocks.stream().anyMatch(AnswerContextBlock::contentTruncated);
        return new AnswerContext(List.copyOf(blocks),
                new AnswerContextUsage(blocks.size(), usedCodePoints, anyCompacted));
    }

    static Candidate headTailWindow() {
        return new HeadTailWindow();
    }

    static Candidate sentenceSkeleton() {
        return new SentenceSkeleton();
    }

}
