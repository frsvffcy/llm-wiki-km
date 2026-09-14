package org.km.llmwiki.wiki;

/** A current finding deterministically refuses repair; only triage is available. */
public class RepairNotEligibleException extends RuntimeException {

    private final RepairRefusalReason reason;

    public RepairNotEligibleException(RepairRefusalReason reason) {
        super("repair is not available: " + reason.name());
        this.reason = reason;
    }

    public RepairRefusalReason reason() {
        return reason;
    }
}
