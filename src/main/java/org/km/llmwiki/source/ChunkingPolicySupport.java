package org.km.llmwiki.source;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Shared deterministic helpers for chunking policies (evidence merge, hashing, context). */
final class ChunkingPolicySupport {

    private ChunkingPolicySupport() {
    }

    static List<SourceChunkDraft> retainOriginalEvidence(List<ChunkCandidate> candidates) {
        List<SourceChunkDraft> chunks = new ArrayList<>();
        String pendingOriginalContent = null;
        for (ChunkCandidate candidate : candidates) {
            if (candidate.normalizedContent().isBlank()) {
                pendingOriginalContent = appendEvidence(pendingOriginalContent, candidate.originalContent());
                continue;
            }
            String originalContent = appendEvidence(pendingOriginalContent, candidate.originalContent());
            pendingOriginalContent = null;
            chunks.add(new SourceChunkDraft(chunks.size() + 1, candidate.pageNo(), candidate.section(),
                    candidate.headingPath(), originalContent, candidate.normalizedContent(),
                    candidate.contentHash(), candidate.chunkPolicyVersion()));
        }
        if (pendingOriginalContent != null && !chunks.isEmpty()) {
            SourceChunkDraft previous = chunks.removeLast();
            chunks.add(new SourceChunkDraft(previous.chunkNo(), previous.pageNo(), previous.section(),
                    previous.headingPath(), appendEvidence(previous.content(), pendingOriginalContent),
                    previous.normalizedContent(), previous.contentHash(), previous.chunkPolicyVersion()));
        }
        return List.copyOf(chunks);
    }

    private static String appendEvidence(String existing, String addition) {
        return existing == null ? addition : existing + "\n\n" + addition;
    }

    static void updateHeadingLevels(List<String> headingLevels, int level, String heading) {
        while (headingLevels.size() >= level) {
            headingLevels.removeLast();
        }
        while (headingLevels.size() < level - 1) {
            headingLevels.add("");
        }
        headingLevels.add(heading.trim());
    }

    static String currentSection(List<String> headingLevels) {
        for (int index = headingLevels.size() - 1; index >= 0; index--) {
            if (!headingLevels.get(index).isBlank()) {
                return headingLevels.get(index);
            }
        }
        return null;
    }

    static String currentHeadingPath(List<String> headingLevels) {
        return headingLevels.stream().filter(heading -> !heading.isBlank())
                .reduce((parent, child) -> parent + " > " + child)
                .orElse(null);
    }

    static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static final class ChunkAccumulator {
        private final Integer pageNo;
        private final String section;
        private final String headingPath;
        private final StringBuilder content = new StringBuilder();

        ChunkAccumulator(Integer pageNo, String section, String headingPath) {
            this.pageNo = pageNo;
            this.section = section;
            this.headingPath = headingPath;
        }

        void append(String block) {
            if (!content.isEmpty()) {
                content.append("\n\n");
            }
            content.append(block);
        }

        boolean isEmpty() {
            return content.isEmpty();
        }

        int lengthWith(String block) {
            return content.length() + 2 + block.length();
        }

        Integer pageNo() {
            return pageNo;
        }

        String section() {
            return section;
        }

        String headingPath() {
            return headingPath;
        }

        String content() {
            return content.toString();
        }
    }

    record ChunkCandidate(Integer pageNo, String section, String headingPath, String originalContent,
                          String normalizedContent, String contentHash, String chunkPolicyVersion) {
    }
}
