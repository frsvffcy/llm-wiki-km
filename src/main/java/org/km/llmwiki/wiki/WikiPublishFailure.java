package org.km.llmwiki.wiki;

record WikiPublishFailure(WikiPublishResultType result, WikiPublishFailureCategory category,
                          WikiPublishFailureStage stage, String code, String detail) {

    static WikiPublishFailure from(RuntimeException exception) {
        if (!(exception instanceof WikiPublishException publishException)) {
            return new WikiPublishFailure(WikiPublishResultType.FAILED, WikiPublishFailureCategory.DATABASE,
                    WikiPublishFailureStage.DATABASE_FINALIZATION, "UNEXPECTED_FAILURE",
                    safe(exception.getClass().getSimpleName() + ": " + exception.getMessage()));
        }
        WikiPublishException.Reason reason = publishException.reason();
        WikiPublishFailureCategory category = switch (reason) {
            case DRAFT_NOT_READY, ACTION_NOT_CREATE, ACTION_NOT_MERGE, PROPOSAL_INVALID,
                    CONTENT_VALIDATION_FAILED -> WikiPublishFailureCategory.VALIDATION;
            case TARGET_CONFLICT, TARGET_MISSING, OPTIMISTIC_LOCK_CONFLICT,
                    OPERATION_CONFLICT -> WikiPublishFailureCategory.CONFLICT;
            case FILESYSTEM_FAILURE -> WikiPublishFailureCategory.FILESYSTEM;
            case METADATA_FAILURE -> WikiPublishFailureCategory.DATABASE;
            case PUBLISHED_FILE_DRIFT, RECONCILIATION_REQUIRED -> WikiPublishFailureCategory.RECONCILIATION;
        };
        WikiPublishFailureStage stage = switch (reason) {
            case DRAFT_NOT_READY, ACTION_NOT_CREATE, ACTION_NOT_MERGE, PROPOSAL_INVALID,
                    CONTENT_VALIDATION_FAILED -> WikiPublishFailureStage.VALIDATION;
            case TARGET_CONFLICT, TARGET_MISSING, OPTIMISTIC_LOCK_CONFLICT ->
                    WikiPublishFailureStage.TARGET_CHECK;
            case OPERATION_CONFLICT -> WikiPublishFailureStage.OPERATION_RESERVATION;
            case FILESYSTEM_FAILURE -> WikiPublishFailureStage.FILESYSTEM;
            case METADATA_FAILURE -> WikiPublishFailureStage.DATABASE_FINALIZATION;
            case PUBLISHED_FILE_DRIFT, RECONCILIATION_REQUIRED -> WikiPublishFailureStage.RECONCILIATION;
        };
        WikiPublishResultType result = category == WikiPublishFailureCategory.CONFLICT
                ? WikiPublishResultType.CONFLICT : WikiPublishResultType.FAILED;
        return new WikiPublishFailure(result, category, stage, reason.name(), safe(publishException.getMessage()));
    }

    private static String safe(String detail) {
        if (detail == null || detail.isBlank()) {
            return "Unspecified publish failure";
        }
        return detail.substring(0, Math.min(detail.length(), 1000));
    }
}
