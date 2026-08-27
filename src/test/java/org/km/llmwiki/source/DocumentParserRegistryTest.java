package org.km.llmwiki.source;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentParserRegistryTest {

    @Test
    void selectsParserByNormalizedMimeType() {
        DocumentParser pdfParser = parser("application/pdf", "pdf");
        DocumentParserRegistry registry = new DocumentParserRegistry(List.of(pdfParser));

        assertThat(registry.findParser("Application/PDF; charset=binary", "report.unknown"))
                .containsSame(pdfParser);
    }

    @Test
    void selectsParserByCaseInsensitiveExtensionWhenMimeTypeIsUnavailable() {
        DocumentParser markdownParser = parser("text/markdown", "md");
        DocumentParserRegistry registry = new DocumentParserRegistry(List.of(markdownParser));

        assertThat(registry.findParser(null, "notes.MD"))
                .containsSame(markdownParser);
    }

    @Test
    void givesMimeTypeSelectionPrecedenceOverExtensionFallback() {
        DocumentParser extensionParser = parser("application/x-custom", "pdf");
        DocumentParser mimeParser = parser("application/pdf", "not-pdf");
        DocumentParserRegistry registry = new DocumentParserRegistry(List.of(extensionParser, mimeParser));

        assertThat(registry.findParser("application/pdf", "report.pdf"))
                .containsSame(mimeParser);
    }

    @Test
    void returnsEmptyWhenNoParserSupportsTheDocument() {
        DocumentParserRegistry registry = new DocumentParserRegistry(List.of(parser("application/pdf", "pdf")));

        assertThat(registry.findParser("image/png", "photo.png")).isEmpty();
    }

    @Test
    void makesMetadataImmutable() {
        Map<String, String> metadata = Map.of("title", "設計文件");
        ParsedDocument document = new ParsedDocument("內容", metadata);

        assertThat(document.content()).isEqualTo("內容");
        assertThat(document.metadata()).containsEntry("title", "設計文件");
        assertThat(document.metadata()).isUnmodifiable();
    }

    private static DocumentParser parser(String mimeType, String extension) {
        return new DocumentParser() {
            @Override
            public boolean supportsMimeType(String candidate) {
                return mimeType.equals(candidate);
            }

            @Override
            public boolean supportsExtension(String candidate) {
                return extension.equals(candidate);
            }

            @Override
            public ParsedDocument parse(Path source) throws IOException {
                return new ParsedDocument("", Map.of());
            }
        };
    }
}
