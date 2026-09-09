package org.km.llmwiki.rag;

/** Mutually exclusive result of one terminal publication currentness check. */
record PublicationOutcome(AuthorityRejectionReason rejection) {

    static final PublicationOutcome CURRENT = new PublicationOutcome(null);

    PublicationOutcome(AuthorityRejectionReason rejection) {
        this.rejection = rejection;
    }

    static PublicationOutcome rejected(AuthorityRejectionReason reason) {
        return new PublicationOutcome(reason);
    }

    AuthorityRejectionReason rejectionReason() {
        return rejection;
    }

    boolean wasRejected() {
        return rejection != null;
    }
}
