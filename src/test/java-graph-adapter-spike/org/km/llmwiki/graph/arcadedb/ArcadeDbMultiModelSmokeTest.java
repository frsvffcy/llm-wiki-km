package org.km.llmwiki.graph.arcadedb;

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.database.Document;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.graph.MutableEdge;
import com.arcadedb.graph.MutableVertex;
import com.arcadedb.graph.Vertex;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.index.fulltext.FullTextSearch;
import com.arcadedb.query.select.SelectVectorResult;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused feasibility evidence for ArcadeDB's embedded document, vector, and graph model.
 *
 * <p>This test intentionally exercises the vendor API only inside the opt-in spike source set.
 * The application-facing projection proof remains covered by
 * {@link ArcadeDbGraphProjectionWriterTest}.
 */
@Tag("graph-spike")
class ArcadeDbMultiModelSmokeTest {

    private static final String ENTITY_TYPE = "SmokeEntity";
    private static final String RELATION_TYPE = "SmokeRelation";
    private static final String DOCUMENT_TYPE = "SmokeProjectionDocument";
    private static final String WORKSPACE_ID = "workspace_id";
    private static final String STABLE_ID = "stable_id";
    private static final String CONTENT = "content";
    private static final String EMBEDDING = "embedding";

    @TempDir
    Path tempDir;

    @Test
    void persistsDocumentsVectorsAndBoundedGraphTraversalAcrossRestart() {
        Path databasePath = tempDir.resolve("multi-model");

        try (DatabaseFactory factory = new DatabaseFactory(databasePath.toString())
                .setAutoTransaction(false)) {
            Database database = factory.create().setReadYourWrites(true);
            try {
                initialiseSchema(database);
                seed(database);
                assertSmokeEvidence(database);
            } finally {
                database.close();
            }
        }

        try (DatabaseFactory factory = new DatabaseFactory(databasePath.toString())
                .setAutoTransaction(false)) {
            Database database = factory.open().setReadYourWrites(true);
            try {
                assertSmokeEvidence(database);
            } finally {
                database.close();
            }
        }
    }

    private static void initialiseSchema(Database database) {
        database.transaction(() -> {
            DocumentType entity = database.getSchema().getOrCreateVertexType(ENTITY_TYPE);
            property(entity, WORKSPACE_ID, Type.LONG);
            property(entity, STABLE_ID, Type.STRING);
            property(entity, "label", Type.STRING);
            entity.getOrCreateTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true,
                    WORKSPACE_ID, STABLE_ID);

            database.getSchema().getOrCreateEdgeType(RELATION_TYPE);

            DocumentType projection = database.getSchema().getOrCreateDocumentType(DOCUMENT_TYPE);
            property(projection, WORKSPACE_ID, Type.LONG);
            property(projection, STABLE_ID, Type.STRING);
            property(projection, "canonical_key", Type.STRING);
            property(projection, CONTENT, Type.STRING);
            property(projection, EMBEDDING, Type.ARRAY_OF_FLOATS);
            projection.getOrCreateTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true,
                    WORKSPACE_ID, STABLE_ID);
            projection.getOrCreateTypeIndex(Schema.INDEX_TYPE.FULL_TEXT, false, CONTENT);
            database.getSchema().buildTypeIndex(DOCUMENT_TYPE, new String[]{EMBEDDING})
                    .withLSMVectorType()
                    .withDimensions(3)
                    .create();
        });
    }

    private static void property(DocumentType type, String name, Type propertyType) {
        type.getOrCreateProperty(name, propertyType);
    }

    private static void seed(Database database) {
        database.transaction(() -> {
            MutableVertex first = vertex(database, 41L, "page-a", "第一頁");
            MutableVertex second = vertex(database, 41L, "page-b", "第二頁");
            MutableVertex third = vertex(database, 41L, "page-c", "第三頁");
            vertex(database, 42L, "page-a", "另一個 workspace 的第一頁");

            edge(first, second);
            edge(second, third);

            document(database, 41L, "page-a", "graph projection", new float[]{1.0f, 0.0f, 0.0f});
            document(database, 41L, "page-b", "graph traversal", new float[]{0.8f, 0.2f, 0.0f});
            document(database, 41L, "page-c", "sqlite control plane", new float[]{0.0f, 1.0f, 0.0f});
            document(database, 42L, "page-a", "other workspace", new float[]{0.2f, 0.8f, 0.0f});
        });
    }

    private static MutableVertex vertex(Database database, long workspaceId, String stableId,
                                        String label) {
        MutableVertex vertex = database.newVertex(ENTITY_TYPE)
                .set(WORKSPACE_ID, workspaceId)
                .set(STABLE_ID, stableId)
                .set("label", label);
        vertex.save();
        return vertex;
    }

    private static void edge(MutableVertex source, MutableVertex target) {
        MutableEdge edge = source.newEdge(RELATION_TYPE, target);
        edge.save();
    }

    private static MutableDocument document(Database database, long workspaceId, String stableId,
                                            String content, float[] embedding) {
        MutableDocument document = database.newDocument(DOCUMENT_TYPE)
                .set(WORKSPACE_ID, workspaceId)
                .set(STABLE_ID, stableId)
                .set("canonical_key", "wiki:" + stableId)
                .set(CONTENT, content)
                .set(EMBEDDING, embedding);
        document.save();
        return document;
    }

    private static void assertSmokeEvidence(Database database) {
        Document first = lookup(database, ENTITY_TYPE, 41L, "page-a");
        Document otherWorkspace = lookup(database, ENTITY_TYPE, 42L, "page-a");
        assertThat(first.getLong(WORKSPACE_ID)).isEqualTo(41L);
        assertThat(otherWorkspace.getLong(WORKSPACE_ID)).isEqualTo(42L);
        assertThat(first.getString(STABLE_ID)).isEqualTo(otherWorkspace.getString(STABLE_ID));

        Vertex firstVertex = first.asVertex(true);
        List<Vertex> oneHop = firstVertex.getVertices(Vertex.DIRECTION.OUT, RELATION_TYPE).toList();
        assertThat(oneHop).extracting(vertex -> vertex.getString(STABLE_ID))
                .containsExactly("page-b");
        assertThat(oneHop).allMatch(vertex -> vertex.getLong(WORKSPACE_ID) == 41L);

        Document projection = lookup(database, DOCUMENT_TYPE, 41L, "page-a");
        assertThat(projection.getString("canonical_key")).isEqualTo("wiki:page-a");
        List<float[]> storedEmbedding = projection.getList(EMBEDDING);
        assertThat(storedEmbedding).hasSize(1);
        assertThat(storedEmbedding.getFirst()).containsExactly(1.0f, 0.0f, 0.0f);

        DocumentType projectionType = database.getSchema().getType(DOCUMENT_TYPE);
        TypeIndex fullTextIndex = projectionType.getIndexByProperties(CONTENT);
        assertThat(fullTextIndex).isNotNull();
        Map<?, ?> fullTextMatches = FullTextSearch.search(fullTextIndex, "projection", 10);
        assertThat(fullTextMatches).isNotEmpty();
        List<Document> fullTextDocuments = fullTextMatches.keySet().stream()
                .map(identity -> database.lookupByRID((com.arcadedb.database.RID) identity, true)
                        .asDocument(true))
                .toList();
        assertThat(fullTextDocuments)
                .allMatch(document -> document.getLong(WORKSPACE_ID) == 41L);

        List<SelectVectorResult<Document>> nearest = database.select()
                .fromType(DOCUMENT_TYPE)
                .nearestTo(EMBEDDING, new float[]{1.0f, 0.0f, 0.0f}, 2)
                .documents();
        assertThat(nearest).hasSize(2)
                .extracting(result -> result.getDocument().getString(STABLE_ID))
                .contains("page-a", "page-b");
        assertThat(nearest).allMatch(result -> result.getDocument().getLong(WORKSPACE_ID) == 41L);
    }

    private static Document lookup(Database database, String type, long workspaceId,
                                   String stableId) {
        try (var cursor = database.lookupByKey(type,
                new String[]{WORKSPACE_ID, STABLE_ID}, new Object[]{workspaceId, stableId})) {
            assertThat((Object) cursor).isNotNull();
            assertThat(cursor.hasNext()).as("lookup %s/%s/%s", type, workspaceId, stableId)
                    .isTrue();
            return cursor.next().asDocument(true);
        }
    }
}
