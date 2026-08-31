package org.km.llmwiki.search;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The single application-side projection used by both FTS writers and queries.
 *
 * <p>Consecutive Han code points are emitted as overlapping bigrams. Other
 * letters, numbers and combining marks stay in one complete token, preserving
 * exact technical-token semantics. NFC and {@link Locale#ROOT} make the result
 * deterministic across machines and locales.
 */
public final class CjkBigramProjector {

    public static final String ALGORITHM = "cjk-bigram";
    public static final String VERSION = "cjk-bigram-v1";

    private CjkBigramProjector() {
    }

    /** Returns the space-delimited FTS projection for canonical or query text. */
    public static String transform(String input) {
        if (input == null) {
            return null;
        }
        return String.join(" ", tokens(input));
    }

    /** Returns deterministic projected terms without exposing mutable state. */
    public static List<String> tokens(String input) {
        if (input == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String segment : segments(input)) {
            boolean han = segment.codePoints().allMatch(CjkBigramProjector::isHan);
            flush(result, new StringBuilder(segment), han);
        }
        return List.copyOf(result);
    }

    /** Searchable runs shared by query highlighting and projection. */
    static List<String> segments(String input) {
        if (input == null) {
            return List.of();
        }
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFC);
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean han = false;
        for (int codePoint : normalized.codePoints().toArray()) {
            if (!isSearchable(codePoint)) {
                flushSegment(result, current);
                continue;
            }
            boolean codePointHan = isHan(codePoint);
            if (!current.isEmpty() && codePointHan != han) {
                flushSegment(result, current);
            }
            current.appendCodePoint(codePoint);
            han = codePointHan;
        }
        flushSegment(result, current);
        return List.copyOf(result);
    }

    private static boolean isSearchable(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isLetterOrDigit(codePoint)
                || type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    private static boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private static void flushSegment(List<String> result, StringBuilder current) {
        if (!current.isEmpty()) {
            result.add(current.toString());
            current.setLength(0);
        }
    }

    private static void flush(List<String> result, StringBuilder current, boolean han) {
        if (current.isEmpty()) {
            return;
        }
        if (han) {
            int[] codePoints = current.codePoints().toArray();
            if (codePoints.length == 1) {
                result.add(new String(codePoints, 0, 1));
            } else {
                for (int index = 0; index < codePoints.length - 1; index++) {
                    result.add(new String(codePoints, index, 2));
                }
            }
        } else {
            result.add(current.toString().toLowerCase(Locale.ROOT));
        }
        current.setLength(0);
    }
}
