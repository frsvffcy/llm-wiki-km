package org.km.llmwiki.rag;

/** Auditable context budget; character count is the deterministic token proxy. */
public record EvidenceBudget(int maxItems, int maxCharacters, int usedItems,
                             int usedCharacters, int estimatedTokens, boolean truncated) {
}
