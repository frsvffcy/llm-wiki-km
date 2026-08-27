package org.km.llmwiki.source;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SourceChunker {

    static final int TARGET_MAX_CHUNK_LENGTH = 3_000;
    private static final Pattern PARAGRAPH_BOUNDARY = Pattern.compile("(?:\\r\\n|\\r|\\n)[\\t ]*(?:\\r\\n|\\r|\\n)+");
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^[\\t ]{0,3}(#{1,6})[\\t ]+(.+?)[\\t ]*#*[\\t ]*$");

    private final ExtractedContentNormalizer normalizer;

    public SourceChunker(ExtractedContentNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    public List<SourceChunkDraft> chunk(String content) {
        List<SourceChunkDraft> chunks = new ArrayList<>();
        List<String> headingLevels = new ArrayList<>();
        int chunkNo = 1;
        String[] pages = content.split("\\f", -1);
        for (int pageIndex = 0; pageIndex < pages.length; pageIndex++) {
            ChunkAccumulator accumulator = null;
            for (String paragraph : PARAGRAPH_BOUNDARY.split(pages[pageIndex])) {
                String block = trimBoundaryWhitespace(paragraph);
                if (block.isEmpty()) {
                    continue;
                }
                Matcher heading = MARKDOWN_HEADING.matcher(block);
                if (heading.matches()) {
                    if (accumulator != null) {
                        chunks.add(toDraft(chunkNo++, accumulator));
                    }
                    updateHeadingLevels(headingLevels, heading.group(1).length(), heading.group(2));
                    accumulator = new ChunkAccumulator(pageIndex + 1, currentSection(headingLevels),
                            currentHeadingPath(headingLevels));
                    accumulator.append(block);
                    continue;
                }

                if (accumulator == null) {
                    accumulator = new ChunkAccumulator(pageIndex + 1, currentSection(headingLevels),
                            currentHeadingPath(headingLevels));
                }
                if (!accumulator.isEmpty() && accumulator.lengthWith(block) > TARGET_MAX_CHUNK_LENGTH) {
                    chunks.add(toDraft(chunkNo++, accumulator));
                    accumulator = new ChunkAccumulator(pageIndex + 1, currentSection(headingLevels),
                            currentHeadingPath(headingLevels));
                }
                accumulator.append(block);
            }
            if (accumulator != null && !accumulator.isEmpty()) {
                chunks.add(toDraft(chunkNo++, accumulator));
            }
        }
        return List.copyOf(chunks);
    }

    private SourceChunkDraft toDraft(int chunkNo, ChunkAccumulator accumulator) {
        String originalContent = accumulator.content();
        String normalizedContent = normalizer.normalize(originalContent);
        return new SourceChunkDraft(chunkNo, accumulator.pageNo(), accumulator.section(),
                accumulator.headingPath(), originalContent, normalizedContent, sha256(normalizedContent));
    }

    private static void updateHeadingLevels(List<String> headingLevels, int level, String heading) {
        while (headingLevels.size() >= level) {
            headingLevels.removeLast();
        }
        while (headingLevels.size() < level - 1) {
            headingLevels.add("");
        }
        headingLevels.add(heading.trim());
    }

    private static String currentSection(List<String> headingLevels) {
        for (int index = headingLevels.size() - 1; index >= 0; index--) {
            if (!headingLevels.get(index).isBlank()) {
                return headingLevels.get(index);
            }
        }
        return null;
    }

    private static String currentHeadingPath(List<String> headingLevels) {
        String path = headingLevels.stream().filter(heading -> !heading.isBlank())
                .reduce((parent, child) -> parent + " > " + child)
                .orElse(null);
        return path;
    }

    private static String trimBoundaryWhitespace(String content) {
        return content.replaceFirst("^[\\s&&[^\\f]]+", "")
                .replaceFirst("[\\s&&[^\\f]]+$", "");
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static final class ChunkAccumulator {
        private final int pageNo;
        private final String section;
        private final String headingPath;
        private final StringBuilder content = new StringBuilder();

        private ChunkAccumulator(int pageNo, String section, String headingPath) {
            this.pageNo = pageNo;
            this.section = section;
            this.headingPath = headingPath;
        }

        private void append(String block) {
            if (!content.isEmpty()) {
                content.append("\n\n");
            }
            content.append(block);
        }

        private boolean isEmpty() {
            return content.isEmpty();
        }

        private int lengthWith(String block) {
            return content.length() + 2 + block.length();
        }

        private int pageNo() {
            return pageNo;
        }

        private String section() {
            return section;
        }

        private String headingPath() {
            return headingPath;
        }

        private String content() {
            return content.toString();
        }
    }
}
