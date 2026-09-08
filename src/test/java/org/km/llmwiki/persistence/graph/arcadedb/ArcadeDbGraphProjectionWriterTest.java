package org.km.llmwiki.persistence.graph.arcadedb;

import com.arcadedb.database.DatabaseFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.graph.GraphProjectionException;
import org.km.llmwiki.graph.GraphProjectionFailureType;
import org.km.llmwiki.graph.GraphProjectionInput;
import org.km.llmwiki.graph.GraphProjectionVersion;
import org.km.llmwiki.graph.GraphProjectionWriteContext;
import org.km.llmwiki.graph.GraphProjectionWriteStatus;
import org.km.llmwiki.graph.GraphWorkspaceScope;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
class ArcadeDbGraphProjectionWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTripsApplicationProofAndKeepsStagedRowsInvisibleUntilPublish() {
        var first = ArcadeDbGraphProjectionFixtures.page(
                ArcadeDbGraphProjectionFixtures.WORKSPACE, "page-1", "第一頁");
        var second = ArcadeDbGraphProjectionFixtures.page(
                ArcadeDbGraphProjectionFixtures.WORKSPACE, "page-2", "第二頁");
        var relation = ArcadeDbGraphProjectionFixtures.links(first, second);
        GraphProjectionInput input = ArcadeDbGraphProjectionFixtures.input(
                ArcadeDbGraphProjectionFixtures.WORKSPACE, List.of(first, second), List.of(relation));
        GraphProjectionWriteContext context = GraphProjectionWriteContext.of(input, 1);

        try (var writer = new ArcadeDbGraphProjectionWriter(tempDir.resolve("round-trip"))) {
            assertThat(writer.upsertEntity(context, first).status())
                    .isEqualTo(GraphProjectionWriteStatus.APPLIED);
            assertThat(writer.upsertEntity(context, second).status())
                    .isEqualTo(GraphProjectionWriteStatus.APPLIED);
            assertThat(writer.upsertRelation(context, relation).status())
                    .isEqualTo(GraphProjectionWriteStatus.APPLIED);

            assertThat(writer.currentSnapshot(ArcadeDbGraphProjectionFixtures.WORKSPACE)).isEmpty();
            assertThat(writer.currentEntity(first.identity())).isEmpty();
            assertThat(writer.hasStagedEntity(context, first.identity())).isTrue();

            assertThat(writer.publish(context).status()).isEqualTo(GraphProjectionWriteStatus.APPLIED);
            assertThat(writer.currentSnapshot(ArcadeDbGraphProjectionFixtures.WORKSPACE))
                    .contains(context.snapshot());
            assertThat(writer.currentEntity(first.identity())).contains(first);
            assertThat(writer.currentRelation(relation.identity())).contains(relation);
            assertThat(writer.publish(context).status()).isEqualTo(GraphProjectionWriteStatus.NO_OP);
        }
    }

    @Test
    void removesStaleRowsAndConditionallyClearsOnlyCurrentGeneration() {
        var retained = ArcadeDbGraphProjectionFixtures.page(
                ArcadeDbGraphProjectionFixtures.WORKSPACE, "retained", "保留頁");
        var stale = ArcadeDbGraphProjectionFixtures.page(
                ArcadeDbGraphProjectionFixtures.WORKSPACE, "stale", "過期頁");
        var relation = ArcadeDbGraphProjectionFixtures.links(retained, stale);
        GraphProjectionInput generationOne = ArcadeDbGraphProjectionFixtures.input(
                ArcadeDbGraphProjectionFixtures.WORKSPACE, List.of(retained, stale), List.of(relation));
        GraphProjectionWriteContext firstContext = GraphProjectionWriteContext.of(generationOne, 1);

        GraphProjectionInput generationTwo = ArcadeDbGraphProjectionFixtures.input(
                ArcadeDbGraphProjectionFixtures.WORKSPACE, retained);
        GraphProjectionWriteContext secondContext = GraphProjectionWriteContext.of(generationTwo, 2);

        try (var writer = new ArcadeDbGraphProjectionWriter(tempDir.resolve("cleanup"))) {
            stageAndPublish(writer, firstContext, List.of(retained, stale), List.of(relation));
            stageAndPublish(writer, secondContext, List.of(retained), List.of());

            var cleanup = writer.removeStale(
                    org.km.llmwiki.graph.GraphProjectionReconciliation.from(generationTwo,
                            secondContext.snapshot()));
            assertThat(cleanup.removedEntities()).isEqualTo(2);
            assertThat(cleanup.removedRelations()).isEqualTo(1);
            assertThat(writer.currentEntity(retained.identity())).contains(retained);
            assertThat(writer.currentEntity(stale.identity())).isEmpty();
            assertThat(writer.currentRelation(relation.identity())).isEmpty();

            assertThat(writer.clearWorkspace(ArcadeDbGraphProjectionFixtures.WORKSPACE,
                    secondContext).status()).isEqualTo(GraphProjectionWriteStatus.APPLIED);
            assertThat(writer.currentSnapshot(ArcadeDbGraphProjectionFixtures.WORKSPACE)).isEmpty();
            assertThat(writer.clearWorkspace(ArcadeDbGraphProjectionFixtures.WORKSPACE,
                    secondContext).status()).isEqualTo(GraphProjectionWriteStatus.NO_OP);
        }
    }

    @Test
    void newerGenerationProtectsCurrentAndStagedStateFromOlderOperations() {
        var olderEntity = ArcadeDbGraphProjectionFixtures.page(
                ArcadeDbGraphProjectionFixtures.WORKSPACE, "older", "舊頁");
        var newerEntity = ArcadeDbGraphProjectionFixtures.page(
                ArcadeDbGraphProjectionFixtures.WORKSPACE, "newer", "新頁");
        GraphProjectionInput olderInput = ArcadeDbGraphProjectionFixtures.input(
                ArcadeDbGraphProjectionFixtures.WORKSPACE, olderEntity);
        GraphProjectionInput newerInput = ArcadeDbGraphProjectionFixtures.input(
                ArcadeDbGraphProjectionFixtures.WORKSPACE, newerEntity);
        GraphProjectionWriteContext older = GraphProjectionWriteContext.of(olderInput, 1);
        GraphProjectionWriteContext newer = GraphProjectionWriteContext.of(newerInput, 2);

        try (var writer = new ArcadeDbGraphProjectionWriter(tempDir.resolve("supersession"))) {
            assertThat(writer.upsertEntity(newer, newerEntity).status())
                    .isEqualTo(GraphProjectionWriteStatus.APPLIED);
            assertThat(writer.publish(newer).status()).isEqualTo(GraphProjectionWriteStatus.APPLIED);

            assertThat(writer.upsertEntity(older, olderEntity).status())
                    .isEqualTo(GraphProjectionWriteStatus.SUPERSEDED);
            assertThat(writer.publish(older).status()).isEqualTo(GraphProjectionWriteStatus.SUPERSEDED);
            var olderCleanup = writer.removeStale(
                    org.km.llmwiki.graph.GraphProjectionReconciliation.from(olderInput, older.snapshot()));
            assertThat(olderCleanup.status())
                    .isEqualTo(org.km.llmwiki.graph.GraphProjectionCleanupStatus.SUPERSEDED);
            assertThat(writer.clearWorkspace(ArcadeDbGraphProjectionFixtures.WORKSPACE, older).status())
                    .isEqualTo(GraphProjectionWriteStatus.SUPERSEDED);
            assertThat(writer.currentEntity(newerEntity.identity())).contains(newerEntity);
        }
    }

    @Test
    void versionReplacementAllowsOnlyV1ToV2AndRejectsDowngradeOrUnknownVersion() {
        var currentEntity = ArcadeDbGraphProjectionFixtures.page(
                ArcadeDbGraphProjectionFixtures.WORKSPACE, "versioned", "版本頁");
        GraphProjectionInput currentInput = ArcadeDbGraphProjectionFixtures.input(
                ArcadeDbGraphProjectionFixtures.WORKSPACE, currentEntity);
        GraphProjectionInput legacyInput = versionedInput(currentInput,
                GraphProjectionVersion.legacyV1());
        GraphProjectionInput unknownInput = versionedInput(currentInput,
                new GraphProjectionVersion("graph-projection-v3"));
        GraphProjectionWriteContext legacy = GraphProjectionWriteContext.of(legacyInput, 1);
        GraphProjectionWriteContext current = GraphProjectionWriteContext.of(currentInput, 2);
        GraphProjectionWriteContext downgrade = GraphProjectionWriteContext.of(legacyInput, 3);
        GraphProjectionWriteContext unknown = GraphProjectionWriteContext.of(unknownInput, 3);

        try (var writer = new ArcadeDbGraphProjectionWriter(tempDir.resolve("version-boundary"))) {
            stageAndPublish(writer, legacy, legacyInput.entities(), List.of());
            stageAndPublish(writer, current, currentInput.entities(), List.of());

            assertThat(writer.publish(legacy).status())
                    .isEqualTo(GraphProjectionWriteStatus.SUPERSEDED);
            assertThatThrownBy(() -> writer.upsertEntity(downgrade,
                    downgradeInputEntity(legacyInput)))
                    .isInstanceOf(GraphProjectionException.class)
                    .extracting(failure -> ((GraphProjectionException) failure).failureType())
                    .isEqualTo(GraphProjectionFailureType.PROJECTION_INCOMPATIBLE);
            assertThatThrownBy(() -> writer.upsertEntity(unknown,
                    downgradeInputEntity(unknownInput)))
                    .isInstanceOf(GraphProjectionException.class)
                    .extracting(failure -> ((GraphProjectionException) failure).failureType())
                    .isEqualTo(GraphProjectionFailureType.PROJECTION_INCOMPATIBLE);
            assertThat(writer.currentSnapshot(ArcadeDbGraphProjectionFixtures.WORKSPACE))
                    .contains(current.snapshot());
        }
    }

    @Test
    void sameGenerationConflictsAndBoundaryViolationsFailClosed() {
        var first = ArcadeDbGraphProjectionFixtures.page(
                ArcadeDbGraphProjectionFixtures.WORKSPACE, "same-a", "同代 A");
        var second = ArcadeDbGraphProjectionFixtures.page(
                ArcadeDbGraphProjectionFixtures.WORKSPACE, "same-b", "同代 B");
        GraphProjectionInput firstInput = ArcadeDbGraphProjectionFixtures.input(
                ArcadeDbGraphProjectionFixtures.WORKSPACE, first);
        GraphProjectionInput secondInput = ArcadeDbGraphProjectionFixtures.input(
                ArcadeDbGraphProjectionFixtures.WORKSPACE, second);
        GraphProjectionWriteContext firstContext = GraphProjectionWriteContext.of(firstInput, 1);
        GraphProjectionWriteContext secondContext = GraphProjectionWriteContext.of(secondInput, 1);

        try (var writer = new ArcadeDbGraphProjectionWriter(tempDir.resolve("boundaries"))) {
            writer.upsertEntity(firstContext, first);
            assertThatThrownBy(() -> writer.upsertEntity(secondContext, second))
                    .isInstanceOf(GraphProjectionException.class)
                    .extracting(exception -> ((GraphProjectionException) exception).failureType())
                    .isEqualTo(GraphProjectionFailureType.INVALID_PROJECTION_INPUT);

            var otherWorkspaceEntity = ArcadeDbGraphProjectionFixtures.page(
                    ArcadeDbGraphProjectionFixtures.OTHER_WORKSPACE, "foreign", "外部頁");
            assertThatThrownBy(() -> writer.upsertEntity(firstContext, otherWorkspaceEntity))
                    .isInstanceOf(GraphProjectionException.class)
                    .extracting(exception -> ((GraphProjectionException) exception).failureType())
                    .isEqualTo(GraphProjectionFailureType.CROSS_WORKSPACE);

            GraphProjectionVersion incompatibleVersion = GraphProjectionVersion.legacyV1();
            var incompatible = ArcadeDbGraphProjectionFixtures.page(
                    ArcadeDbGraphProjectionFixtures.WORKSPACE, "incompatible", "不相容頁",
                    incompatibleVersion, ArcadeDbGraphProjectionFixtures.page(
                            ArcadeDbGraphProjectionFixtures.WORKSPACE, "metadata-source", "暫存").metadata(),
                    ArcadeDbGraphProjectionFixtures.page(
                            ArcadeDbGraphProjectionFixtures.WORKSPACE, "provenance-source", "暫存").provenance().metadata(),
                    org.km.llmwiki.graph.GraphFreshness.revision(8));
            assertThatThrownBy(() -> writer.upsertEntity(firstContext, incompatible))
                    .isInstanceOf(GraphProjectionException.class)
                    .extracting(exception -> ((GraphProjectionException) exception).failureType())
                    .isEqualTo(GraphProjectionFailureType.PROJECTION_INCOMPATIBLE);
        }
    }

    @Test
    void mapsEmbeddedOpenFailureToCapabilityUnavailableAndPersistsAcrossReopen() throws Exception {
        Path existingFile = tempDir.resolve("not-a-database");
        Files.createFile(existingFile);
        assertThatThrownBy(() -> new ArcadeDbGraphProjectionWriter(existingFile))
                .isInstanceOf(GraphProjectionException.class)
                .extracting(exception -> ((GraphProjectionException) exception).failureType())
                .isEqualTo(GraphProjectionFailureType.CAPABILITY_UNAVAILABLE);

        var entity = ArcadeDbGraphProjectionFixtures.page(
                ArcadeDbGraphProjectionFixtures.WORKSPACE, "reopen", "重啟頁");
        GraphProjectionInput input = ArcadeDbGraphProjectionFixtures.input(
                ArcadeDbGraphProjectionFixtures.WORKSPACE, entity);
        GraphProjectionWriteContext context = GraphProjectionWriteContext.of(input, 1);
        Path databasePath = tempDir.resolve("reopen");
        try (var writer = new ArcadeDbGraphProjectionWriter(databasePath)) {
            stageAndPublish(writer, context, List.of(entity), List.of());
        }
        try (var reopened = new ArcadeDbGraphProjectionWriter(databasePath)) {
            assertThat(reopened.currentSnapshot(ArcadeDbGraphProjectionFixtures.WORKSPACE))
                    .contains(context.snapshot());
            assertThat(reopened.currentEntity(entity.identity())).contains(entity);
        }
    }

    @Test
    void invalidPersistedSnapshotProofFailsClosedAsProjectionCorrupt() {
        var entity = ArcadeDbGraphProjectionFixtures.page(
                ArcadeDbGraphProjectionFixtures.WORKSPACE, "corrupt", "損壞證據");
        GraphProjectionInput input = ArcadeDbGraphProjectionFixtures.input(
                ArcadeDbGraphProjectionFixtures.WORKSPACE, entity);
        GraphProjectionWriteContext context = GraphProjectionWriteContext.of(input, 1);
        Path databasePath = tempDir.resolve("corrupt-proof");
        try (var writer = new ArcadeDbGraphProjectionWriter(databasePath)) {
            stageAndPublish(writer, context, List.of(entity), List.of());
        }

        overwriteCurrentToken(databasePath, "not-a-valid-snapshot-token");

        try (var reopened = new ArcadeDbGraphProjectionWriter(databasePath)) {
            assertThatThrownBy(() -> reopened.readProof(ArcadeDbGraphProjectionFixtures.WORKSPACE))
                    .isInstanceOf(GraphProjectionException.class)
                    .extracting(failure -> ((GraphProjectionException) failure).failureType())
                    .isEqualTo(GraphProjectionFailureType.PROJECTION_CORRUPT);
        }
    }

    @Test
    void repeatedCloseIsIdempotentAndClosedUseRemainsAProgrammingFailure() {
        var writer = new ArcadeDbGraphProjectionWriter(tempDir.resolve("double-close"));

        assertThatCode(writer::close).doesNotThrowAnyException();
        assertThatCode(writer::close).doesNotThrowAnyException();
        assertThatThrownBy(() -> writer.readProof(ArcadeDbGraphProjectionFixtures.WORKSPACE))
                .isExactlyInstanceOf(IllegalStateException.class);
    }

    private static void stageAndPublish(ArcadeDbGraphProjectionWriter writer,
                                        GraphProjectionWriteContext context,
                                        List<org.km.llmwiki.graph.GraphEntity> entities,
                                        List<org.km.llmwiki.graph.GraphRelation> relations) {
        entities.forEach(entity -> assertThat(writer.upsertEntity(context, entity).status())
                .isIn(GraphProjectionWriteStatus.APPLIED, GraphProjectionWriteStatus.NO_OP));
        relations.forEach(relation -> assertThat(writer.upsertRelation(context, relation).status())
                .isIn(GraphProjectionWriteStatus.APPLIED, GraphProjectionWriteStatus.NO_OP));
        assertThat(writer.publish(context).status())
                .isIn(GraphProjectionWriteStatus.APPLIED, GraphProjectionWriteStatus.NO_OP);
    }

    private static GraphProjectionInput versionedInput(GraphProjectionInput input,
                                                        GraphProjectionVersion version) {
        return new GraphProjectionInput(input.workspace(), version,
                input.entities().stream().map(entity -> new org.km.llmwiki.graph.GraphEntity(
                        entity.identity(), entity.displayName(), entity.provenance(),
                        entity.metadata(), version)).toList(), List.of());
    }

    private static org.km.llmwiki.graph.GraphEntity downgradeInputEntity(
            GraphProjectionInput input) {
        return input.entities().getFirst();
    }

    private static void overwriteCurrentToken(Path databasePath, String token) {
        try (DatabaseFactory factory = new DatabaseFactory(databasePath.toString())
                .setAutoTransaction(false)) {
            var database = factory.open().setReadYourWrites(true);
            try {
                database.transaction(() -> {
                    try (var records = database.lookupByKey(
                            ArcadeDbGraphProjectionWriter.STATE_TYPE,
                            new String[]{"state_key"}, new Object[]{"workspace|41"})) {
                        records.next().asDocument(true).modify()
                                .set("current_token", token)
                                .save();
                    }
                });
            } finally {
                database.close();
            }
        }
    }
}
