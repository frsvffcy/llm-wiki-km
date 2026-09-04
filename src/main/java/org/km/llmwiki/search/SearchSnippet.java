package org.km.llmwiki.search;

import java.util.ArrayList;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;

/** Keeps FTS snippets bounded by Unicode code point without cutting a surrogate pair or marker. */
final class SearchSnippet {

    private static final int MAX_VISIBLE_CODE_POINTS = 280;
    private static final String START = "<mark>";
    private static final String END = "</mark>";

    private SearchSnippet() {
    }

    /**
     * Builds a bounded snippet from canonical text.  FTS projection tokens are
     * deliberately not used as snippet input: CJK phrases are highlighted in
     * their original form and technical terms retain token boundaries.
     */
    static String canonical(String canonicalText, String rawQuery) {
        if (canonicalText == null || canonicalText.isEmpty()) {
            return "";
        }
        String text = Normalizer.normalize(canonicalText, Normalizer.Form.NFC);
        String query = rawQuery == null ? "" : Normalizer.normalize(rawQuery, Normalizer.Form.NFC).strip();
        if (query.isEmpty()) {
            return bounded(escape(text));
        }

        List<Range> candidates = new ArrayList<>();
        for (String segment : CjkBigramProjector.segments(query)) {
            boolean han = segment.codePoints().allMatch(SearchSnippet::isHan);
            int from = 0;
            while (from < text.length()) {
                int index = indexOfIgnoreCase(text, segment, from);
                if (index < 0) {
                    break;
                }
                int end = index + segment.length();
                if (han || hasTokenBoundaries(text, index, end)) {
                    candidates.add(new Range(index, end));
                }
                int next = text.offsetByCodePoints(index, 1);
                from = next > index ? next : index + 1;
            }
        }

        candidates.sort(Comparator.comparingInt(Range::start)
                .thenComparing(Comparator.comparingInt(Range::end).reversed()));
        List<Range> selected = new ArrayList<>();
        for (Range candidate : candidates) {
            if (selected.stream().noneMatch(existing -> overlaps(existing, candidate))) {
                selected.add(candidate);
            }
        }
        selected.sort(Comparator.comparingInt(Range::start));

        StringBuilder marked = new StringBuilder(text.length() + selected.size() * (START.length() + END.length()));
        int cursor = 0;
        for (Range range : selected) {
            appendEscaped(marked, text.substring(cursor, range.start()));
            marked.append(START);
            appendEscaped(marked, text.substring(range.start(), range.end()));
            marked.append(END);
            cursor = range.end();
        }
        appendEscaped(marked, text.substring(cursor));
        return bounded(marked.toString());
    }

    private static boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private static int indexOfIgnoreCase(String text, String term, int from) {
        for (int index = from; index < text.length();) {
            if (text.regionMatches(true, index, term, 0, term.length())) {
                return index;
            }
            index += Character.charCount(text.codePointAt(index));
        }
        return -1;
    }

    private static boolean hasTokenBoundaries(String text, int start, int end) {
        if (start > 0 && isTechnicalTokenCodePoint(text.codePointBefore(start))) {
            return false;
        }
        return end >= text.length() || !isTechnicalTokenCodePoint(text.codePointAt(end));
    }

    /** Matches the projector's non-Han searchable runs for exact technical-token semantics. */
    private static boolean isTechnicalTokenCodePoint(int codePoint) {
        int type = Character.getType(codePoint);
        boolean searchable = Character.isLetterOrDigit(codePoint)
                || type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
        return searchable && !isHan(codePoint);
    }

    private static boolean overlaps(Range left, Range right) {
        return left.start() < right.end() && right.start() < left.end();
    }

    private static void appendEscaped(StringBuilder target, String value) {
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            switch (codePoint) {
                case '&' -> target.append("&amp;");
                case '<' -> target.append("&lt;");
                case '>' -> target.append("&gt;");
                case '"' -> target.append("&quot;");
                case '\'' -> target.append("&#39;");
                default -> target.appendCodePoint(codePoint);
            }
            index += Character.charCount(codePoint);
        }
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        appendEscaped(escaped, value);
        return escaped.toString();
    }

    static String bounded(String snippet) {
        if (snippet == null || snippet.isEmpty()) {
            return "";
        }
        return boundedInternal(snippet, true);
    }

    private static String boundedInternal(String snippet, boolean escapeVisible) {
        ParsedSnippet parsed = parse(snippet);
        int total = parsed.visibleText().codePointCount(0, parsed.visibleText().length());
        if (total <= MAX_VISIBLE_CODE_POINTS) {
            return snippet;
        }

        Window window = parsed.highlights().isEmpty()
                ? new Window(0, MAX_VISIBLE_CODE_POINTS - 1)
                : centeredWindow(parsed.highlights().getFirst(), total);
        boolean prefixOmitted = window.start() > 0;
        boolean suffixOmitted = window.end() < total;

        StringBuilder bounded = new StringBuilder();
        if (prefixOmitted) {
            bounded.append('…');
        }
        int cursor = window.start();
        for (Highlight highlight : parsed.highlights()) {
            int start = Math.max(window.start(), highlight.start());
            int end = Math.min(window.end(), highlight.end());
            if (start >= end) {
                continue;
            }
            appendCodePointRange(bounded, parsed.visibleText(), cursor, start, escapeVisible);
            bounded.append(START);
            appendCodePointRange(bounded, parsed.visibleText(), start, end, escapeVisible);
            bounded.append(END);
            cursor = end;
        }
        appendCodePointRange(bounded, parsed.visibleText(), cursor, window.end(), escapeVisible);
        if (suffixOmitted) {
            bounded.append('…');
        }
        return bounded.toString();
    }

    private static Window centeredWindow(Highlight firstHighlight, int total) {
        int omittedMarkers = 2;
        int contentBudget = MAX_VISIBLE_CODE_POINTS - omittedMarkers;
        int center = firstHighlight.start() + (firstHighlight.end() - firstHighlight.start()) / 2;
        int start = Math.max(0, center - contentBudget / 2);
        int end = Math.min(total, start + contentBudget);
        start = Math.max(0, end - contentBudget);

        int actualOmittedMarkers = (start > 0 ? 1 : 0) + (end < total ? 1 : 0);
        if (actualOmittedMarkers < omittedMarkers) {
            contentBudget = MAX_VISIBLE_CODE_POINTS - actualOmittedMarkers;
            start = Math.max(0, center - contentBudget / 2);
            end = Math.min(total, start + contentBudget);
            start = Math.max(0, end - contentBudget);
        }
        return new Window(start, end);
    }

    private static ParsedSnippet parse(String snippet) {
        StringBuilder visibleText = new StringBuilder();
        List<Highlight> highlights = new ArrayList<>();
        int visible = 0;
        int openHighlight = -1;
        for (int index = 0; index < snippet.length();) {
            if (snippet.startsWith(START, index)) {
                if (openHighlight < 0) {
                    openHighlight = visible;
                }
                index += START.length();
                continue;
            }
            if (snippet.startsWith(END, index)) {
                if (openHighlight >= 0) {
                    highlights.add(new Highlight(openHighlight, visible));
                    openHighlight = -1;
                }
                index += END.length();
                continue;
            }
            String entity = htmlEntityAt(snippet, index);
            if (entity != null) {
                visibleText.appendCodePoint(entity.codePointAt(0));
                visible++;
                index += entity.length();
                continue;
            }
            int codePoint = snippet.codePointAt(index);
            visibleText.appendCodePoint(codePoint);
            visible++;
            index += Character.charCount(codePoint);
        }
        if (openHighlight >= 0) {
            highlights.add(new Highlight(openHighlight, visible));
        }
        return new ParsedSnippet(visibleText.toString(), List.copyOf(highlights));
    }

    private static void appendCodePointRange(StringBuilder target, String text,
                                             int start, int end, boolean escapeVisible) {
        if (start >= end) {
            return;
        }
        int startIndex = text.offsetByCodePoints(0, start);
        int endIndex = text.offsetByCodePoints(startIndex, end - start);
        if (escapeVisible) {
            appendEscaped(target, text.substring(startIndex, endIndex));
        } else {
            target.append(text, startIndex, endIndex);
        }
    }

    /** Decodes only entities emitted by {@link #appendEscaped}; unknown text stays literal. */
    private static String htmlEntityAt(String text, int index) {
        if (text.charAt(index) != '&') {
            return null;
        }
        if (text.startsWith("&amp;", index)) {
            return "&";
        }
        if (text.startsWith("&lt;", index)) {
            return "<";
        }
        if (text.startsWith("&gt;", index)) {
            return ">";
        }
        if (text.startsWith("&quot;", index)) {
            return "\"";
        }
        if (text.startsWith("&#39;", index)) {
            return "'";
        }
        return null;
    }

    private record ParsedSnippet(String visibleText, List<Highlight> highlights) {
    }

    private record Highlight(int start, int end) {
    }

    private record Window(int start, int end) {
    }

    private record Range(int start, int end) {
    }
}
