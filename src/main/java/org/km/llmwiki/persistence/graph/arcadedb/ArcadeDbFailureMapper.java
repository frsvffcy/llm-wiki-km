package org.km.llmwiki.persistence.graph.arcadedb;

import com.arcadedb.exception.BrokenChunkChainException;
import com.arcadedb.exception.ConcurrentModificationException;
import com.arcadedb.exception.DatabaseIsClosedException;
import com.arcadedb.exception.DatabaseIsReadOnlyException;
import com.arcadedb.exception.DatabaseNotAvailableException;
import com.arcadedb.exception.LockTimeoutException;
import com.arcadedb.exception.NeedRetryException;
import com.arcadedb.exception.PageCorruptionException;
import com.arcadedb.exception.TransactionException;
import com.arcadedb.exception.WALVersionGapException;
import org.km.llmwiki.graph.GraphProjectionException;
import org.km.llmwiki.graph.GraphProjectionFailure;
import org.km.llmwiki.graph.GraphProjectionFailureType;

import java.io.IOException;
import java.util.Locale;

/** Maps known infrastructure failures without disguising application programming errors. */
final class ArcadeDbFailureMapper {

    private ArcadeDbFailureMapper() {
    }

    static RuntimeException opening(RuntimeException failure) {
        return map(failure, true);
    }

    static RuntimeException operation(RuntimeException failure) {
        return map(failure, false);
    }

    private static RuntimeException map(RuntimeException failure, boolean opening) {
        if (failure instanceof GraphProjectionException) {
            return failure;
        }
        GraphProjectionFailureType type = classify(failure, opening);
        if (type == null) {
            return failure;
        }
        return new GraphProjectionException(new GraphProjectionFailure(type, diagnostic(type)), failure);
    }

    private static GraphProjectionFailureType classify(Throwable failure, boolean opening) {
        for (Throwable cursor = failure; cursor != null; cursor = cursor.getCause()) {
            boolean arcadeDbOwned = isArcadeDbOwned(cursor);
            if (cursor instanceof LockTimeoutException
                    || (arcadeDbOwned || cursor instanceof IOException) && lockMessage(cursor)) {
                return GraphProjectionFailureType.BACKEND_LOCKED;
            }
            if (cursor instanceof PageCorruptionException || cursor instanceof WALVersionGapException
                    || cursor instanceof BrokenChunkChainException) {
                return GraphProjectionFailureType.PROJECTION_CORRUPT;
            }
            if (cursor instanceof DatabaseIsReadOnlyException || cursor instanceof IOException) {
                return GraphProjectionFailureType.FILESYSTEM_UNAVAILABLE;
            }
            if (cursor instanceof TransactionException || cursor instanceof NeedRetryException
                    || cursor instanceof ConcurrentModificationException) {
                return GraphProjectionFailureType.TRANSACTION_FAILURE;
            }
            if (cursor instanceof DatabaseNotAvailableException
                    || cursor instanceof DatabaseIsClosedException) {
                return GraphProjectionFailureType.CAPABILITY_UNAVAILABLE;
            }
            if (arcadeDbOwned) {
                return opening ? GraphProjectionFailureType.CAPABILITY_UNAVAILABLE
                        : GraphProjectionFailureType.BACKEND_FAILURE;
            }
        }
        return null;
    }

    private static boolean lockMessage(Throwable failure) {
        String message = failure.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains(" locked") || lower.startsWith("locked")
                || lower.contains("lock timeout") || lower.contains("lock conflict")
                || lower.contains("cannot acquire lock") || lower.contains("could not acquire lock")
                || lower.contains("already open") || lower.contains("already in use");
    }

    private static boolean isArcadeDbOwned(Throwable failure) {
        Package owner = failure.getClass().getPackage();
        return owner != null && owner.getName().startsWith("com.arcadedb");
    }

    private static String diagnostic(GraphProjectionFailureType type) {
        return switch (type) {
            case BACKEND_LOCKED -> "projection backend is locked by another process";
            case FILESYSTEM_UNAVAILABLE -> "projection storage is unavailable";
            case PROJECTION_CORRUPT -> "projection storage requires rebuild";
            case TRANSACTION_FAILURE -> "projection transaction did not complete";
            case CAPABILITY_UNAVAILABLE -> "projection backend is unavailable";
            default -> "projection backend operation failed";
        };
    }
}
