package org.km.llmwiki.source;

import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
    private static final DocumentParserLimits DEFAULT_LIMITS = new DocumentParserLimits(
            ExtractionResourceProperties.ABSOLUTE_MAX_INPUT_BYTES,
            ExtractionResourceProperties.ABSOLUTE_MAX_OUTPUT_CHARACTERS,
            ExtractionResourceProperties.ABSOLUTE_MAX_METADATA_CHARACTERS);

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
        return parse(source, DEFAULT_LIMITS);
    }

    @Override
    public ParsedDocument parse(Path source, DocumentParserLimits limits) throws IOException {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(limits, "limits must not be null");
        if (java.nio.file.Files.size(source) > limits.maxInputBytes()) {
            throw new DocumentParserResourceLimitException(
                    DocumentParserResourceLimitException.Resource.INPUT_BYTES);
        }

        Metadata metadata = new Metadata();
        metadata.setMetadataWriteFilter(new BoundedMetadataWriteFilter(limits.maxMetadataCharacters()));
        try {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, source.getFileName().toString());
        } catch (MetadataLimitExceededException exception) {
            throw new DocumentParserResourceLimitException(
                    DocumentParserResourceLimitException.Resource.METADATA_CHARACTERS);
        }
        BodyContentHandler contentHandler = new BodyContentHandler(limits.maxOutputCharacters());

        try (InputStream file = java.nio.file.Files.newInputStream(source);
             TikaInputStream input = TikaInputStream.get(new BoundedInputStream(file, limits.maxInputBytes()))) {
            new AutoDetectParser().parse(input, contentHandler, metadata, new ParseContext());
            return new ParsedDocument(contentHandler.toString(), copyMetadata(metadata));
        } catch (MetadataLimitExceededException exception) {
            throw new DocumentParserResourceLimitException(
                    DocumentParserResourceLimitException.Resource.METADATA_CHARACTERS);
        } catch (TikaException | SAXException exception) {
            if (WriteLimitReachedException.isWriteLimitReached(exception)) {
                throw new DocumentParserResourceLimitException(
                        DocumentParserResourceLimitException.Resource.OUTPUT_CHARACTERS);
            }
            Map<String, String> failedMetadata = copyMetadata(metadata);
            failedMetadata.put("parseError", "parser failure");
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
