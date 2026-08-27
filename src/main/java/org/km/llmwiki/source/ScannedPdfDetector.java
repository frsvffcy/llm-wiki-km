package org.km.llmwiki.source;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/** 偵測已有頁面但可抽取文字不足、必須先進行 OCR 的 PDF。 */
@Component
public class ScannedPdfDetector {

    private final ScannedPdfDetectionProperties properties;

    public ScannedPdfDetector(ScannedPdfDetectionProperties properties) {
        this.properties = properties;
    }

    public boolean requiresOcr(Path source, String mimeType, String fileName, String extractedContent) {
        if (!isPdf(mimeType, fileName) || textCharacterCount(extractedContent)
                >= properties.getMinimumTextCharacters()) {
            return false;
        }

        try (PDDocument document = Loader.loadPDF(source.toFile())) {
            return document.getNumberOfPages() > 0;
        } catch (IOException exception) {
            return false;
        }
    }

    private static boolean isPdf(String mimeType, String fileName) {
        if ("application/pdf".equalsIgnoreCase(mimeType)) {
            return true;
        }
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    private static long textCharacterCount(String content) {
        return content.codePoints().filter(codePoint -> !Character.isWhitespace(codePoint)).count();
    }
}
