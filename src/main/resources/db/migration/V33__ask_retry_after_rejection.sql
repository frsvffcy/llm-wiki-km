-- #391：Ask proposal 被人類拒絕後，不應永久占用相同的 dedup hash。
-- 與 repair ingress 的 retry 語意一致；非 REJECTED proposal 仍維持 idempotency。

DROP INDEX idx_knowledge_proposal_ask_dedup;

CREATE UNIQUE INDEX idx_knowledge_proposal_ask_dedup
    ON knowledge_proposal(workspace_id, source_dedup_hash)
    WHERE source_kind = 'ASK' AND status != 'REJECTED';
