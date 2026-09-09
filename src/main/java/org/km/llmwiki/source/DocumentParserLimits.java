package org.km.llmwiki.source;

/**
 * Application-owned hard limits for one synchronous document parse.
 *
 * <p>The limits deliberately use finite positive values. A parser implementation must not
 * interpret a sentinel value as unlimited because this contract protects the synchronous
 * request path from parser expansion as well as upload size.</p>
 */
public record DocumentParserLimits(long maxInputBytes, int maxOutputCharacters,
                                   int maxMetadataCharacters) {

    public DocumentParserLimits {
        if (maxInputBytes < 1) {
            throw new IllegalArgumentException("文件抽取的最大輸入大小必須大於零");
        }
        if (maxOutputCharacters < 1) {
            throw new IllegalArgumentException("文件抽取的最大輸出字元數必須大於零");
        }
        if (maxMetadataCharacters < 1) {
            throw new IllegalArgumentException("文件抽取的最大 metadata 字元數必須大於零");
        }
    }
}
