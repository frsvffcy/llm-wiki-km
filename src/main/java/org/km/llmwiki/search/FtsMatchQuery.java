package org.km.llmwiki.search;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Converts user text into a bound, literal FTS5 expression without allowing MATCH operators. */
final class FtsMatchQuery {

    private static final int MAX_QUERY_CODE_POINTS = 256;
    private static final int MAX_TERMS = 16;

    private FtsMatchQuery() {
    }

    static String literalExpression(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Search query must not be blank");
        }
        if (query.codePointCount(0, query.length()) > MAX_QUERY_CODE_POINTS) {
            throw new IllegalArgumentException("Search query must not exceed "
                    + MAX_QUERY_CODE_POINTS + " characters");
        }
        if (query.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                && !Character.isWhitespace(codePoint))) {
            throw new IllegalArgumentException("Search query contains unsupported control characters");
        }
        List<String> terms = tokenize(query);
        if (terms.isEmpty()) {
            throw new IllegalArgumentException("Search query contains no searchable terms");
        }
        if (terms.size() > MAX_TERMS) {
            throw new IllegalArgumentException("Search query must not contain more than "
                    + MAX_TERMS + " terms");
        }
        return terms.stream()
                .map(FtsMatchQuery::quote)
                .collect(Collectors.joining(" AND "));
    }

    private static List<String> tokenize(String query) {
        List<String> terms = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        query.codePoints().forEach(codePoint -> {
            if (isTokenCodePoint(codePoint)) {
                current.appendCodePoint(codePoint);
            } else if (!current.isEmpty()) {
                terms.add(current.toString());
                current.setLength(0);
            }
        });
        if (!current.isEmpty()) {
            terms.add(current.toString());
        }
        return terms;
    }

    private static boolean isTokenCodePoint(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isLetterOrDigit(codePoint)
                || type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    private static String quote(String term) {
        return '"' + term.replace("\"", "\"\"") + '"';
    }
}
