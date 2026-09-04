package org.km.llmwiki.wiki;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The DB half of MERGE publish is one transaction and runs only after atomic filesystem replacement. */
@Service
public class WikiMergePublicationFinalizer {

    private final WikiPublicationRepository publicationRepository;
    private final WikiDraftRepository draftRepository;

    public WikiMergePublicationFinalizer(WikiPublicationRepository publicationRepository,
                                         WikiDraftRepository draftRepository) {
        this.publicationRepository = publicationRepository;
        this.draftRepository = draftRepository;
    }

    @Transactional
    public StoredWikiPublishOperation complete(StoredWikiDraft draft, StoredWikiPublishOperation operation,
                                               long knowledgePageId, String publishedAt) {
        publicationRepository.updateKnowledgePageForMerge(draft, operation, knowledgePageId, publishedAt);
        draftRepository.markPublished(draft.workspaceId(), draft.id(), operation.targetPath(),
                operation.contentHash(), operation.revision(), publishedAt);
        publicationRepository.complete(draft.workspaceId(), operation.id(), knowledgePageId, publishedAt);
        return publicationRepository.findByDraft(draft.workspaceId(), draft.id())
                .orElseThrow(() -> new IllegalStateException("Completed MERGE publish operation was not found"));
    }
}
