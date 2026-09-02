package org.km.llmwiki.rag;

/** Controlled defaults and hard ceilings for bounded Answer context assembly. */
final class RetrievalBudgetPolicy {

    static final int DEFAULT_MAX_ITEMS = 8;
    static final int DEFAULT_MAX_CHARACTERS = 12_000;
    static final int HARD_MAX_ITEMS = 50;
    static final int HARD_MAX_CHARACTERS = 100_000;
    private static final int CANDIDATE_MULTIPLIER = 4;
    private static final int SEARCH_MAX_CANDIDATES = 200;

    private RetrievalBudgetPolicy() {
    }

    static ResolvedBudget resolve(RetrievalRequest request) {
        int maxItems = request.maxItems() == null ? DEFAULT_MAX_ITEMS : request.maxItems();
        int maxCharacters = request.maxCharacters() == null
                ? DEFAULT_MAX_CHARACTERS : request.maxCharacters();
        if (maxItems < 1 || maxItems > HARD_MAX_ITEMS) {
            throw new IllegalArgumentException("maxItems must be between 1 and " + HARD_MAX_ITEMS);
        }
        if (maxCharacters < 1 || maxCharacters > HARD_MAX_CHARACTERS) {
            throw new IllegalArgumentException(
                    "maxCharacters must be between 1 and " + HARD_MAX_CHARACTERS);
        }
        int candidateLimit = Math.min(SEARCH_MAX_CANDIDATES,
                Math.multiplyExact(maxItems, CANDIDATE_MULTIPLIER));
        return new ResolvedBudget(maxItems, maxCharacters, candidateLimit);
    }

    record ResolvedBudget(int maxItems, int maxCharacters, int candidateLimit) {
    }
}
