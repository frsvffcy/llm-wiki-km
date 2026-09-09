package org.km.llmwiki.rag;

import java.util.Objects;
import java.util.Optional;

/** Mutually exclusive result of one authority revalidation: current evidence or a typed reason. */
record RevalidationOutcome(CandidateAuthorityRevalidator.AuthorityEvidence current,
                           AuthorityRejectionReason rejection) {

    RevalidationOutcome {
        if ((current == null) == (rejection == null)) {
            throw new IllegalArgumentException("exactly one of evidence or rejection must be present");
        }
    }

    static RevalidationOutcome accepted(CandidateAuthorityRevalidator.AuthorityEvidence evidence) {
        return new RevalidationOutcome(Objects.requireNonNull(evidence), null);
    }

    static RevalidationOutcome rejected(AuthorityRejectionReason reason) {
        return new RevalidationOutcome(null, Objects.requireNonNull(reason));
    }

    Optional<CandidateAuthorityRevalidator.AuthorityEvidence> evidence() {
        return Optional.ofNullable(current);
    }

    AuthorityRejectionReason rejectionReason() {
        return rejection;
    }

    boolean wasRejected() {
        return rejection != null;
    }
}
