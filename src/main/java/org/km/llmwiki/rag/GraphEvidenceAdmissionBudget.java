package org.km.llmwiki.rag;

/**
 * Hard admission budget for graph-derived evidence; graph contribution can never expand an
 * {@link EvidenceBundle} through traversal paths or candidate volume.
 */
public record GraphEvidenceAdmissionBudget(int maxItems, int maxCharacters) {

    public static final int DEFAULT_MAX_ITEMS = 4;
    public static final int DEFAULT_MAX_CHARACTERS = 4_000;
    public static final int HARD_MAX_ITEMS = 8;
    public static final int HARD_MAX_CHARACTERS = 8_000;

    public GraphEvidenceAdmissionBudget {
        if (maxItems < 1 || maxItems > HARD_MAX_ITEMS) {
            throw new IllegalArgumentException(
                    "Graph evidence admission item budget is outside the hard bounds");
        }
        if (maxCharacters < 1 || maxCharacters > HARD_MAX_CHARACTERS) {
            throw new IllegalArgumentException(
                    "Graph evidence admission character budget is outside the hard bounds");
        }
    }

    public static GraphEvidenceAdmissionBudget defaults() {
        return new GraphEvidenceAdmissionBudget(DEFAULT_MAX_ITEMS, DEFAULT_MAX_CHARACTERS);
    }
}
