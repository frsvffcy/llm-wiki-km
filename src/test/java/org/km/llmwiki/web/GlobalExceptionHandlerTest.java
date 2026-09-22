package org.km.llmwiki.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.ai.ask.AskApiException;
import org.km.llmwiki.ai.ask.AskFailureType;
import org.km.llmwiki.graph.GraphProjectionException;
import org.km.llmwiki.graph.GraphProjectionFailure;
import org.km.llmwiki.graph.GraphProjectionFailureType;
import org.km.llmwiki.rag.RetrievalUnavailableException;
import org.km.llmwiki.source.DocumentAlreadyProcessedException;
import org.km.llmwiki.source.DocumentExtractionException;
import org.km.llmwiki.source.DocumentNotFoundException;
import org.km.llmwiki.source.SourceChunkNotFoundException;
import org.km.llmwiki.search.embedding.ProcessingJobNotFoundException;
import org.km.llmwiki.processing.ProcessingOperationNotFoundException;
import org.km.llmwiki.wiki.AskCitationInvalidException;
import org.km.llmwiki.wiki.KnowledgeProposalNotFoundException;
import org.km.llmwiki.wiki.PublishedWikiUnavailableException;
import org.km.llmwiki.wiki.PublishedWikiValidationException;
import org.km.llmwiki.wiki.RepairFindingStaleException;
import org.km.llmwiki.wiki.RepairNotEligibleException;
import org.km.llmwiki.wiki.RepairRefusalReason;
import org.km.llmwiki.wiki.WikiDraftLifecycleException;
import org.km.llmwiki.wiki.WikiDraftNotFoundException;
import org.km.llmwiki.wiki.WikiDraftTargetException;
import org.km.llmwiki.wiki.WikiPageNotFoundException;
import org.km.llmwiki.wiki.WikiPublishException;
import org.km.llmwiki.workspace.DuplicateWorkspaceException;
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceNotFoundException;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for the public REST error boundary (#504): stable codes and HTTP statuses are
 * decided by the typed exception, every application-owned message is a fixed Traditional
 * Chinese projection, and no raw exception prose — safe English or hostile — ever reaches the
 * response body. These tests actively falsify both the redaction boundary (hostile messages
 * carrying paths/secrets/SQL/backend identity) and the language-governance boundary (safe
 * English that would previously survive redaction and leak as user-facing prose).
 */
@Tag("contract")
class GlobalExceptionHandlerTest {

    private static final String HOSTILE =
            "root /Users/example/workspace/secret token=abcdef123456 "
                    + "Authorization: Bearer abc.def SELECT * FROM knowledge_page RID #12:0 "
                    + "{\"error\":{\"message\":\"upstream\"}}";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void loggingPolicyMakesServerFailuresVisibleWithoutNoisyClientErrors() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/inbox");
        request.setQueryString("secret=must-not-be-logged");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        try {
            logger.setLevel(Level.INFO);

            RuntimeException unexpected = new RuntimeException("boom");
            handler.handleUnexpected(unexpected);

            assertThat(appender.list).hasSize(1);
            ILoggingEvent serverEvent = appender.list.getFirst();
            assertThat(serverEvent.getLevel()).isEqualTo(Level.ERROR);
            assertThat(serverEvent.getFormattedMessage())
                    .contains("GET /api/v1/inbox", "500", "INTERNAL_ERROR")
                    .doesNotContain("secret=must-not-be-logged");
            assertThat(serverEvent.getThrowableProxy()).isNotNull();
            assertThat(serverEvent.getThrowableProxy().getClassName())
                    .isEqualTo(RuntimeException.class.getName());

            handler.handleRetrievalUnavailable(
                    new RetrievalUnavailableException(
                            RetrievalUnavailableException.Dependency.GRAPH,
                            new IllegalStateException("backend unavailable")));
            assertThat(appender.list).hasSize(2);
            ILoggingEvent unavailableEvent = appender.list.get(1);
            assertThat(unavailableEvent.getLevel()).isEqualTo(Level.WARN);
            assertThat(unavailableEvent.getFormattedMessage())
                    .contains("GET /api/v1/inbox", "503", "RETRIEVAL_UNAVAILABLE");
            assertThat(unavailableEvent.getThrowableProxy()).isNotNull();

            handler.handleIllegalArgument(new IllegalArgumentException("bad input"));
            assertThat(appender.list)
                    .as("4xx stays DEBUG and therefore remains quiet at the default INFO runtime")
                    .hasSize(2);

            logger.setLevel(Level.DEBUG);
            handler.handleIllegalArgument(new IllegalArgumentException("bad input"));
            assertThat(appender.list).hasSize(3);
            ILoggingEvent clientEvent = appender.list.get(2);
            assertThat(clientEvent.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(clientEvent.getFormattedMessage())
                    .contains("GET /api/v1/inbox", "400", "INVALID_REQUEST");
        } finally {
            RequestContextHolder.resetRequestAttributes();
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
        }
    }

    @Test
    void duplicateWorkspaceNeverExposesTheRootPath() {
        DuplicateWorkspaceException exception = new DuplicateWorkspaceException(
                "/Users/example/workspace/secret-project", 7L);
        ResponseEntity<ApiError> response = handler.handleDuplicateWorkspace(exception);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(response)).isEqualTo("WORKSPACE_ALREADY_EXISTS");
        assertThat(message(response))
                .isEqualTo("此根目錄路徑已有工作區")
                .doesNotContain("/Users", "toddyeh", "secret");
    }

    @Test
    void illegalArgumentUsesFixedChineseMessageForSafeAndHostileInput() {
        ResponseEntity<ApiError> hostile = handler.handleIllegalArgument(
                new IllegalArgumentException(HOSTILE));
        assertThat(hostile.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(hostile)).isEqualTo("INVALID_REQUEST");
        assertThat(message(hostile)).isEqualTo("要求驗證失敗");

        // #504 regression: safe English validation wording must not passthrough as
        // user-facing prose even though DiagnosticRedaction would deem it safe.
        ResponseEntity<ApiError> safe = handler.handleIllegalArgument(
                new IllegalArgumentException("size must be between 1 and 200"));
        assertThat(safe.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(safe)).isEqualTo("INVALID_REQUEST");
        assertThat(message(safe)).isEqualTo("要求驗證失敗");
    }

    @Test
    void notFoundAndConflictHandlersKeepStableCodesAndFixedChineseMessages() {
        ResponseEntity<ApiError> document = handler.handleDocumentNotFound(
                new DocumentNotFoundException(42L));
        assertThat(document.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(document)).isEqualTo("DOCUMENT_NOT_FOUND");
        assertThat(message(document)).isEqualTo("找不到指定的文件");

        ResponseEntity<ApiError> wikiLifecycle = handler.handleWikiDraftLifecycle(
                new WikiDraftLifecycleException(HOSTILE));
        assertThat(wikiLifecycle.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(wikiLifecycle)).isEqualTo("WIKI_DRAFT_LIFECYCLE_CONFLICT");
        assertThat(message(wikiLifecycle))
                .isEqualTo("Wiki 草稿處於衝突的生命週期狀態");

        ResponseEntity<ApiError> wikiPublish = handler.handleWikiPublish(
                new WikiPublishException(WikiPublishException.Reason.FILESYSTEM_FAILURE,
                        HOSTILE));
        assertThat(wikiPublish.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(code(wikiPublish)).isEqualTo("WIKI_PUBLISH_FILESYSTEM_FAILURE");
        assertThat(message(wikiPublish)).isEqualTo("Wiki 發布操作失敗");
    }

    @Test
    void workspaceFamilyUsesFixedChineseMessages() {
        ResponseEntity<ApiError> workspace = handler.handleWorkspaceNotFound(
                new WorkspaceNotFoundException(99L));
        assertThat(workspace.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(workspace)).isEqualTo("WORKSPACE_NOT_FOUND");
        assertThat(message(workspace)).isEqualTo("找不到指定的工作區");

        ResponseEntity<ApiError> noActive = handler.handleNoActiveWorkspace(
                new NoActiveWorkspaceException());
        assertThat(noActive.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(noActive)).isEqualTo("NO_ACTIVE_WORKSPACE");
        assertThat(message(noActive)).isEqualTo("尚未開啟工作區");
    }

    @Test
    void notFoundFamilyUsesFixedChineseMessages() {
        assertThat(message(handler.handleSourceChunkNotFound(
                new SourceChunkNotFoundException(7L)))).isEqualTo("找不到指定的來源片段");
        assertThat(code(handler.handleSourceChunkNotFound(
                new SourceChunkNotFoundException(7L)))).isEqualTo("SOURCE_CHUNK_NOT_FOUND");

        assertThat(message(handler.handleKnowledgeProposalNotFound(
                new KnowledgeProposalNotFoundException(7L)))).isEqualTo("找不到指定的提案");
        assertThat(message(handler.handleWikiDraftNotFound(
                new WikiDraftNotFoundException(7L)))).isEqualTo("找不到指定的 Wiki 草稿");
        assertThat(message(handler.handleWikiPageNotFound(
                new WikiPageNotFoundException("concept")))).isEqualTo("找不到指定的 Wiki 頁面");
        assertThat(message(handler.handleProcessingJobNotFound(
                new ProcessingJobNotFoundException()))).isEqualTo("找不到指定的處理工作");
        assertThat(message(handler.handleProcessingOperationNotFound(
                new ProcessingOperationNotFoundException()))).isEqualTo("找不到指定的處理工作");
        assertThat(message(handler.handleDocumentAlreadyProcessed(
                new DocumentAlreadyProcessedException(42L, "PROCESSED"))))
                .isEqualTo("文件已處理，無法執行此操作");
    }

    @Test
    void askRepairWikiFamilyUsesFixedChineseMessages() {
        assertThat(message(handler.handleAskCitationInvalid(
                new AskCitationInvalidException(java.util.List.of("WIKI:missing")))))
                .isEqualTo("引用來源驗證失敗");
        assertThat(code(handler.handleAskCitationInvalid(
                new AskCitationInvalidException(java.util.List.of("WIKI:missing")))))
                .isEqualTo("ASK_CITATION_INVALID");

        assertThat(message(handler.handleRepairFindingStale(
                new RepairFindingStaleException("no finding is present for page: concept"))))
                .isEqualTo("修復對象已變動，請重新整理後再試一次");
        assertThat(message(handler.handleRepairNotEligible(
                new RepairNotEligibleException(RepairRefusalReason.SEMANTIC_JUDGMENT_REQUIRED))))
                .isEqualTo("此診斷項目無法修復，僅可檢視");

        assertThat(message(handler.handlePublishedWikiValidation(
                new PublishedWikiValidationException(HOSTILE))))
                .isEqualTo("已發布的 Wiki 內容不可用");
        assertThat(message(handler.handlePublishedWikiUnavailable(
                new PublishedWikiUnavailableException(HOSTILE, new IllegalStateException("x")))))
                .isEqualTo("已發布的 Wiki 內容暫時無法使用");
        assertThat(message(handler.handleWikiDraftTarget(
                new WikiDraftTargetException(WikiDraftTargetException.Reason.TARGET_FILE_MISSING,
                        "Repair target file does not exist in the active vault"))))
                .isEqualTo("Wiki 草稿目標驗證失敗");
    }

    @Test
    void extractionHandlerKeepsItsErrorCodeAndRedactsTheMessage() {
        ResponseEntity<ApiError> response = handler.handleDocumentExtraction(
                new DocumentExtractionException("EXTRACTION_PARSE_FAILED", HOSTILE));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(code(response)).isEqualTo("EXTRACTION_PARSE_FAILED");
        assertThat(message(response)).isEqualTo("文件抽取失敗");
    }

    @Test
    void retrievalUnavailableUsesFixedChineseMessage() {
        ResponseEntity<ApiError> response = handler.handleRetrievalUnavailable(
                new RetrievalUnavailableException(RetrievalUnavailableException.Dependency.GRAPH,
                        new IllegalStateException("backend unavailable")));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(code(response)).isEqualTo("RETRIEVAL_UNAVAILABLE");
        assertThat(message(response)).isEqualTo("檢索服務無法使用");
    }

    @Test
    void safeEnglishExceptionProseNeverBecomesPublicMessage() {
        // #504 language-governance regression: every raw message below is safe for
        // DiagnosticRedaction (no path/secret/SQL/backend marker) and would previously
        // passthrough verbatim. The public projection must stay fixed Chinese.
        assertThat(message(handler.handleWorkspaceNotFound(
                new WorkspaceNotFoundException(42L)))).isNotEqualTo("Workspace not found: 42");
        assertThat(message(handler.handleDocumentNotFound(
                new DocumentNotFoundException(42L)))).isNotEqualTo("Document not found: 42");
        assertThat(message(handler.handleKnowledgeProposalNotFound(
                new KnowledgeProposalNotFoundException(42L))))
                .isNotEqualTo("Knowledge proposal not found: 42");
        assertThat(message(handler.handleWikiDraftNotFound(
                new WikiDraftNotFoundException(42L))))
                .isNotEqualTo("Wiki Draft not found: 42");
        assertThat(message(handler.handleWikiPageNotFound(
                new WikiPageNotFoundException("concept"))))
                .isNotEqualTo("Published Wiki page not found: concept");
        assertThat(message(handler.handleProcessingJobNotFound(
                new ProcessingJobNotFoundException())))
                .isNotEqualTo("Embedding rebuild job not found");
        assertThat(message(handler.handleProcessingOperationNotFound(
                new ProcessingOperationNotFoundException())))
                .isNotEqualTo("Processing job not found");
        assertThat(message(handler.handleNoActiveWorkspace(new NoActiveWorkspaceException())))
                .isNotEqualTo("No active workspace has been registered");
        assertThat(message(handler.handleRetrievalUnavailable(
                new RetrievalUnavailableException(
                        RetrievalUnavailableException.Dependency.GRAPH,
                        new IllegalStateException("backend unavailable")))))
                .isNotEqualTo("Retrieval dependency is unavailable: GRAPH");
        assertThat(message(handler.handleIllegalArgument(
                new IllegalArgumentException("size must be between 1 and 200"))))
                .isNotEqualTo("size must be between 1 and 200");
    }

    @Test
    void graphAndAskTypedMappingsStayUntouched() {
        ResponseEntity<ApiError> graph = handler.handleGraphProjection(
                new GraphProjectionException(new GraphProjectionFailure(
                        GraphProjectionFailureType.BACKEND_LOCKED, HOSTILE)));
        assertThat(graph.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(code(graph)).isEqualTo("GRAPH_BACKEND_LOCKED");
        assertThat(message(graph)).isEqualTo("圖譜投影操作失敗");

        ResponseEntity<ApiError> ask = handler.handleAskFailure(new AskApiException(
                AskFailureType.PROVIDER_TIMEOUT_OR_NETWORK_UNAVAILABLE));
        assertThat(ask.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(code(ask)).isEqualTo(AskFailureType.PROVIDER_TIMEOUT_OR_NETWORK_UNAVAILABLE
                .publicCode());
        assertThat(message(ask)).isEqualTo("回答服務無法使用");
    }

    private static String code(ResponseEntity<ApiError> response) {
        return response.getBody().error().code();
    }

    private static String message(ResponseEntity<ApiError> response) {
        return response.getBody().error().message();
    }
}
