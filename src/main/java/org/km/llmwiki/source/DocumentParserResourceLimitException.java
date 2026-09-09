package org.km.llmwiki.source;

import java.io.IOException;

/**
 * Typed parser-neutral signal that a document exceeded a synchronous extraction resource bound.
 */
public class DocumentParserResourceLimitException extends IOException {

    public enum Resource {
        INPUT_BYTES,
        OUTPUT_CHARACTERS,
        METADATA_CHARACTERS,
        STRUCTURE_BLOCKS
    }

    private final Resource resource;

    public DocumentParserResourceLimitException(Resource resource) {
        super("document extraction resource limit exceeded");
        this.resource = resource;
    }

    public Resource resource() {
        return resource;
    }
}
