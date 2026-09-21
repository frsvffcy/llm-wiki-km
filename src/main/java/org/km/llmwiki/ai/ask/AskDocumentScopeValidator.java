package org.km.llmwiki.ai.ask;

import org.km.llmwiki.rag.DocumentRetrievalScope;
import org.km.llmwiki.source.DocumentUsabilityReadiness;
import org.km.llmwiki.source.InboxListService;
import org.springframework.stereotype.Service;

/** Revalidates the active-workspace document authority for every scoped Ask consumption window. */
@Service
public class AskDocumentScopeValidator {

    private final InboxListService inboxListService;

    public AskDocumentScopeValidator(InboxListService inboxListService) {
        this.inboxListService = inboxListService;
    }

    public void requireCurrent(DocumentRetrievalScope scope) {
        if (scope == null) {
            return;
        }
        var document = inboxListService.getInboxDocument(scope.documentId())
                .orElseThrow(() -> new AskDocumentScopeException(
                        AskDocumentScopeException.Reason.INVALID));
        var usability = document.usability();
        if (usability != null
                && usability.status() == DocumentUsabilityReadiness.Status.INDEX_STALE) {
            throw new AskDocumentScopeException(AskDocumentScopeException.Reason.STALE);
        }
        if (usability == null
                || usability.status() != DocumentUsabilityReadiness.Status.READY_TO_USE
                || !usability.searchReady()) {
            throw new AskDocumentScopeException(AskDocumentScopeException.Reason.INVALID);
        }
    }
}
