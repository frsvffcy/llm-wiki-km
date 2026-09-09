package org.km.llmwiki.source;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Library-neutral extraction result shared by all document parsers.
 *
 * <p>Besides the flat {@code content}/{@code metadata} pair that downstream normalization has
 * always consumed, a parser result now carries typed structural blocks and the application
 * parser provenance ({@code parserId}/{@code parserVersion}). Blocks are assigned by the
 * application in parse order; they are derived, rebuildable structure — never citation
 * authority, and vendor block identities or layout metadata never cross this boundary.
 */
public record ParsedDocument(String content, Map<String, String> metadata, List<ParsedBlock> blocks,
                             String parserId, String parserVersion) {

    private static final String LEGACY_PARSER_ID = "flat-legacy";

    public ParsedDocument {
        content = Objects.requireNonNull(content, "content must not be null");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata must not be null"));
        blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks must not be null"));
        for (int index = 0; index < blocks.size(); index++) {
            if (blocks.get(index).stableOrdinal() != index + 1) {
                throw new IllegalArgumentException("block ordinals must be 1-based, gapless and parse-ordered");
            }
        }
        parserId = requireNonBlank(parserId, "parser id must not be blank");
        parserVersion = requireNonBlank(parserVersion, "parser version must not be blank");
    }

    /**
     * Compatibility view for callers that only model flat text: the flat content is segmented
     * with the application's own structure rules so downstream policies never have to
     * reverse-engineer structure, under the absolute structural ceiling (never the caller's
     * configured, larger extraction bounds).
     */
    public ParsedDocument(String content, Map<String, String> metadata)
            throws DocumentParserResourceLimitException {
        this(content, metadata,
                FlatTextStructureSegmenter.segment(content,
                        ExtractionResourceProperties.ABSOLUTE_MAX_STRUCTURE_BLOCKS),
                LEGACY_PARSER_ID, "v0");
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
