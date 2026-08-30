package org.km.llmwiki.search;

import java.util.ArrayList;
import java.util.List;

/** Keeps FTS snippets bounded by Unicode code point without cutting a surrogate pair or marker. */
final class SearchSnippet {

    private static final int MAX_VISIBLE_CODE_POINTS = 280;
    private static final String START = "<mark>";
    private static final String END = "</mark>";

    private SearchSnippet() {
    }

    static String bounded(String snippet) {
        if (snippet == null || snippet.isEmpty()) {
            return "";
        }
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
            appendCodePointRange(bounded, parsed.visibleText(), cursor, start);
            bounded.append(START);
            appendCodePointRange(bounded, parsed.visibleText(), start, end);
            bounded.append(END);
            cursor = end;
        }
        appendCodePointRange(bounded, parsed.visibleText(), cursor, window.end());
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
                                             int start, int end) {
        if (start >= end) {
            return;
        }
        int startIndex = text.offsetByCodePoints(0, start);
        int endIndex = text.offsetByCodePoints(startIndex, end - start);
        target.append(text, startIndex, endIndex);
    }

    private record ParsedSnippet(String visibleText, List<Highlight> highlights) {
    }

    private record Highlight(int start, int end) {
    }

    private record Window(int start, int end) {
    }
}
