package org.km.llmwiki.source;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Selects a document parser from the registered implementations by MIME type or file extension.
 */
@Component
public class DocumentParserRegistry {

    private final List<DocumentParser> parsers;

    public DocumentParserRegistry(List<DocumentParser> parsers) {
        this.parsers = List.copyOf(parsers);
    }

    public Optional<DocumentParser> findParser(String mimeType, String fileName) {
        String normalizedMimeType = normalizeMimeType(mimeType);
        String extension = extensionOf(fileName);

        Optional<DocumentParser> parserByMimeType = normalizedMimeType.isEmpty()
                ? Optional.empty()
                : parsers.stream().filter(parser -> parser.supportsMimeType(normalizedMimeType)).findFirst();
        if (parserByMimeType.isPresent() || extension.isEmpty()) {
            return parserByMimeType;
        }
        return parsers.stream().filter(parser -> parser.supportsExtension(extension)).findFirst();
    }

    private static String normalizeMimeType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return "";
        }
        int parameterIndex = mimeType.indexOf(';');
        String mediaType = parameterIndex >= 0 ? mimeType.substring(0, parameterIndex) : mimeType;
        return mediaType.trim().toLowerCase(Locale.ROOT);
    }

    private static String extensionOf(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex <= 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }
}
