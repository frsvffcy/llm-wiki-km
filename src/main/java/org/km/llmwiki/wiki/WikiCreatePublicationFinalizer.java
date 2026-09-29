package org.km.llmwiki.wiki;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The DB half of CREATE publish is one transaction and runs only after the final file is visible. */
@Service
public class WikiCreatePublicationFinalizer {

    private final WikiPublicationRepository publicationRepository;
    private final WikiDraftRepository draftRepository;
    private final WikiPublishCommitPointGuard commitPointGuard;

    public WikiCreatePublicationFinalizer(WikiPublicationRepository publicationRepository,
                                          WikiDraftRepository draftRepository,
                                          WikiPublishCommitPointGuard commitPointGuard) {
        this.publicationRepository = publicationRepository;
        this.draftRepository = draftRepository;
        this.commitPointGuard = commitPointGuard;
    }

    @Transactional
    public StoredWikiPublishOperation complete(StoredWikiDraft draft, StoredWikiPublishOperation operation,
                                               String publishedAt) {
        // #652 commit point：先取得 SQLite writer serialization，再重驗 persisted ASK
        // evidence；必須是交易內第一個 DB statement，之後才做 canonical metadata commit。
        // Stale 映射為 PROPOSAL_INVALID（WIKI_PUBLISH_PROPOSAL_INVALID），不退化為 METADATA_FAILURE。
        commitPointGuard.verifyCommitPoint(draft.workspaceId(), draft.id(), draft.proposalId(),
                operation.id(), org.km.llmwiki.ai.LlmProposalAction.CREATE);
        long knowledgePageId = publicationRepository.insertKnowledgePage(draft, operation, publishedAt);
        draftRepository.markPublished(draft.workspaceId(), draft.id(), operation.targetPath(),
                operation.contentHash(), operation.revision(), publishedAt);
        publicationRepository.complete(draft.workspaceId(), operation.id(), knowledgePageId, publishedAt);
        return publicationRepository.findByDraft(draft.workspaceId(), draft.id())
                .orElseThrow(() -> new IllegalStateException("Completed CREATE publish operation was not found"));
    }
}
