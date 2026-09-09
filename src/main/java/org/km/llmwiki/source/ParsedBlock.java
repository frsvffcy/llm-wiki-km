package org.km.llmwiki.source;

import java.util.Objects;

/**
 * One application-owned structural block of a parsed document.
 *
 * <p>{@code stableOrdinal} is assigned by the application in parse order (1-based, gapless);
 * vendor block ids never cross the parser boundary. {@code boundingBox} is an optional
 * structural locator: a parser without layout capability must leave it {@code null} and must
 * never fabricate location precision. Block text and counts stay bounded by the same
 * {@link DocumentParserLimits} that bound flat extraction output.
 */
public record ParsedBlock(int stableOrdinal, ParsedBlockKind kind, int headingLevel, Integer pageNo,
                          String text, String headingTitle, String boundingBox) {

    public ParsedBlock {
        if (stableOrdinal < 1) {
            throw new IllegalArgumentException("block ordinal must be positive");
        }
        if (kind == null) {
            throw new IllegalArgumentException("block kind must not be null");
        }
        if (headingLevel < 0 || headingLevel > 6) {
            throw new IllegalArgumentException("heading level must be between 0 and 6");
        }
        text = Objects.requireNonNull(text, "block text must not be null");
        if (kind == ParsedBlockKind.HEADING && headingLevel < 1) {
            throw new IllegalArgumentException("heading blocks require a heading level");
        }
        if (kind != ParsedBlockKind.HEADING && headingTitle != null) {
            throw new IllegalArgumentException("only heading blocks carry a heading title");
        }
    }
}
