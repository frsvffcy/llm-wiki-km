package org.km.llmwiki.source;

import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Apache Tika implementation of the library-neutral document parsing contract.
 */
@Component
public class TikaDocumentParser implements DocumentParser {

    private static final Set<String> SUPPORTED_MIME_TYPES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/html",
            "text/markdown",
            "text/plain");
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "doc", "docx", "html", "htm", "md", "markdown", "pdf", "txt");
    private static final int UNLIMITED_CONTENT_LENGTH = -1;

    @Override
    public boolean supportsMimeType(String mimeType) {
        return mimeType != null && SUPPORTED_MIME_TYPES.contains(mimeType.toLowerCase(Locale.ROOT));
    }

    @Override
    public boolean supportsExtension(String extension) {
        return extension != null && SUPPORTED_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
    }

    @Override
    public ParsedDocument parse(Path source) throws IOException {
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, source.getFileName().toString());
        BodyContentHandler contentHandler = new BodyContentHandler(UNLIMITED_CONTENT_LENGTH);

        try (TikaInputStream input = TikaInputStream.get(source)) {
            new AutoDetectParser().parse(input, contentHandler, metadata, new ParseContext());
            return new ParsedDocument(contentHandler.toString(), copyMetadata(metadata));
        } catch (TikaException | SAXException exception) {
            Map<String, String> failedMetadata = copyMetadata(metadata);
            failedMetadata.put("parseError", exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage());
            return new ParsedDocument("", failedMetadata);
        }
    }

    private static Map<String, String> copyMetadata(Metadata metadata) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : metadata.names()) {
            values.put(name, String.join(", ", metadata.getValues(name)));
        }
        return values;
    }
}
