package org.km.llmwiki.wiki;

import org.jooq.exception.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * #601：single-usable-draft 衝突復原的唯一規則擁有者。
 *
 * <p>當建立路徑撞上 V35 partial unique index（併發競爭者已先提交可用草稿），
 * 復原規則只有一條：回讀同一 (workspace, proposal) 最新可用草稿並沿用；
 * 非此約束違反、或回讀仍無可用草稿時，原樣拋出（fail-closed，不猜測）。
 * 所有建立路徑（auto／manual／regenerate）共用此 policy。
 */
@Service
public class WikiDraftReusePolicy {

    private final WikiDraftRepository draftRepository;

    public WikiDraftReusePolicy(WikiDraftRepository draftRepository) {
        this.draftRepository = draftRepository;
    }

    public StoredWikiDraft reuseOrThrow(long workspaceId, long proposalId, DataAccessException failure) {
        if (!WikiDraftRepository.isSingleUsableDraftViolation(failure)) {
            throw failure;
        }
        return draftRepository.findLatestUsableByProposalId(workspaceId, proposalId)
                .orElseThrow(() -> failure);
    }
}
