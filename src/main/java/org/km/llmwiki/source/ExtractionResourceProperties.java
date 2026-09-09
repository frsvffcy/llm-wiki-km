package org.km.llmwiki.source;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Finite hard limits for synchronous document extraction.
 *
 * <p>These ceilings are intentionally stricter than the multipart upload defaults. Raising the
 * upload limit therefore cannot make the synchronous parser unbounded.</p>
 */
@ConfigurationProperties("app.extraction.resource")
public class ExtractionResourceProperties {

    public static final long ABSOLUTE_MAX_INPUT_BYTES = 50L * 1024 * 1024;
    public static final int ABSOLUTE_MAX_OUTPUT_CHARACTERS = 5_000_000;
    public static final int ABSOLUTE_MAX_METADATA_CHARACTERS = 100_000;

    private long maxInputBytes = ABSOLUTE_MAX_INPUT_BYTES;
    private int maxOutputCharacters = ABSOLUTE_MAX_OUTPUT_CHARACTERS;
    private int maxMetadataCharacters = ABSOLUTE_MAX_METADATA_CHARACTERS;

    public long getMaxInputBytes() {
        return maxInputBytes;
    }

    public void setMaxInputBytes(long maxInputBytes) {
        requireWithinCeiling("最大輸入大小", maxInputBytes, ABSOLUTE_MAX_INPUT_BYTES);
        this.maxInputBytes = maxInputBytes;
    }

    public int getMaxOutputCharacters() {
        return maxOutputCharacters;
    }

    public void setMaxOutputCharacters(int maxOutputCharacters) {
        requireWithinCeiling("最大輸出字元數", maxOutputCharacters, ABSOLUTE_MAX_OUTPUT_CHARACTERS);
        this.maxOutputCharacters = maxOutputCharacters;
    }

    public int getMaxMetadataCharacters() {
        return maxMetadataCharacters;
    }

    public void setMaxMetadataCharacters(int maxMetadataCharacters) {
        requireWithinCeiling("最大 metadata 字元數", maxMetadataCharacters,
                ABSOLUTE_MAX_METADATA_CHARACTERS);
        this.maxMetadataCharacters = maxMetadataCharacters;
    }

    public DocumentParserLimits limits() {
        return new DocumentParserLimits(maxInputBytes, maxOutputCharacters, maxMetadataCharacters);
    }

    private static void requireWithinCeiling(String name, long value, long ceiling) {
        if (value < 1 || value > ceiling) {
            throw new IllegalArgumentException(name + "必須介於 1 與 " + ceiling + " 之間");
        }
    }
}
