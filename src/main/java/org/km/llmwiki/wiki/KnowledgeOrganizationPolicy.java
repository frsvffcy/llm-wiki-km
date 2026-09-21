package org.km.llmwiki.wiki;

import java.util.List;
import java.util.Set;

/**
 * 知識組織 metadata 的單一 authority 契約（#569）。
 *
 * <p>Decision（见 Issue #569 decision boundary 允許的「只保留 Wiki tag」結論）：
 *
 * <ol>
 *   <li><strong>Source Document 沒有 tags。</strong>來源文件只攜帶身份與 provenance
 *   （檔名、路徑、雜湊、抽取狀態）；不新增 {@code document_tag} table、不新增 migration。
 *   任何「文件層級分類真相」都不存在，因此不可能與 Wiki 側漂移。</li>
 *   <li><strong>KnowledgeCandidate 只有 controlled type，沒有 tags。</strong>
 *   LLM 產生的 {@code type} 是 bounded suggestion（closed enum＋application validation），
 *   不是 durable classification authority。</li>
 *   <li><strong>自動分類永遠是 suggestion，不是 authority。</strong>
 *   Tag/classification 建議經由唯讀 projection（{@code /api/v1/organization/tag-suggestions}）
 *   呈現：ephemeral、不持久化、不影響 retrieval（無 hidden boost/filter），並攜帶
 *   staleness（analysis 綁定；文件在分析後變動即標示 stale，不得沿用）。</li>
 *   <li><strong>手動 tag 修改的唯一落點是 REVIEW-stage proposal 的 normalized tags。</strong>
 *   人類在審核階段調整的 tags 經 {@link KnowledgeTagPolicy} 驗證後寫入
 *   {@code knowledge_proposal.normalized_data_json.tags}；後續建立／重新產生的 draft
 *   承接新 tags，已存在的 draft snapshot 不變（immutable snapshot 語意，以 regenerate 取代）。</li>
 *   <li><strong>Canonical durable tags 只存在於 Wiki 側。</strong>
 *   Draft frontmatter → published frontmatter 是唯一持久真相；proposal tags 只是
 *   通往該真相的人類可控輸入。</li>
 * </ol>
 *
 * <p>Retrieval 不變性：tags 建議與 tags 修改都不觸及 FTS／embedding／graph projection；
 * 任何 retrieval 行為改變必須另有 retrieval regression evidence。
 */
public final class KnowledgeOrganizationPolicy {

    /**
     * 允許人類直接修改 tags 的 proposal 來源種類。REPAIR proposal 的 normalized data
     * 由 deterministic repair plan 重建（lineage authority），人類覆寫會破壞 lineage，
     * 因此排除在可編輯集合之外。
     */
    private static final Set<String> TAG_EDITABLE_SOURCE_KINDS = Set.of("DOCUMENT_ANALYSIS", "ASK");

    /**
     * 允許人類修改 tags 的 proposal 狀態：只有 REVIEW（人類審核中）是合法的人控 mutation point。
     * DRAFT 尚未進入審核、APPROVED／REJECTED 已是 terminal 不可變（draft conversion source 完整性）。
     */
    private static final Set<KnowledgeProposalStatus> TAG_EDITABLE_STATUSES = Set.of(KnowledgeProposalStatus.REVIEW);

    private KnowledgeOrganizationPolicy() {
    }

    /** Proposal 狀態是否允許人類修改 tags。 */
    public static boolean isTagEditableStatus(KnowledgeProposalStatus status) {
        return status != null && TAG_EDITABLE_STATUSES.contains(status);
    }

    /** Proposal 來源種類是否允許人類修改 tags（未知種類 fail-closed 為不可編輯）。 */
    public static boolean isTagEditableSourceKind(String sourceKind) {
        return sourceKind != null && TAG_EDITABLE_SOURCE_KINDS.contains(sourceKind);
    }

    /** 人類可編輯的狀態集合（供 API 文件／Browser capability 呈現，不作為授權 token）。 */
    public static List<KnowledgeProposalStatus> tagEditableStatuses() {
        return List.copyOf(TAG_EDITABLE_STATUSES);
    }
}
