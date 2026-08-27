package org.km.llmwiki.source;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 控制判定 PDF 需進行 OCR 的文字品質門檻。 */
@ConfigurationProperties("app.extraction.scanned-pdf")
public class ScannedPdfDetectionProperties {

    private int minimumTextCharacters = 50;

    public int getMinimumTextCharacters() {
        return minimumTextCharacters;
    }

    public void setMinimumTextCharacters(int minimumTextCharacters) {
        if (minimumTextCharacters < 0) {
            throw new IllegalArgumentException("掃描 PDF 的最小文字字元數不得為負數");
        }
        this.minimumTextCharacters = minimumTextCharacters;
    }
}
