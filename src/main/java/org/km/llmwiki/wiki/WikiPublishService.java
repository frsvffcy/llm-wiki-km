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
    /**
     * #652：app-owned Wiki filesystem publish 的 bounded canonical serialization。
     * Scope 為單一 active workspace（key 只有 workspaceId），至少涵蓋 file commit →
     * DB finalizer，讓同 workspace 另一個 publish 不能在 evidence validation 與
     * canonical commit 中間換檔（改寫被引用的 Wiki file）。不同 workspace 互不阻塞；
     * 非 publish 的 canonical mutation（SOURCE status/parse/chunk）仍由 finalizer
     * 交易內的 SQLite writer serialization＋commit-point revalidation 封住。
     * 不是 global lock；也不是 per-draft（per-draft 無法阻止同 workspace 另一 draft
     * 改寫同一 canonical file）。
     *
     * <p>Lifecycle（#653 corrective）：entries 保留 process lifetime，絕不移除。
     * {@code unlock() → hasQueuedThreads() → remove()} 是不安全的：T1 unlock 後 T2
     * 可能已接手舊 lock 並離開 queue，此時 T1 觀察到 queue 為空而移除舊 lock，隨後
     * 的 T3 會建立新 lock 並與仍持有舊 lock 的 T2 同時進入 critical section。
     * 保留是最小且 correctness 明確的方案：keyspace 為曾見過的 workspace id（本系統
     * 為 single-instance 單使用者本機部署，workspace 基數小；id 為 AUTOINCREMENT
     * 永不重用，保留舊 id 的 lock 無正確性影響），每個 entry 僅一個 ReentrantLock，
     * footprint 可忽略。
     */
    private final ConcurrentHashMap<String, ReentrantLock> workspacePublishLocks = new ConcurrentHashMap<>();

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
        String lockKey = String.valueOf(workspaceId);
        ReentrantLock lock = workspacePublishLocks.computeIfAbsent(lockKey, ignored -> new ReentrantLock());
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
            // 只 unlock，不從 map 移除（lifecycle 見欄位 javadoc）：移除需要證明
            // 沒有 holder 也沒有 waiter，而 unlock 之後已無法原子證明這件事。
            lock.unlock();
        }
    }
}
