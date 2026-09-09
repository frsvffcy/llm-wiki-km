package org.km.llmwiki.source;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Application-owned segmentation of parser flat text into typed structural blocks.
 *
 * <p>The segmentation rules (page separators, blank-line paragraph boundaries, Markdown
 * heading detection, boundary whitespace trimming) are the exact rules the current chunker
 * has always applied to flat text; materializing them as typed blocks at the parser boundary
 * removes the need for every chunking policy to reverse-engineer structure from text. The
 * segmenter never invents kinds a flat-text parser cannot observe (TABLE/FIGURE/CAPTION) and
 * never fabricates bounding boxes.
 */
final class FlatTextStructureSegmenter {

    static final Pattern PARAGRAPH_BOUNDARY =
            Pattern.compile("(?:\\r\\n|\\r|\\n)[\\t ]*(?:\\r\\n|\\r|\\n)+");
    static final Pattern MARKDOWN_HEADING =
            Pattern.compile("^[\\t ]{0,3}(#{1,6})[\\t ]+(.+?)[\\t ]*#*[\\t ]*$");

    private FlatTextStructureSegmenter() {
    }

    static List<ParsedBlock> segment(String content, int maxBlocks)
            throws DocumentParserResourceLimitException {
        List<ParsedBlock> blocks = new ArrayList<>();
        String[] pages = content.split("\\f", -1);
        for (int pageIndex = 0; pageIndex < pages.length; pageIndex++) {
            for (String paragraph : PARAGRAPH_BOUNDARY.split(pages[pageIndex])) {
                String block = trimBoundaryWhitespace(paragraph);
                if (block.isEmpty()) {
                    continue;
                }
                blocks.add(toBlock(blocks.size() + 1, block, pageIndex + 1));
                if (blocks.size() > maxBlocks) {
                    throw new DocumentParserResourceLimitException(
                            DocumentParserResourceLimitException.Resource.STRUCTURE_BLOCKS);
                }
            }
        }
        return List.copyOf(blocks);
    }

    private static ParsedBlock toBlock(int ordinal, String block, int pageNo) {
        Matcher heading = MARKDOWN_HEADING.matcher(block);
        if (heading.matches()) {
            return new ParsedBlock(ordinal, ParsedBlockKind.HEADING, heading.group(1).length(),
                    pageNo, block, heading.group(2), null);
        }
        return new ParsedBlock(ordinal, ParsedBlockKind.PARAGRAPH, 0, pageNo, block, null, null);
    }

    private static String trimBoundaryWhitespace(String content) {
        return content.replaceFirst("^[\\s&&[^\\f]]+", "")
                .replaceFirst("[\\s&&[^\\f]]+$", "");
    }
}
