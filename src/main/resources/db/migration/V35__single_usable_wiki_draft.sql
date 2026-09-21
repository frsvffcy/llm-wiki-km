-- #601：atomic single-usable-draft contract for (workspace_id, proposal_id)。
-- 同一 workspace + proposal 在任何時刻最多只有一筆可用（DRAFT/READY）草稿；
-- auto-draft 與 manual create 共用此 storage authority（先查後寫無法證明併發冪等，
-- Browser lock/debounce 不能當 correctness 修正）。
-- 已發布 migration 不得修改；dedup 與 index 皆在此新 migration 內完成。

-- 1. Historical duplicate usable drafts：deterministic dedup，只保留最新 id。
--    較舊的重複列轉為 INVALIDATED（內容保留、可稽核；絕不自動 publish，也絕不發明
--    PUBLISHED）。INVALIDATED 原因是展示用欄位；存活列即為 application 原本會重用的
--    最新可用草稿，因此不改變任何既有可見行為。
UPDATE wiki_draft
SET status = 'INVALIDATED',
    invalidated_reason = 'SUPERSEDED_BY_REGENERATION',
    updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
WHERE status IN ('DRAFT', 'READY')
  AND id NOT IN (
    SELECT MAX(id) FROM wiki_draft
    WHERE status IN ('DRAFT', 'READY')
    GROUP BY workspace_id, proposal_id
  );

-- 2. Storage-level enforcement going forward：partial unique index 只覆蓋可用狀態；
--    PUBLISHED/INVALIDATED 歷史列不受限（publish/regenerate/invalidate lifecycle 不變）。
CREATE UNIQUE INDEX IF NOT EXISTS idx_wiki_draft_single_usable_draft
    ON wiki_draft(workspace_id, proposal_id)
    WHERE status IN ('DRAFT', 'READY');
