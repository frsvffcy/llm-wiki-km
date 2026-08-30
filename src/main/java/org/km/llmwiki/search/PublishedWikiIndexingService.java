package org.km.llmwiki.search;

import org.km.llmwiki.wiki.PublishedWikiRepository;
import org.km.llmwiki.wiki.PublishedWikiContentReader;
import org.km.llmwiki.wiki.PageStatus;
import org.km.llmwiki.wiki.StoredPublishedWiki;
import org.km.llmwiki.wiki.WikiPublishResult;
import org.km.llmwiki.wiki.WikiCreatePublishResponse;
import org.km.llmwiki.wiki.WikiMergePublishResponse;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Projects an already successful Published Wiki into the rebuildable FTS index.
 *
 * <p>The service reads the canonical bytes from vault and verifies the durable
 * {@code knowledge_page.content_hash} before writing FTS. It never renders or asks an
 * LLM for content, and all failures are recorded as repairable ledger state instead of
 * escaping into the completed publish transaction.
 */
@Service
public class PublishedWikiIndexingService {

    private final PublishedWikiRepository publishedWikiRepository;
    private final PublishedWikiContentReader contentReader;
    private final FtsSearchIndexRepository ftsRepository;
    private final WikiSearchIndexSyncRepository syncRepository;

    public PublishedWikiIndexingService(PublishedWikiRepository publishedWikiRepository,
                                        PublishedWikiContentReader contentReader,
                                        FtsSearchIndexRepository ftsRepository,
                                        WikiSearchIndexSyncRepository syncRepository) {
        this.publishedWikiRepository = publishedWikiRepository;
        this.contentReader = contentReader;
        this.ftsRepository = ftsRepository;
        this.syncRepository = syncRepository;
    }

    /** Syncs one Published Wiki by the workspace-scoped knowledge_page id. */
    public WikiIndexSyncResult reindex(long workspaceId, long knowledgePageId) {
        Optional<StoredPublishedWiki> page = publishedWikiRepository
                .findPublishedById(workspaceId, knowledgePageId);
        if (page.isEmpty()) {
            return new WikiIndexSyncResult(WikiIndexSyncStatus.NOT_FOUND, workspaceId, knowledgePageId,
                    "Published Wiki page was not found in this workspace");
        }

        StoredPublishedWiki published = page.get();
        try {
            String searchableContent = contentReader.readSearchableContent(published);
            KnowledgeSearchDocument document = new KnowledgeSearchDocument(
                    published.workspaceId(), published.knowledgeId(), published.title(),
                    published.normalizedTitle(), searchableContent, published.markdownPath(),
                    published.pageType().name(), PageStatus.PUBLISHED.name(), published.contentHash());
            ftsRepository.upsertKnowledge(document);
            StoredWikiSearchIndexSync ledger = syncRepository.markSynced(published);
            return new WikiIndexSyncResult(WikiIndexSyncStatus.SYNCED, ledger.workspaceId(),
                    ledger.knowledgePageId(), null);
        } catch (RuntimeException exception) {
            WikiSearchIndexSyncStatus status = exception.getMessage() != null
                    && exception.getMessage().contains("content_hash")
                    ? WikiSearchIndexSyncStatus.DRIFT : WikiSearchIndexSyncStatus.INDEX_PENDING;
            return pending(published, status,
                    "Published Wiki FTS sync failed: " + exception.getClass().getSimpleName()
                            + ": " + safeMessage(exception));
        }
    }

    /** Alias used by publish/recovery callers; the operation is intentionally idempotent. */
    public WikiIndexSyncResult synchronize(long workspaceId, long knowledgePageId) {
        return reindex(workspaceId, knowledgePageId);
    }

    /**
     * Called only after CREATE/MERGE publish has completed its vault and relational finalization.
     * NO_OP is also checked so a retry can repair an earlier index-pending row.
     */
    public WikiIndexSyncResult synchronizeAfterPublish(WikiPublishResult result) {
        if (result instanceof WikiCreatePublishResponse create) {
            return reindex(create.workspaceId(), create.knowledgePageId());
        }
        if (result instanceof WikiMergePublishResponse merge) {
            return reindex(merge.workspaceId(), merge.knowledgePageId());
        }
        throw new IllegalArgumentException("Unsupported Wiki publish result");
    }

    private WikiIndexSyncResult pending(StoredPublishedWiki page, WikiSearchIndexSyncStatus status,
                                        String detail) {
        try {
            StoredWikiSearchIndexSync ledger = syncRepository.markPending(page, status, detail);
            return new WikiIndexSyncResult(WikiIndexSyncStatus.valueOf(status.name()),
                    ledger.workspaceId(), ledger.knowledgePageId(), detail);
        } catch (RuntimeException ledgerFailure) {
            // The caller must never turn a rebuildable-index failure into a publish rollback.
            return new WikiIndexSyncResult(WikiIndexSyncStatus.valueOf(status.name()),
                    page.workspaceId(), page.id(),
                    detail + "; repair ledger write failed: " + safeMessage(ledgerFailure));
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "unspecified failure" : message;
    }
}
