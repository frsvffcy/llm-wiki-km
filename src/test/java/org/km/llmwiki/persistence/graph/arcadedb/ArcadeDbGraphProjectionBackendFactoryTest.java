package org.km.llmwiki.persistence.graph.arcadedb;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.graph.GraphProjectionBackend;
import org.km.llmwiki.graph.GraphProjectionException;
import org.km.llmwiki.graph.GraphProjectionFailureType;
import org.km.llmwiki.graph.GraphProjectionBackendProof;
import org.km.llmwiki.graph.GraphProjectionInput;
import org.km.llmwiki.graph.GraphProjectionSnapshot;
import org.km.llmwiki.graph.GraphProjectionWriteResult;
import org.km.llmwiki.graph.GraphProjectionVersion;
import org.km.llmwiki.graph.GraphWorkspaceScope;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
class ArcadeDbGraphProjectionBackendFactoryTest {

    @TempDir
    Path tempDir;

    @Test
    void missingBackendRemainsAbsentAndDoesNotCreateWorkspaceDirectory() {
        Path base = tempDir.resolve("missing");
        try (var factory = factory(base)) {
            assertThat(factory.openExisting(workspace(1))).isEmpty();
            assertThat(Files.exists(base.resolve("workspace-1"))).isFalse();
        }
    }

    @Test
    void workspaceSessionsAreIsolatedAndCanBeClosedRepeatedly() {
        Path base = tempDir.resolve("isolated");
        var factory = factory(base);
        GraphProjectionBackend first = factory.openForWrite(workspace(1));
        GraphProjectionBackend second = factory.openForWrite(workspace(2));

        assertThat(Files.isDirectory(base.resolve("workspace-1"))).isTrue();
        assertThat(Files.isDirectory(base.resolve("workspace-2"))).isTrue();
        assertThatCode(first::close).doesNotThrowAnyException();
        assertThatCode(first::close).doesNotThrowAnyException();
        assertThatCode(second::close).doesNotThrowAnyException();
        assertThatCode(factory::close).doesNotThrowAnyException();
        assertThatCode(factory::close).doesNotThrowAnyException();
    }

    @Test
    void sameFactoryRejectsSecondWriterUntilFirstSessionCloses() {
        try (var factory = factory(tempDir.resolve("in-process"))) {
            GraphProjectionBackend first = factory.openForWrite(workspace(1));
            assertFailure(GraphProjectionFailureType.BACKEND_LOCKED,
                    () -> factory.openForWrite(workspace(1)));

            first.close();
            assertThatCode(() -> factory.openForWrite(workspace(1)).close())
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void arcadeDbLockRejectsSecondFactoryOpeningSameDatabase() {
        Path base = tempDir.resolve("cross-factory");
        try (var firstFactory = factory(base); var secondFactory = factory(base)) {
            GraphProjectionBackend first = firstFactory.openForWrite(workspace(1));
            assertFailure(GraphProjectionFailureType.BACKEND_LOCKED,
                    () -> secondFactory.openForWrite(workspace(1)));
            first.close();
        }
    }

    @Test
    void closingFactoryClosesOwnedSessionsAndRejectsFurtherOpens() {
        var factory = factory(tempDir.resolve("factory-close"));
        factory.openForWrite(workspace(1));
        factory.openForWrite(workspace(2));

        factory.close();

        assertFailure(GraphProjectionFailureType.CAPABILITY_UNAVAILABLE,
                () -> factory.openForWrite(workspace(1)));
    }

    @Test
    void closeWaitsForInFlightOpenAndClosesTheNewSessionDeterministically() throws Exception {
        CountDownLatch openerEntered = new CountDownLatch(1);
        CountDownLatch allowOpen = new CountDownLatch(1);
        CountDownLatch closeStarted = new CountDownLatch(1);
        AtomicBoolean sessionClosed = new AtomicBoolean();
        var factory = new ArcadeDbGraphProjectionBackendFactory(tempDir.resolve("open-close"),
                GraphProjectionVersion.initial(), (scope, path, create, callback) -> {
                    openerEntered.countDown();
                    await(allowOpen);
                    return new TestBackend(sessionClosed, callback);
                });
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<GraphProjectionBackend> opening = executor.submit(
                    () -> factory.openForWrite(workspace(1)));
            assertThat(openerEntered.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> closing = executor.submit(() -> {
                closeStarted.countDown();
                factory.close();
            });
            assertThat(closeStarted.await(5, TimeUnit.SECONDS)).isTrue();

            allowOpen.countDown();
            GraphProjectionBackend opened = opening.get(5, TimeUnit.SECONDS);
            closing.get(5, TimeUnit.SECONDS);

            assertThat(opened).isNotNull();
            assertThat(sessionClosed).isTrue();
            assertFailure(GraphProjectionFailureType.CAPABILITY_UNAVAILABLE,
                    () -> factory.openForWrite(workspace(1)));
        } finally {
            allowOpen.countDown();
            factory.close();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void repeatedStartStopReleasesTheRealDatabaseHandleForImmediateReopen() {
        Path base = tempDir.resolve("repeated-lifecycle");

        for (int cycle = 0; cycle < 5; cycle++) {
            try (var factory = factory(base)) {
                assertThatCode(() -> factory.openForWrite(workspace(1)).close())
                        .doesNotThrowAnyException();
            }
        }

        try (var finalFactory = factory(base)) {
            var reopened = finalFactory.openExisting(workspace(1));
            assertThat(reopened).isPresent();
            reopened.orElseThrow().close();
        }
    }

    private static ArcadeDbGraphProjectionBackendFactory factory(Path path) {
        return new ArcadeDbGraphProjectionBackendFactory(path, GraphProjectionVersion.initial());
    }

    private static GraphWorkspaceScope workspace(long id) {
        return new GraphWorkspaceScope(id);
    }

    private static void assertFailure(GraphProjectionFailureType expected,
                                      org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(GraphProjectionException.class)
                .extracting(failure -> ((GraphProjectionException) failure).failureType())
                .isEqualTo(expected);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for deterministic test barrier");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for deterministic test barrier",
                    exception);
        }
    }

    private static final class TestBackend implements GraphProjectionBackend {

        private final AtomicBoolean closed;
        private final Runnable closeCallback;

        private TestBackend(AtomicBoolean closed, Runnable closeCallback) {
            this.closed = closed;
            this.closeCallback = closeCallback;
        }

        @Override
        public GraphProjectionSnapshot rebuild(GraphProjectionInput input,
                                               GraphProjectionSnapshot target) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GraphProjectionBackendProof readProof(GraphWorkspaceScope workspace) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GraphProjectionWriteResult clearWorkspace(GraphWorkspaceScope workspace,
                                                          GraphProjectionSnapshot expectedCurrent) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                closeCallback.run();
            }
        }
    }
}
