package org.km.llmwiki.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.km.llmwiki.processing.ProcessingJob;
import org.km.llmwiki.processing.ProcessingJobRepository;
import org.km.llmwiki.processing.ProcessingJobStatus;
import org.km.llmwiki.processing.ProcessingJobType;
import org.km.llmwiki.processing.ProcessingLogRepository;
import org.km.llmwiki.wiki.PageStatus;
import org.km.llmwiki.wiki.PublishedWikiContentReader;
import org.km.llmwiki.wiki.PublishedWikiRepository;
import org.km.llmwiki.wiki.StoredPublishedWiki;
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Asynchronous, atomic rebuild of workspace-scoped FTS projections from canonical authority. */
@Service
public class FtsRebuildService {

    private final WorkspaceService workspaceService;
    private final PublishedWikiRepository publishedWikiRepository;
    private final PublishedWikiContentReader publishedWikiContentReader;
    private final SourceSearchAuthorityRepository sourceAuthorityRepository;
    private final FtsSearchIndexRepository ftsRepository;
    private final WikiSearchIndexSyncRepository wikiSyncRepository;
    private final SourceSearchIndexSyncRepository sourceSyncRepository;
    private final FtsRebuildStateRepository rebuildStateRepository;
    private final ProcessingJobRepository jobRepository;
    private final ProcessingLogRepository logRepository;
    private final TaskExecutor taskExecutor;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public FtsRebuildService(WorkspaceService workspaceService,
                             PublishedWikiRepository publishedWikiRepository,
                             PublishedWikiContentReader publishedWikiContentReader,
                             SourceSearchAuthorityRepository sourceAuthorityRepository,
                             FtsSearchIndexRepository ftsRepository,
                             WikiSearchIndexSyncRepository wikiSyncRepository,
                             SourceSearchIndexSyncRepository sourceSyncRepository,
                             FtsRebuildStateRepository rebuildStateRepository,
                             ProcessingJobRepository jobRepository,
                             ProcessingLogRepository logRepository,
                             @Qualifier("ftsRebuildTaskExecutor") TaskExecutor taskExecutor,
                             TransactionTemplate transactionTemplate,
                             ObjectMapper objectMapper) {
        this.workspaceService = workspaceService;
        this.publishedWikiRepository = publishedWikiRepository;
        this.publishedWikiContentReader = publishedWikiContentReader;
        this.sourceAuthorityRepository = sourceAuthorityRepository;
        this.ftsRepository = ftsRepository;
        this.wikiSyncRepository = wikiSyncRepository;
        this.sourceSyncRepository = sourceSyncRepository;
        this.rebuildStateRepository = rebuildStateRepository;
        this.jobRepository = jobRepository;
        this.logRepository = logRepository;
        this.taskExecutor = taskExecutor;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    public FtsRebuildCreatedResponse start(FtsRebuildRequest request) {
        SearchCorpus corpus = SearchCorpus.from(request == null ? null : request.corpus());
        WorkspaceResponse workspace = workspaceService.findActiveWithoutValidation()
                .orElseThrow(NoActiveWorkspaceException::new);
        List<SearchCorpus> physicalCorpora = physicalCorpora(corpus);
        Launch launch = transactionTemplate.execute(status -> {
            if (rebuildStateRepository.hasInProgress(workspace.id(), physicalCorpora)) {
                throw new IllegalStateException("An FTS rebuild is already in progress for this workspace and corpus");
            }
            ProcessingJob job = jobRepository.create(workspace.id(), UUID.randomUUID().toString(),
                    ProcessingJobType.FTS_REBUILD, physicalCorpora.size());
            rebuildStateRepository.markQueued(workspace.id(), job.id(), physicalCorpora);
            return new Launch(workspace.id(), corpus, physicalCorpora, job);
        });
        if (launch == null) {
            throw new IllegalStateException("Could not create FTS rebuild job");
        }
        scheduleAfterCommit(launch);
        return new FtsRebuildCreatedResponse(launch.job().jobId(), ProcessingJobStatus.QUEUED.name(),
                corpus, workspace.id());
    }

    private void scheduleAfterCommit(Launch launch) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            taskExecutor.execute(() -> run(launch));
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                taskExecutor.execute(() -> run(launch));
            }
        });
    }

    private void run(Launch launch) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                requireStillActive(launch.workspaceId());
                jobRepository.markRunning(launch.job().id());
                rebuildStateRepository.markRunning(launch.workspaceId(), launch.job().id(),
                        launch.physicalCorpora());
                logRepository.append(launch.job().id(), null, null, "FTS_REBUILD", "RUNNING",
                        "FTS rebuild started", metadata(Map.of("corpus", launch.corpus().name(),
                                "workspaceId", launch.workspaceId())));
            });

            PreparedAuthority authority = prepareAuthority(launch);
            transactionTemplate.executeWithoutResult(status -> replaceProjection(launch, authority));
        } catch (RuntimeException failure) {
            recordFailure(launch, failure);
        }
    }

    private PreparedAuthority prepareAuthority(Launch launch) {
        requireStillActive(launch.workspaceId());
        List<PreparedWiki> wiki = new ArrayList<>();
        if (launch.physicalCorpora().contains(SearchCorpus.WIKI)) {
            for (StoredPublishedWiki page : publishedWikiRepository.findAllPublished(launch.workspaceId())) {
                String content = publishedWikiContentReader.readSearchableContent(page);
                wiki.add(new PreparedWiki(page, new KnowledgeSearchDocument(page.workspaceId(),
                        page.knowledgeId(), page.title(), page.normalizedTitle(), content,
                        page.markdownPath(), page.pageType().name(), PageStatus.PUBLISHED.name(),
                        page.contentHash())));
            }
        }

        List<PreparedSourceDocument> source = new ArrayList<>();
        if (launch.physicalCorpora().contains(SearchCorpus.SOURCE)) {
            for (SourceSearchAuthorityDocument document
                    : sourceAuthorityRepository.findAllDocuments(launch.workspaceId())) {
                List<SourceSearchDocument> projections =
                        SourceChunkIndexingService.eligibleDocuments(document);
                source.add(new PreparedSourceDocument(document, projections,
                        SourceChunkIndexingService.fingerprint(projections)));
            }
        }
        return new PreparedAuthority(List.copyOf(wiki), List.copyOf(source));
    }

    private void replaceProjection(Launch launch, PreparedAuthority authority) {
        requireStillActive(launch.workspaceId());
        int completedCorpora = 0;
        if (launch.physicalCorpora().contains(SearchCorpus.WIKI)) {
            List<KnowledgeSearchDocument> documents = authority.wiki().stream()
                    .map(PreparedWiki::projection).toList();
            ftsRepository.rebuildKnowledge(launch.workspaceId(), documents);
            wikiSyncRepository.clearWorkspace(launch.workspaceId());
            authority.wiki().forEach(item -> wikiSyncRepository.markSynced(item.page()));
            rebuildStateRepository.markCompleted(launch.workspaceId(), launch.job().id(),
                    SearchCorpus.WIKI, documents.size());
            completedCorpora++;
        }
        if (launch.physicalCorpora().contains(SearchCorpus.SOURCE)) {
            List<SourceSearchDocument> documents = authority.source().stream()
                    .flatMap(item -> item.projections().stream()).toList();
            ftsRepository.rebuildSource(launch.workspaceId(), documents);
            sourceSyncRepository.clearWorkspace(launch.workspaceId());
            for (PreparedSourceDocument item : authority.source()) {
                SourceSearchIndexSyncStatus status = item.projections().isEmpty()
                        ? SourceSearchIndexSyncStatus.INELIGIBLE : SourceSearchIndexSyncStatus.SYNCED;
                sourceSyncRepository.markComplete(launch.workspaceId(), item.authority().documentId(),
                        status, item.projections().size(), item.fingerprint());
            }
            rebuildStateRepository.markCompleted(launch.workspaceId(), launch.job().id(),
                    SearchCorpus.SOURCE, documents.size());
            completedCorpora++;
        }
        jobRepository.markCompleted(launch.job().id(), completedCorpora, completedCorpora, 0, 0);
        logRepository.append(launch.job().id(), null, null, "FTS_REBUILD", "SUCCEEDED",
                "FTS rebuild completed", metadata(Map.of("corpus", launch.corpus().name(),
                        "workspaceId", launch.workspaceId(), "indexedWiki", authority.wiki().size(),
                        "indexedSourceChunks", authority.source().stream()
                                .mapToInt(item -> item.projections().size()).sum())));
    }

    private void recordFailure(Launch launch, RuntimeException failure) {
        String detail = failure.getClass().getSimpleName() + ": " + safeMessage(failure);
        transactionTemplate.executeWithoutResult(status -> {
            rebuildStateRepository.markFailed(launch.workspaceId(), launch.job().id(),
                    launch.physicalCorpora(), detail);
            jobRepository.markFailed(launch.job().id(), 1, 0, 1, detail);
            logRepository.append(launch.job().id(), null, null, "FTS_REBUILD", "FAILED",
                    "FTS rebuild failed", metadata(Map.of("corpus", launch.corpus().name(),
                            "workspaceId", launch.workspaceId(), "failure", detail)));
        });
    }

    private void requireStillActive(long workspaceId) {
        long activeId = workspaceService.findActiveWithoutValidation()
                .orElseThrow(NoActiveWorkspaceException::new).id();
        if (activeId != workspaceId) {
            throw new IllegalStateException("Active workspace changed before FTS rebuild completed");
        }
    }

    static List<SearchCorpus> physicalCorpora(SearchCorpus corpus) {
        return switch (corpus) {
            case WIKI -> List.of(SearchCorpus.WIKI);
            case SOURCE -> List.of(SearchCorpus.SOURCE);
            case ALL -> List.of(SearchCorpus.WIKI, SearchCorpus.SOURCE);
        };
    }

    private String metadata(Map<String, Object> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize FTS rebuild diagnostics", exception);
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "unspecified failure" : message;
    }

    private record Launch(long workspaceId, SearchCorpus corpus, List<SearchCorpus> physicalCorpora,
                          ProcessingJob job) {
        private Launch {
            physicalCorpora = List.copyOf(physicalCorpora);
        }
    }

    private record PreparedWiki(StoredPublishedWiki page, KnowledgeSearchDocument projection) {
    }

    private record PreparedSourceDocument(SourceSearchAuthorityDocument authority,
                                          List<SourceSearchDocument> projections,
                                          String fingerprint) {
        private PreparedSourceDocument {
            projections = List.copyOf(projections);
        }
    }

    private record PreparedAuthority(List<PreparedWiki> wiki, List<PreparedSourceDocument> source) {
    }
}
