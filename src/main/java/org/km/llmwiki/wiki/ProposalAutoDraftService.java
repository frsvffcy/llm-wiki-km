package org.km.llmwiki.wiki;

import org.springframework.stereotype.Service;

/**
 * #570：核准後自動準備草稿的 get-or-create 邊界（非交易協調器）。
 *
 * <p>呼叫前核准轉換已提交：先讀既有可用草稿（沿用，不重複建立），否則經
 * {@link ProposalAutoDraftCreator} 在獨立交易嘗試建立。失敗一律為 typed
 * {@link ProposalAutoDraft#failed}（proposal 維持 APPROVED，不 fake-publish、
 * 不回滾核准）。重複呼叫安全（冪等）：第二次起必命中沿用分支。
 */
@Service
public class ProposalAutoDraftService {

    private final WikiDraftRepository draftRepository;
    private final ProposalAutoDraftCreator draftCreator;

    public ProposalAutoDraftService(WikiDraftRepository draftRepository,
                                    ProposalAutoDraftCreator draftCreator) {
        this.draftRepository = draftRepository;
        this.draftCreator = draftCreator;
    }

    public ProposalAutoDraft prepare(long workspaceId, long proposalId) {
        var existing = draftRepository.findLatestUsableByProposalId(workspaceId, proposalId);
        if (existing.isPresent()) {
            return ProposalAutoDraft.reused(existing.get().id(), existing.get().status());
        }
        return draftCreator.create(proposalId);
    }
}
