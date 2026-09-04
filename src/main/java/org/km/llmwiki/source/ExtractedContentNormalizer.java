package org.km.llmwiki.source;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Applies syntax-level cleanup only. It deliberately does not rewrite the text's meaning.
 */
@Component
public class ExtractedContentNormalizer {

    private static final String PAGE_BREAK = "\f";

    private final ExtractedContentNormalizationProperties properties;

    public ExtractedContentNormalizer(ExtractedContentNormalizationProperties properties) {
        this.properties = properties;
    }

    public String normalize(String content) {
        return canonicalize(content).content();
    }

    /**
     * Builds the document-scoped normalization context used by both the extracted document and its source chunks.
     */
    public CanonicalNormalization canonicalize(String content) {
        String preparedContent = prepare(Objects.requireNonNull(content, "content must not be null"));
        RepeatedEdges repeatedEdges = repeatedEdges(preparedContent);
        return new CanonicalNormalization(normalizePrepared(preparedContent, repeatedEdges), repeatedEdges);
    }

    /**
     * Normalizes a chunk with the repeated header/footer decision already made for its containing document.
     */
    public String normalizeChunk(String content, CanonicalNormalization canonicalNormalization) {
        Objects.requireNonNull(canonicalNormalization, "canonicalNormalization must not be null");
        return normalizePrepared(prepare(Objects.requireNonNull(content, "content must not be null")),
                canonicalNormalization.repeatedEdges());
    }

    private static String prepare(String content) {
        String normalized = normalizeLineEndings(content);
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFC);
        normalized = removeControlCharacters(normalized);
        return removeTrailingWhitespace(normalized);
    }

    private String normalizePrepared(String content, RepeatedEdges repeatedEdges) {
        String normalized = removeRepeatedHeaderAndFooters(content, repeatedEdges);
        return collapseExcessBlankLines(normalized);
    }

    private RepeatedEdges repeatedEdges(String content) {
        if (!properties.isRepeatedHeaderFooterEnabled()) {
            return RepeatedEdges.none();
        }
        List<List<String>> pages = splitPages(content);
        int minimumOccurrences = properties.getRepeatedHeaderFooterMinimumOccurrences();
        if (pages.size() < minimumOccurrences) {
            return RepeatedEdges.none();
        }
        return new RepeatedEdges(
                repeatedEdgeLines(pages, true, minimumOccurrences),
                repeatedEdgeLines(pages, false, minimumOccurrences));
    }

    private static String normalizeLineEndings(String content) {
        return content.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String removeControlCharacters(String content) {
        StringBuilder cleaned = new StringBuilder(content.length());
        content.codePoints().forEach(codePoint -> {
            if (!Character.isISOControl(codePoint)
                    || codePoint == '\n' || codePoint == '\t' || codePoint == '\f') {
                cleaned.appendCodePoint(codePoint);
            }
        });
        return cleaned.toString();
    }

    private static String removeTrailingWhitespace(String content) {
        return content.replaceAll("[\\t ]+(?=\\n|\\f|$)", "");
    }

    private static String collapseExcessBlankLines(String content) {
        return content.replaceAll("\\n{3,}", "\n\n");
    }

    private static String removeRepeatedHeaderAndFooters(String content, RepeatedEdges repeatedEdges) {
        List<List<String>> pages = splitPages(content);
        if (repeatedEdges.isEmpty()) {
            return content;
        }

        return pages.stream()
                .map(page -> withoutRepeatedEdges(page, repeatedEdges.headers(), repeatedEdges.footers()))
                .map(lines -> String.join("\n", lines))
                .collect(Collectors.joining(PAGE_BREAK));
    }

    private static List<List<String>> splitPages(String content) {
        String[] rawPages = content.split(PAGE_BREAK, -1);
        List<List<String>> pages = new ArrayList<>(rawPages.length);
        for (String page : rawPages) {
            pages.add(new ArrayList<>(List.of(page.split("\\n", -1))));
        }
        return pages;
    }

    private static Set<String> repeatedEdgeLines(List<List<String>> pages, boolean header,
                                                  int minimumOccurrences) {
        Map<String, Integer> occurrences = new HashMap<>();
        for (List<String> page : pages) {
            edgeLine(page, header).ifPresent(line -> occurrences.merge(line, 1, Integer::sum));
        }
        return occurrences.entrySet().stream()
                .filter(entry -> entry.getValue() >= minimumOccurrences)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    private static Optional<String> edgeLine(List<String> page, boolean header) {
        if (header) {
            return page.stream().filter(line -> !line.isBlank()).findFirst();
        }
        for (int index = page.size() - 1; index >= 0; index--) {
            String line = page.get(index);
            if (!line.isBlank()) {
                return Optional.of(line);
            }
        }
        return Optional.empty();
    }

    private static List<String> withoutRepeatedEdges(List<String> page, Set<String> repeatedHeaders,
                                                      Set<String> repeatedFooters) {
        List<String> kept = new ArrayList<>(page);
        removeEdgeIfRepeated(kept, repeatedHeaders, true);
        removeEdgeIfRepeated(kept, repeatedFooters, false);
        return kept;
    }

    private static void removeEdgeIfRepeated(List<String> lines, Set<String> repeatedLines, boolean header) {
        if (header) {
            for (int index = 0; index < lines.size(); index++) {
                if (!lines.get(index).isBlank()) {
                    if (repeatedLines.contains(lines.get(index))) {
                        lines.remove(index);
                    }
                    return;
                }
            }
            return;
        }
        for (int index = lines.size() - 1; index >= 0; index--) {
            if (!lines.get(index).isBlank()) {
                if (repeatedLines.contains(lines.get(index))) {
                    lines.remove(index);
                }
                return;
            }
        }
    }

    public record CanonicalNormalization(String content, RepeatedEdges repeatedEdges) {
    }

    public record RepeatedEdges(Set<String> headers, Set<String> footers) {
        private static RepeatedEdges none() {
            return new RepeatedEdges(Set.of(), Set.of());
        }

        private boolean isEmpty() {
            return headers.isEmpty() && footers.isEmpty();
        }
    }
}
