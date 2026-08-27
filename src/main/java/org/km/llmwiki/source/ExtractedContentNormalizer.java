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
        String normalized = normalizeLineEndings(Objects.requireNonNull(content, "content must not be null"));
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFC);
        normalized = removeControlCharacters(normalized);
        normalized = removeTrailingWhitespace(normalized);
        if (properties.isRepeatedHeaderFooterEnabled()) {
            normalized = removeRepeatedHeaderAndFooters(normalized,
                    properties.getRepeatedHeaderFooterMinimumOccurrences());
        }
        return collapseExcessBlankLines(normalized);
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

    private static String removeRepeatedHeaderAndFooters(String content, int minimumOccurrences) {
        List<List<String>> pages = splitPages(content);
        if (pages.size() < minimumOccurrences) {
            return content;
        }

        Set<String> repeatedHeaders = repeatedEdgeLines(pages, true, minimumOccurrences);
        Set<String> repeatedFooters = repeatedEdgeLines(pages, false, minimumOccurrences);
        if (repeatedHeaders.isEmpty() && repeatedFooters.isEmpty()) {
            return content;
        }

        return pages.stream()
                .map(page -> withoutRepeatedEdges(page, repeatedHeaders, repeatedFooters))
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
}
