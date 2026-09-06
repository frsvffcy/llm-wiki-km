package org.km.llmwiki.persistence.graph.arcadedb;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.graph.GraphProjectionException;
import org.km.llmwiki.graph.GraphProjectionFailureType;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class ArcadeDbFailureMapperTest {

    @Test
    void programmingExceptionContainingLockIsNotDisguisedAsBackendFailure() {
        var failure = new IllegalStateException("lock invariant was violated");

        assertThat(ArcadeDbFailureMapper.operation(failure)).isSameAs(failure);
        assertThat(ArcadeDbFailureMapper.opening(failure)).isSameAs(failure);
    }

    @Test
    void explicitFilesystemLockFailureMapsToBackendLocked() {
        var failure = new IllegalStateException(
                "opening failed", new IOException("could not acquire lock for database"));

        assertThat(ArcadeDbFailureMapper.opening(failure))
                .isInstanceOfSatisfying(GraphProjectionException.class,
                        mapped -> assertThat(mapped.failureType())
                                .isEqualTo(GraphProjectionFailureType.BACKEND_LOCKED));
    }

    @Test
    void ordinaryFilesystemFailureMapsToFilesystemUnavailable() {
        var failure = new IllegalStateException(
                "opening failed", new IOException("permission denied"));

        assertThat(ArcadeDbFailureMapper.opening(failure))
                .isInstanceOfSatisfying(GraphProjectionException.class,
                        mapped -> assertThat(mapped.failureType())
                                .isEqualTo(GraphProjectionFailureType.FILESYSTEM_UNAVAILABLE));
    }
}
