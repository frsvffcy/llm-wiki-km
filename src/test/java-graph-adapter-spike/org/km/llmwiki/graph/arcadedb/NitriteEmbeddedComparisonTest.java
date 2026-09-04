package org.km.llmwiki.graph.arcadedb;

import org.dizitart.no2.Nitrite;
import org.dizitart.no2.collection.Document;
import org.dizitart.no2.collection.NitriteCollection;
import org.dizitart.no2.index.IndexOptions;
import org.dizitart.no2.index.IndexType;
import org.dizitart.no2.mvstore.MVStoreModule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comparison evidence for Nitrite's embedded document persistence.
 *
 * <p>Nitrite is deliberately evaluated as document persistence only; this test does not imply
 * graph, vector, or traversal parity with ArcadeDB.
 */
@Tag("graph-spike")
class NitriteEmbeddedComparisonTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsIndexedDocumentsAcrossCloseAndReopen() {
        Path databasePath = tempDir.resolve("nitrite.db");

        try (Nitrite database = open(databasePath)) {
            NitriteCollection collection = database.getCollection("projection");
            collection.createIndex(IndexOptions.indexOptions(IndexType.UNIQUE),
                    "workspaceId", "stableId");
            collection.createIndex(IndexOptions.indexOptions(IndexType.FULL_TEXT), "content");
            collection.insert(document(41L, "page-a", "graph projection"));
            collection.insert(document(42L, "page-a", "other workspace"));
            database.commit();

            assertThat(collection.hasIndex("workspaceId", "stableId")).isTrue();
            assertThat(collection.hasIndex("content")).isTrue();
            assertThat(collection.find(org.dizitart.no2.filters.FluentFilter.where("stableId")
                    .eq("page-a")).toList()).hasSize(2);
            assertThat(collection.find(org.dizitart.no2.filters.FluentFilter.where("workspaceId")
                    .eq(41L)).firstOrNull().get("content")).isEqualTo("graph projection");
        }

        try (Nitrite database = open(databasePath)) {
            NitriteCollection collection = database.getCollection("projection");
            assertThat(database.listCollectionNames()).contains("projection");
            assertThat(collection.size()).isEqualTo(2);
            assertThat(collection.find(org.dizitart.no2.filters.FluentFilter.where("workspaceId")
                    .eq(42L)).firstOrNull().get("stableId")).isEqualTo("page-a");
            assertThat(collection.listIndices()).extracting(index -> index.getIndexType())
                    .contains(IndexType.UNIQUE, IndexType.FULL_TEXT);
        }
    }

    private static Nitrite open(Path databasePath) {
        return Nitrite.builder()
                .loadModule(MVStoreModule.withConfig().filePath(databasePath.toString()).build())
                .openOrCreate();
    }

    private static Document document(long workspaceId, String stableId, String content) {
        return Document.createDocument("workspaceId", workspaceId)
                .put("stableId", stableId)
                .put("content", content);
    }
}
