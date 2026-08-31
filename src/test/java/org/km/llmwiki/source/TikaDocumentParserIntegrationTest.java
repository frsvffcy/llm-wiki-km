package org.km.llmwiki.source;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class TikaDocumentParserIntegrationTest {

    private static final String LEGACY_DOC_GZIP_BASE64 =
            "H4sICEe0j2oAA3Rpa2EtaXNzdWUxMy1taW5pbWFsLmRvYwDtnE1sU0cQx8cfMUmIEydAcMNHDVS04iMJAqQeqiqBBEJakZSg9kgd28SubCeqnYTcckFCPVH10EulCiROSFURiCtpD9yAXDiUU3pEKiLllgN+/c+8fc6zDcSuIgzK/J7G+zG7O7v7Nn671pssPmpfunar628q43PyUcFqooArzwPZ4yRCRPtMXsGyLM6KQCzlveKfG3+YG7rccc++y+24qR6+8T80ErVSHJSvDodOWQdjl2wZ9LyunI1lta0Zd0jL53WTul6hr4TLbK2inMNdSAvkTxM6PIcUamhnvXmGWYx4V9NPINvq1hulWh5S6TpSlHefIRqkL2mUuilDcQpSnqL0PU1QArEepPeIZoaySM1CE6UpXAnEepAfhSYB3eEyXTd9J+0Ece2TK0JnUC4PbRpXioYlZwDW+xGzywRRL4VeJFCXbR+Q9CzlivEM6jrxqbL2BykmLU+hfKKkTe5Xni7IqLLSv6j0JC5arhVFnRxKcX5Sci8iHkNqEqXnSjRsdxLjS4iec3IyW3nYzsJGzuRxOTvnhIwoW9TkIccwZzGxkYTWPY6vUStH09ClkRpD6Wn0NAWbETqJz3jZ6GZQOmbye0paykj7I2jDXX4A6QvSLo+t1PY52MrKSDJiJS7jjrrqOznO/YnTuCt2GH1Joq47Z1ZGEjR3dwLt23bj0HGpNNIT0Cp1wezzVurdD+Ut0TIy7KOvICeHw6v7/Kf17payTuBMFry/n1p6vd/6rwYWGpebI8HRtvn237csbQuF+7pKChfvv7Ih8XRQGzVjGaQQeskn1zGivn8tL4dYHmfleZ2RpzgRH0y3ylcHf42Mmq+SzFCTv7Lxz6i/74V1DSGvygE883lXNY128thRjMoOMYpnv71zTCLvFGzx/qMc+5cF/r3CfJpV20BUfIIFXPFN3NNQSWVbz708Hbi6xg8VNufNNiiHjp1FOCsbpEmZiiz0x9GOd81WCFutOdQZR02ewKNVWu+X7aM96dX2eEi21zOyHU3J9jKCzT1vupIyqRGMv7fxSpiWF9HxpLT55Iv0fhaOlxrxSNpPvpCnItfJifhoOUTK/+NGYGHTHbpD89TRu/6t89rcu/pnifuk3/OKoiiKoiiKsqEp4ETtb648XXLO0uVfX6yMJEM3f2ykgx/f/ovPKGGjY+GDHx8ynr58mz1WFEVRFEVRFEVRFKVW3nT+9z5+8PiX7h2hn37G+f/Qym96/lcURVEURVEURVGU9xM550N8ZL8cz2/U85vy/AY9/1+HJoTNkM1kezezn14rpM3o2xF2QLaQ7X3PnuqdkO1G/wFCdjnZAdkJ2QXZDfnQ6Fn2uuKFOv0fhI0KOzRMij/GoHhesLdrLXRSg8dpi9dQoMl2iViw1adeVecjyLyJH6HzFKVxSlOiJrsOrVi97vFUU0degzdeC9+Id0ucBhDGaFocPl7lgfI6usjr4b+hWuyTy34DjYnVjHjDzImX9MWix01e/H6zb2jmE9jnGfeT4x9Tpemi/fKR19afT2G/1vkPu+wriqIo9eE/Csdv+QBMAAA=";

    @TempDir
    Path tempDirectory;

    private final TikaDocumentParser parser = new TikaDocumentParser();

    @Test
    void extractsTextAndMetadataFromPdf() throws Exception {
        Path source = tempDirectory.resolve("report.pdf");
        createPdf(source, "PDF integration content");

        assertParsed(source, "PDF integration content", "application/pdf");
    }

    @Test
    void extractsTextAndMetadataFromDoc() throws Exception {
        Path source = tempDirectory.resolve("legacy.doc");
        createDoc(source);

        assertParsed(source, "Runtime data", "application/msword");
    }

    @Test
    void extractsTextAndMetadataFromDocx() throws Exception {
        Path source = tempDirectory.resolve("modern.docx");
        createDocx(source, "DOCX integration content");

        assertParsed(source, "DOCX integration content",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    @Test
    void extractsTextAndMetadataFromMarkdownPlainTextAndHtml() throws Exception {
        Path markdown = write("notes.md", "# Markdown integration content");
        Path plainText = write("notes.txt", "Plain text integration content");
        Path html = write("notes.html", "<html><title>HTML title</title><body>HTML integration content</body></html>");

        assertParsed(markdown, "Markdown integration content", "text/");
        assertParsed(plainText, "Plain text integration content", "text/");
        assertParsed(html, "HTML integration content", "text/html");
    }

    @Test
    void returnsAnEmptyDocumentWithParseErrorInsteadOfPropagatingTikaParsingFailures() throws Exception {
        Path source = write("broken.pdf", "%PDF-not-a-valid-document");

        ParsedDocument parsed = parser.parse(source);

        assertThat(parsed.content()).isEmpty();
        assertThat(parsed.metadata()).containsKey("parseError");
    }

    private void assertParsed(Path source, String expectedContent, String expectedMimeType) throws Exception {
        ParsedDocument parsed = parser.parse(source);

        assertThat(parsed.content()).contains(expectedContent);
        assertThat(parsed.metadata())
                .containsEntry("resourceName", source.getFileName().toString());
        assertThat(parsed.metadata().get("Content-Type")).startsWith(expectedMimeType);
    }

    private Path write(String fileName, String content) throws Exception {
        Path source = tempDirectory.resolve(fileName);
        Files.writeString(source, content);
        return source;
    }

    private static void createPdf(Path source, String content) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 720);
                stream.showText(content);
                stream.endText();
            }
            document.save(source.toFile());
        }
    }

    private static void createDoc(Path source) throws Exception {
        byte[] compressed = Base64.getDecoder().decode(LEGACY_DOC_GZIP_BASE64);
        try (InputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            Files.copy(input, source);
        }
    }

    private static void createDocx(Path source, String content) throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText(content);
            try (OutputStream output = Files.newOutputStream(source)) {
                document.write(output);
            }
        }
    }
}
