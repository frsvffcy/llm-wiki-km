package org.km.llmwiki.wiki;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The DB half of MERGE publish is one transaction and runs only after atomic filesystem replacement. */
@Service
public class WikiMergePublicationFinalizer {

    private final WikiPublicationRepository publicationRepository;
    private final WikiDraftRepository draftRepository;
    private final WikiPublishCommitPointGuard commitPointGuard;

    public WikiMergePublicationFinalizer(WikiPublicationRepository publicationRepository,
                                         WikiDraftRepository draftRepository,
                                         WikiPublishCommitPointGuard commitPointGuard) {
        this.publicationRepository = publicationRepository;
        this.draftRepository = draftRepository;
        this.commitPointGuard = commitPointGuard;
    }

    @Transactional
    public StoredWikiPublishOperation complete(StoredWikiDraft draft, StoredWikiPublishOperation operation,
                                               long knowledgePageId, String publishedAt) {
        // #652 commit point：與 CREATE 同一 contract，先 writer serialization 再重驗
        // persisted ASK evidence；stale 映射為 PROPOSAL_INVALID，不得退化為 METADATA_FAILURE。
        commitPointGuard.verifyCommitPoint(draft.workspaceId(), draft.id(), draft.proposalId(),
                operation.id(), org.km.llmwiki.ai.LlmProposalAction.MERGE);
        publicationRepository.updateKnowledgePageForMerge(draft, operation, knowledgePageId, publishedAt);
        draftRepository.markPublished(draft.workspaceId(), draft.id(), operation.targetPath(),
                operation.contentHash(), operation.revision(), publishedAt);
        publicationRepository.complete(draft.workspaceId(), operation.id(), knowledgePageId, publishedAt);
        return publicationRepository.findByDraft(draft.workspaceId(), draft.id())
                .orElseThrow(() -> new IllegalStateException("Completed MERGE publish operation was not found"));
    }
}
