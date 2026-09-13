package org.km.llmwiki.wiki;

import java.util.List;

/**
 * One or more citation references from the Ask result are unknown, stale, or
 * out-of-workspace: the ingress fails closed instead of creating a proposal that looks
 * grounded. Only the client-supplied identities are surfaced.
 */
public class AskCitationInvalidException extends RuntimeException {

    private final List<String> invalidIdentities;

    public AskCitationInvalidException(List<String> invalidIdentities) {
        super("Ask citations failed validation: " + String.join(", ", invalidIdentities));
        this.invalidIdentities = List.copyOf(invalidIdentities);
    }

    public List<String> invalidIdentities() {
        return invalidIdentities;
    }
}
