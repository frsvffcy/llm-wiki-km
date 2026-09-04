package org.km.llmwiki.wiki;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The DB half of CREATE publish is one transaction and runs only after the final file is visible. */
@Service
public class WikiCreatePublicationFinalizer {

    private final WikiPublicationRepository publicationRepository;
    private final WikiDraftRepository draftRepository;

    public WikiCreatePublicationFinalizer(WikiPublicationRepository publicationRepository,
                                          WikiDraftRepository draftRepository) {
        this.publicationRepository = publicationRepository;
        this.draftRepository = draftRepository;
    }

    @Transactional
    public StoredWikiPublishOperation complete(StoredWikiDraft draft, StoredWikiPublishOperation operation,
                                               String publishedAt) {
        long knowledgePageId = publicationRepository.insertKnowledgePage(draft, operation, publishedAt);
        draftRepository.markPublished(draft.workspaceId(), draft.id(), operation.targetPath(),
                operation.contentHash(), operation.revision(), publishedAt);
        publicationRepository.complete(draft.workspaceId(), operation.id(), knowledgePageId, publishedAt);
        return publicationRepository.findByDraft(draft.workspaceId(), draft.id())
                .orElseThrow(() -> new IllegalStateException("Completed CREATE publish operation was not found"));
    }
}
