package org.km.llmwiki.wiki;

/** The finding a repair command names is no longer present in current backend state. */
public class RepairFindingStaleException extends RuntimeException {

    public RepairFindingStaleException(String message) {
        super(message);
    }
}
