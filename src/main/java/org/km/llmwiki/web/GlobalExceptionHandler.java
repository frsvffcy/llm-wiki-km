package org.km.llmwiki.web;

import org.km.llmwiki.ai.ask.AskApiException;
import org.km.llmwiki.ai.ask.AskDocumentScopeException;
import org.km.llmwiki.ai.ask.AskFailureType;
import org.km.llmwiki.graph.GraphProjectionException;
import org.km.llmwiki.graph.GraphProjectionFailureType;
import org.km.llmwiki.rag.RetrievalUnavailableException;
import org.km.llmwiki.search.FtsRebuildAdmissionConflictException;
import org.km.llmwiki.search.embedding.ProcessingJobNotFoundException;
import org.km.llmwiki.processing.ProcessingOperationNotFoundException;
import org.km.llmwiki.source.DocumentAlreadyProcessedException;
import org.km.llmwiki.source.DocumentExtractionException;
import org.km.llmwiki.source.DocumentNotFoundException;
import org.km.llmwiki.source.SourceChunkNotFoundException;
import org.km.llmwiki.web.security.OwnerAuthenticationException;
import org.km.llmwiki.web.security.OwnerHostRejectedException;
import org.km.llmwiki.web.security.OwnerOriginRejectedException;
import org.km.llmwiki.web.security.OwnerRateLimitedException;
import org.km.llmwiki.wiki.KnowledgeProposalNotFoundException;
import org.km.llmwiki.wiki.WikiDraftLifecycleException;
import org.km.llmwiki.wiki.PublishedWikiUnavailableException;
import org.km.llmwiki.wiki.PublishedWikiValidationException;
import org.km.llmwiki.wiki.AskCitationInvalidException;
import org.km.llmwiki.wiki.RepairFindingStaleException;
import org.km.llmwiki.wiki.RepairNotEligibleException;
import org.km.llmwiki.wiki.WikiDraftNotFoundException;
import org.km.llmwiki.wiki.WikiPageNotFoundException;
import org.km.llmwiki.wiki.WikiDraftTargetException;
import org.km.llmwiki.wiki.WikiPublishException;
import org.km.llmwiki.workspace.DuplicateWorkspaceException;
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Public REST error boundary (#504): stable codes and HTTP statuses are decided by the typed
 * exception, and every application-owned {@code error.message} is a fixed Traditional Chinese
 * (臺灣用語) projection decided by the handler — the raw {@code exception.getMessage()} never
 * becomes user-facing prose, even when it would survive {@link DiagnosticRedaction}.
 * {@code DiagnosticRedaction} remains the safety net for other boundaries (persisted
 * diagnostics, health failure detail, batch failure reason) but is not the localization
 * mechanism for {@code ApiError}. The full exception and its cause chain stay server-side in
 * the application log.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException exception) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "要求驗證失敗", exception);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "無法讀取要求內容", exception);
    }

    /**
     * Typed owner security semantics (#417 §E). Controller-level owner failures
     * (login with a wrong credential, logout/rotation/status without a valid
     * session) use the same stable 401 contract as the filter so a wrong
     * credential, an expired session, and a revoked session are
     * indistinguishable to the caller and never leak workspace or content
     * existence. Bodies carry fixed messages only — never session material,
     * secrets, paths, or backend identities.
     */
    @ExceptionHandler(OwnerAuthenticationException.class)
    public ResponseEntity<ApiError> handleOwnerAuthentication(OwnerAuthenticationException exception) {
        return respond(HttpStatus.UNAUTHORIZED, "OWNER_AUTH_REQUIRED",
                "需要擁有者驗證", exception);
    }

    @ExceptionHandler(OwnerHostRejectedException.class)
    public ResponseEntity<ApiError> handleOwnerHostRejected(OwnerHostRejectedException exception) {
        return respond(HttpStatus.FORBIDDEN, "OWNER_HOST_REJECTED",
                "不允許此要求主機", exception);
    }

    @ExceptionHandler(OwnerOriginRejectedException.class)
    public ResponseEntity<ApiError> handleOwnerOriginRejected(OwnerOriginRejectedException exception) {
        return respond(HttpStatus.FORBIDDEN, "OWNER_ORIGIN_REJECTED",
                "不允許此要求來源", exception);
    }

    @ExceptionHandler(OwnerRateLimitedException.class)
    public ResponseEntity<ApiError> handleOwnerRateLimited(OwnerRateLimitedException exception) {
        return respond(HttpStatus.TOO_MANY_REQUESTS, "OWNER_RATE_LIMITED",
                "要求次數過多，請稍後再試", exception);
    }

    @ExceptionHandler(DuplicateWorkspaceException.class)
    public ResponseEntity<ApiError> handleDuplicateWorkspace(DuplicateWorkspaceException exception) {
        // The raw message embeds the workspace root path; the public projection stays fixed.
        return respond(HttpStatus.CONFLICT, "WORKSPACE_ALREADY_EXISTS",
                "此根目錄路徑已有工作區", exception);
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    public ResponseEntity<ApiError> handleDocumentNotFound(DocumentNotFoundException exception) {
        return respond(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", "找不到指定的文件", exception);
    }

    @ExceptionHandler(SourceChunkNotFoundException.class)
    public ResponseEntity<ApiError> handleSourceChunkNotFound(SourceChunkNotFoundException exception) {
        return respond(HttpStatus.NOT_FOUND, "SOURCE_CHUNK_NOT_FOUND", "找不到指定的來源片段", exception);
    }

    @ExceptionHandler(KnowledgeProposalNotFoundException.class)
    public ResponseEntity<ApiError> handleKnowledgeProposalNotFound(
            KnowledgeProposalNotFoundException exception) {
        return respond(HttpStatus.NOT_FOUND, "KNOWLEDGE_PROPOSAL_NOT_FOUND",
                "找不到指定的提案", exception);
    }

    @ExceptionHandler(WikiDraftNotFoundException.class)
    public ResponseEntity<ApiError> handleWikiDraftNotFound(WikiDraftNotFoundException exception) {
        return respond(HttpStatus.NOT_FOUND, "WIKI_DRAFT_NOT_FOUND", "找不到指定的 Wiki 草稿", exception);
    }

    @ExceptionHandler(AskCitationInvalidException.class)
    public ResponseEntity<ApiError> handleAskCitationInvalid(AskCitationInvalidException exception) {
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, "ASK_CITATION_INVALID",
                "引用來源驗證失敗", exception);
    }

    @ExceptionHandler(RepairFindingStaleException.class)
    public ResponseEntity<ApiError> handleRepairFindingStale(
            RepairFindingStaleException exception) {
        return respond(HttpStatus.CONFLICT, "REPAIR_FINDING_STALE",
                "修復對象已變動，請重新整理後再試一次", exception);
    }

    @ExceptionHandler(RepairNotEligibleException.class)
    public ResponseEntity<ApiError> handleRepairNotEligible(
            RepairNotEligibleException exception) {
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, "REPAIR_NOT_ELIGIBLE",
                "此診斷項目無法修復，僅可檢視", exception);
    }

    @ExceptionHandler(WikiPageNotFoundException.class)
    public ResponseEntity<ApiError> handleWikiPageNotFound(WikiPageNotFoundException exception) {
        return respond(HttpStatus.NOT_FOUND, "WIKI_PAGE_NOT_FOUND", "找不到指定的 Wiki 頁面", exception);
    }

    @ExceptionHandler(PublishedWikiValidationException.class)
    public ResponseEntity<ApiError> handlePublishedWikiValidation(PublishedWikiValidationException exception) {
        // Content failed canonical validation (missing/drifted/invalid): the metadata
        // exists but the page is no longer a trustworthy read — a distinct "失效" state.
        return respond(HttpStatus.CONFLICT, "WIKI_PAGE_UNAVAILABLE",
                "已發布的 Wiki 內容不可用", exception);
    }

    @ExceptionHandler(PublishedWikiUnavailableException.class)
    public ResponseEntity<ApiError> handlePublishedWikiUnavailable(PublishedWikiUnavailableException exception) {
        return respond(HttpStatus.SERVICE_UNAVAILABLE, "WIKI_PAGE_UNAVAILABLE",
                "已發布的 Wiki 內容暫時無法使用", exception);
    }

    @ExceptionHandler(WikiDraftLifecycleException.class)
    public ResponseEntity<ApiError> handleWikiDraftLifecycle(WikiDraftLifecycleException exception) {
        return respond(HttpStatus.CONFLICT, "WIKI_DRAFT_LIFECYCLE_CONFLICT",
                "Wiki 草稿處於衝突的生命週期狀態", exception);
    }

    @ExceptionHandler(WikiDraftTargetException.class)
    public ResponseEntity<ApiError> handleWikiDraftTarget(WikiDraftTargetException exception) {
        return respond(HttpStatus.CONFLICT, "WIKI_DRAFT_TARGET_" + exception.reason().name(),
                "Wiki 草稿目標驗證失敗", exception);
    }

    @ExceptionHandler(WikiPublishException.class)
    public ResponseEntity<ApiError> handleWikiPublish(WikiPublishException exception) {
        HttpStatus status = switch (exception.reason()) {
            case FILESYSTEM_FAILURE, CONTENT_VALIDATION_FAILED, METADATA_FAILURE, RECONCILIATION_REQUIRED ->
                    HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.CONFLICT;
        };
        return respond(status, "WIKI_PUBLISH_" + exception.reason().name(),
                "Wiki 發布操作失敗", exception);
    }

    @ExceptionHandler(DocumentAlreadyProcessedException.class)
    public ResponseEntity<ApiError> handleDocumentAlreadyProcessed(
            DocumentAlreadyProcessedException exception) {
        return respond(HttpStatus.CONFLICT, "DOCUMENT_ALREADY_PROCESSED", "文件已處理，無法執行此操作", exception);
    }

    @ExceptionHandler(DocumentExtractionException.class)
    public ResponseEntity<ApiError> handleDocumentExtraction(DocumentExtractionException exception) {
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, exception.errorCode(),
                "文件抽取失敗", exception);
    }

    @ExceptionHandler(WorkspaceNotFoundException.class)
    public ResponseEntity<ApiError> handleWorkspaceNotFound(WorkspaceNotFoundException exception) {
        return respond(HttpStatus.NOT_FOUND, "WORKSPACE_NOT_FOUND", "找不到指定的工作區", exception);
    }

    @ExceptionHandler(NoActiveWorkspaceException.class)
    public ResponseEntity<ApiError> handleNoActiveWorkspace(NoActiveWorkspaceException exception) {
        return respond(HttpStatus.NOT_FOUND, "NO_ACTIVE_WORKSPACE", "尚未開啟工作區", exception);
    }

    @ExceptionHandler(ProcessingJobNotFoundException.class)
    public ResponseEntity<ApiError> handleProcessingJobNotFound(ProcessingJobNotFoundException exception) {
        return respond(HttpStatus.NOT_FOUND, "PROCESSING_JOB_NOT_FOUND", "找不到指定的處理工作", exception);
    }

    @ExceptionHandler(ProcessingOperationNotFoundException.class)
    public ResponseEntity<ApiError> handleProcessingOperationNotFound(
            ProcessingOperationNotFoundException exception) {
        return respond(HttpStatus.NOT_FOUND, "PROCESSING_JOB_NOT_FOUND", "找不到指定的處理工作", exception);
    }

    @ExceptionHandler(RetrievalUnavailableException.class)
    public ResponseEntity<ApiError> handleRetrievalUnavailable(
            RetrievalUnavailableException exception) {
        return respond(HttpStatus.SERVICE_UNAVAILABLE, "RETRIEVAL_UNAVAILABLE",
                "檢索服務無法使用", exception);
    }

    /** Duplicate FTS rebuild admission is a typed conflict, never a generic 500. */
    @ExceptionHandler(FtsRebuildAdmissionConflictException.class)
    public ResponseEntity<ApiError> handleFtsRebuildAdmissionConflict(
            FtsRebuildAdmissionConflictException exception) {
        return respond(HttpStatus.CONFLICT, "FTS_REBUILD_IN_PROGRESS",
                "此工作區與語料庫已有 FTS 重建作業進行中", exception);
    }

    /**
     * Typed graph projection failure taxonomy for the operational API. Refused-state failures
     * stay 409, operational infrastructure failures stay 503, and integrity/correctness
     * violations fail closed as 500 — corruption is never disguised as a retryable
     * unavailability. Only the stable public code leaves the process; raw causes stay
     * server-side.
     */
    @ExceptionHandler(GraphProjectionException.class)
    public ResponseEntity<ApiError> handleGraphProjection(GraphProjectionException exception) {
        GraphProjectionFailureType type = exception.failureType();
        HttpStatus status = switch (type) {
            case CAPABILITY_DISABLED, CONFIGURATION_INVALID, PROJECTION_NOT_READY,
                    PROJECTION_STALE, PROJECTION_INCOMPATIBLE -> HttpStatus.CONFLICT;
            case CAPABILITY_UNAVAILABLE, BACKEND_LOCKED, FILESYSTEM_UNAVAILABLE,
                    TRANSACTION_FAILURE, BACKEND_FAILURE -> HttpStatus.SERVICE_UNAVAILABLE;
            case PROJECTION_CORRUPT, INVALID_PROJECTION_INPUT, INVALID_PROVENANCE,
                    CROSS_WORKSPACE, INVALID_TRAVERSAL_BOUNDS, LOCAL_VALIDATION ->
                    HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return respond(status, type.publicCode(), "圖譜投影操作失敗", exception);
    }

    @ExceptionHandler(AskApiException.class)
    public ResponseEntity<ApiError> handleAskFailure(AskApiException exception) {
        AskFailureType type = exception.failureType();
        HttpStatus status = switch (type) {
            case LOCAL_VALIDATION -> HttpStatus.BAD_REQUEST;
            case PROVIDER_INVALID_RESPONSE -> HttpStatus.BAD_GATEWAY;
            case RETRIEVAL_UNAVAILABLE,
                    RETRIEVAL_VECTOR_UNAVAILABLE,
                    PROVIDER_CONFIGURATION_UNAVAILABLE,
                    PROVIDER_AUTHENTICATION_OR_AUTHORIZATION,
                    PROVIDER_RATE_LIMIT_OR_QUOTA,
                    PROVIDER_TIMEOUT_OR_NETWORK_UNAVAILABLE,
                    PROVIDER_SERVER_FAILURE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        String message = switch (type) {
            case RETRIEVAL_UNAVAILABLE -> "檢索服務無法使用";
            case RETRIEVAL_VECTOR_UNAVAILABLE -> "語意檢索無法使用";
            case PROVIDER_CONFIGURATION_UNAVAILABLE -> "尚未設定回答服務";
            case PROVIDER_AUTHENTICATION_OR_AUTHORIZATION ->
                    "回答服務驗證失敗";
            case PROVIDER_RATE_LIMIT_OR_QUOTA -> "回答服務已達速率限制";
            case PROVIDER_TIMEOUT_OR_NETWORK_UNAVAILABLE -> "回答服務無法使用";
            case PROVIDER_SERVER_FAILURE -> "回答服務失敗";
            case PROVIDER_INVALID_RESPONSE -> "回答服務回應無效";
            case LOCAL_VALIDATION -> "提問要求遭拒";
        };
        return respond(status, type.publicCode(), message, exception);
    }

    @ExceptionHandler(AskDocumentScopeException.class)
    public ResponseEntity<ApiError> handleAskDocumentScope(AskDocumentScopeException exception) {
        String code = exception.reason() == AskDocumentScopeException.Reason.STALE
                ? "ASK_DOCUMENT_SCOPE_STALE" : "ASK_DOCUMENT_SCOPE_INVALID";
        String message = exception.reason() == AskDocumentScopeException.Reason.STALE
                ? "指定文件的搜尋索引已過期，請重新處理後再提問"
                : "指定文件目前無法用於提問，請重新選擇文件";
        return respond(HttpStatus.CONFLICT, code, message, exception);
    }

    @ExceptionHandler({NoResourceFoundException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleNotFound(Exception exception) {
        return respond(HttpStatus.NOT_FOUND, "NOT_FOUND", "找不到資源", exception);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException exception) {
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "內部伺服器錯誤", exception);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception) {
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "內部伺服器錯誤", exception);
    }

    private static ResponseEntity<ApiError> respond(HttpStatus status, String code, String message,
                                                    Exception exception) {
        // Public response stays fixed/localized (#282/#504). Server diagnostics are
        // severity-aware: unexpected 500s must be visible at the default INFO runtime,
        // while normal 4xx validation/not-found traffic remains quiet unless DEBUG is enabled.
        String request = currentRequest();
        if (status == HttpStatus.INTERNAL_SERVER_ERROR) {
            log.error("REST {} -> {} {}", request, status.value(), code, exception);
        } else if (status.is5xxServerError()) {
            log.warn("REST {} -> {} {}", request, status.value(), code, exception);
        } else {
            log.debug("REST {} -> {} {}", request, status.value(), code, exception);
        }
        return ResponseEntity.status(status).body(ApiError.of(code, message));
    }

    private static String currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            var request = servletAttributes.getRequest();
            // Intentionally omit query string, headers and body: method + path are enough
            // for local correlation without copying user content or credentials into logs.
            return request.getMethod() + " " + request.getRequestURI();
        }
        return "request-unavailable";
    }
}
