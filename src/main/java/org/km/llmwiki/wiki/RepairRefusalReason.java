package org.km.llmwiki.wiki;

/**
 * Typed refusal reasons for repair eligibility (#384). The set is closed and backend-owned:
 * the Browser maps these codes to presentation copy but never decides eligibility itself,
 * and unknown future codes fail closed (no repair action rendered).
 */
public enum RepairRefusalReason {
    AMBIGUOUS_TARGET("連結目標不明確，無法推導修復動作"),
    SEMANTIC_JUDGMENT_REQUIRED("需要語意判斷，無法自動修復"),
    TARGET_NOT_READABLE("目標內容無法讀取"),
    AMBIGUOUS_IDENTITY("識別重複，無法判定修復對象"),
    NO_RESOLVABLE_LINEAGE("找不到可重建的 governed 來源");

    private final String description;

    RepairRefusalReason(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
