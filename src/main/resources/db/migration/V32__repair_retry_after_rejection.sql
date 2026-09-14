-- #384 stabilization: the repair dedup index must not be occupied by REJECTED
-- proposals. With the V31 predicate (source_kind only), a human-rejected repair
-- permanently blocked retries: the re-insert violated the unique index while the
-- winner lookup filtered REJECTED out, surfacing as an unexplainable 500. The index
-- is recreated with the retry-aware predicate so a rejected repair can be proposed
-- again; non-rejected rows keep the idempotency contract. The ASK index keeps its
-- #374 contract unchanged.

DROP INDEX idx_knowledge_proposal_repair_dedup;

CREATE UNIQUE INDEX idx_knowledge_proposal_repair_dedup
    ON knowledge_proposal(workspace_id, source_dedup_hash)
    WHERE source_kind = 'REPAIR' AND status != 'REJECTED';
