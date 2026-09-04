package org.km.llmwiki.graph.arcadedb;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.graph.GraphProjectionInput;
import org.km.llmwiki.graph.GraphProjectionSnapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("graph-spike")
class ArcadeDbGraphProjectionRebuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void rebuildPublishesCompleteInputAndReconcilesStaleRows() {
        var retained = ArcadeDbGraphProjectionFixtures.page(
                ArcadeDbGraphProjectionFixtures.WORKSPACE, "retained", "保留頁");
        var stale = ArcadeDbGraphProjectionFixtures.page(
                ArcadeDbGraphProjectionFixtures.WORKSPACE, "stale", "過期頁");
        var relation = ArcadeDbGraphProjectionFixtures.links(retained, stale);
        GraphProjectionInput first = ArcadeDbGraphProjectionFixtures.input(
                ArcadeDbGraphProjectionFixtures.WORKSPACE, List.of(retained, stale), List.of(relation));
        GraphProjectionInput second = ArcadeDbGraphProjectionFixtures.input(
                ArcadeDbGraphProjectionFixtures.WORKSPACE, retained);

        try (var writer = new ArcadeDbGraphProjectionWriter(tempDir.resolve("rebuild"))) {
            var rebuilder = new ArcadeDbGraphProjectionRebuilder(writer);
            GraphProjectionSnapshot firstSnapshot = rebuilder.rebuild(first);
            assertThat(firstSnapshot.generation()).isEqualTo(1);
            assertThat(writer.currentEntity(stale.identity())).contains(stale);
            assertThat(writer.currentRelation(relation.identity())).contains(relation);

            GraphProjectionSnapshot secondSnapshot = rebuilder.rebuild(second);
            assertThat(secondSnapshot.generation()).isEqualTo(2);
            assertThat(secondSnapshot.sourceFingerprint()).isEqualTo(second.sourceFingerprint());
            assertThat(writer.currentEntity(retained.identity())).contains(retained);
            assertThat(writer.currentEntity(stale.identity())).isEmpty();
            assertThat(writer.currentRelation(relation.identity())).isEmpty();
        }
    }

    @Test
    void deletingDerivedDatabaseAndRebuildingPreservesApplicationProofSemantics() throws IOException {
        var first = ArcadeDbGraphProjectionFixtures.page(
                ArcadeDbGraphProjectionFixtures.WORKSPACE, "same", "相同頁");
        var second = ArcadeDbGraphProjectionFixtures.page(
                ArcadeDbGraphProjectionFixtures.WORKSPACE, "same-target", "相同目標頁");
        var relation = ArcadeDbGraphProjectionFixtures.links(first, second);
        GraphProjectionInput input = ArcadeDbGraphProjectionFixtures.input(
                ArcadeDbGraphProjectionFixtures.WORKSPACE, List.of(first, second), List.of(relation));

        Path databasePath = tempDir.resolve("disposable");
        GraphProjectionSnapshot original;
        try (var writer = new ArcadeDbGraphProjectionWriter(databasePath)) {
            original = new ArcadeDbGraphProjectionRebuilder(writer).rebuild(input);
        }
        deleteContents(databasePath);
        assertThat(Files.exists(databasePath)).isFalse();

        GraphProjectionSnapshot rebuilt;
        try (var writer = new ArcadeDbGraphProjectionWriter(databasePath)) {
            rebuilt = new ArcadeDbGraphProjectionRebuilder(writer).rebuild(input);
            assertThat(writer.currentEntity(first.identity())).contains(first);
            assertThat(writer.currentRelation(relation.identity())).contains(relation);
        }

        assertThat(rebuilt.generation()).isEqualTo(original.generation());
        assertThat(rebuilt.sourceFingerprint()).isEqualTo(original.sourceFingerprint());
        assertThat(rebuilt.snapshotToken()).isEqualTo(original.snapshotToken());
        assertThat(first.identity().stableId()).isEqualTo(
                ArcadeDbGraphProjectionFixtures.page(
                        ArcadeDbGraphProjectionFixtures.WORKSPACE, "same", "不同顯示名稱").identity().stableId());
        assertThat(relation.identity().stableId()).isEqualTo(
                ArcadeDbGraphProjectionFixtures.links(first, second).identity().stableId());
    }

    private static void deleteContents(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
            });
        } catch (java.io.UncheckedIOException exception) {
            throw exception.getCause();
        }
    }
}
