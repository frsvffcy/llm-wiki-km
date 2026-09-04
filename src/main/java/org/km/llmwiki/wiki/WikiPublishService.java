package org.km.llmwiki.wiki;

import org.km.llmwiki.ai.LlmProposalAction;
import org.km.llmwiki.search.PublishedWikiIndexingService;
import org.km.llmwiki.search.embedding.EmbeddingProjectionJobService;
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Dispatches the explicit publish endpoint without broadening either action-specific service. */
@Service
public class WikiPublishService {

    private static final Logger log = LoggerFactory.getLogger(WikiPublishService.class);

    private final WorkspaceService workspaceService;
    private final WikiDraftRepository draftRepository;
    private final WikiCreatePublishService createPublishService;
    private final WikiMergePublishService mergePublishService;
    private final WikiPublicationRepository publicationRepository;
    private final WikiPublishAttemptRepository attemptRepository;
    private final PublishedWikiIndexingService publishedWikiIndexingService;
    private final EmbeddingProjectionJobService embeddingProjectionJobService;
    private final ConcurrentHashMap<String, ReentrantLock> draftLocks = new ConcurrentHashMap<>();

    public WikiPublishService(WorkspaceService workspaceService, WikiDraftRepository draftRepository,
                              WikiCreatePublishService createPublishService,
                              WikiMergePublishService mergePublishService,
                              WikiPublicationRepository publicationRepository,
                              WikiPublishAttemptRepository attemptRepository,
                              PublishedWikiIndexingService publishedWikiIndexingService,
                              EmbeddingProjectionJobService embeddingProjectionJobService) {
        this.workspaceService = workspaceService;
        this.draftRepository = draftRepository;
        this.createPublishService = createPublishService;
        this.mergePublishService = mergePublishService;
        this.publicationRepository = publicationRepository;
        this.attemptRepository = attemptRepository;
        this.publishedWikiIndexingService = publishedWikiIndexingService;
        this.embeddingProjectionJobService = embeddingProjectionJobService;
    }

    public WikiPublishResult publish(long draftId) {
        long workspaceId = workspaceService.findActiveWithoutValidation()
                .orElseThrow(NoActiveWorkspaceException::new).id();
        StoredWikiDraft draft = draftRepository.findById(workspaceId, draftId)
                .orElseThrow(() -> new WikiDraftNotFoundException(draftId));
        StoredWikiPublishAttempt attempt = attemptRepository.start(draft);
        String lockKey = workspaceId + ":" + draftId;
        ReentrantLock lock = draftLocks.computeIfAbsent(lockKey, ignored -> new ReentrantLock());
        lock.lock();
        try {
            WikiPublishResult result = draft.action() == LlmProposalAction.CREATE
                    ? createPublishService.publish(draftId)
                    : mergePublishService.publish(draftId);
            // FTS is a rebuildable projection. A failed sync is recorded as repairable state and
            // is deliberately never allowed to roll back a completed vault publish.
            publishedWikiIndexingService.synchronizeAfterPublish(result);
            long publishedPageId = result instanceof WikiCreatePublishResponse create
                    ? create.knowledgePageId() : ((WikiMergePublishResponse) result).knowledgePageId();
            try {
                embeddingProjectionJobService.enqueueWiki(workspaceId, publishedPageId);
            } catch (RuntimeException schedulingFailure) {
                log.warn("Embedding projection scheduling failed after canonical Wiki publish; "
                                + "repair state should be STALE (workspaceId={}, pageId={}, failureType={})",
                        workspaceId, publishedPageId, schedulingFailure.getClass().getSimpleName());
            }
            StoredWikiPublishOperation operation = publicationRepository.findByDraft(workspaceId, draftId)
                    .orElseThrow(() -> new WikiPublishException(WikiPublishException.Reason.RECONCILIATION_REQUIRED,
                            "Successful publish did not retain its operation identity"));
            attemptRepository.complete(attempt, result.result(), operation);
            return result.withAttemptId(attempt.id());
        } catch (RuntimeException exception) {
            StoredWikiPublishOperation operation = publicationRepository.findByDraft(workspaceId, draftId)
                    .orElse(null);
            try {
                attemptRepository.fail(attempt, WikiPublishFailure.from(exception), operation);
            } catch (RuntimeException auditFailure) {
                exception.addSuppressed(auditFailure);
            }
            throw exception;
        } finally {
            lock.unlock();
            if (!lock.hasQueuedThreads()) {
                draftLocks.remove(lockKey, lock);
            }
        }
    }
}
