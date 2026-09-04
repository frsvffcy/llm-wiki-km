package org.km.llmwiki.source;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Parses a source document without exposing a concrete parsing library to the processing flow.
 */
public interface DocumentParser {

    /**
     * Returns whether this parser supports the normalized MIME type.
     */
    boolean supportsMimeType(String mimeType);

    /**
     * Returns whether this parser supports the normalized extension without a leading dot.
     */
    boolean supportsExtension(String extension);

    /**
     * Extracts the document text and metadata from a source file.
     */
    ParsedDocument parse(Path source) throws IOException;
}
