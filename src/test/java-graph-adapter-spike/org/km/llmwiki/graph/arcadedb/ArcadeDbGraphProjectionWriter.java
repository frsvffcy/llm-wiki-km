package org.km.llmwiki.graph.arcadedb;

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.database.Document;
import com.arcadedb.database.Identifiable;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.database.Record;
import com.arcadedb.graph.Edge;
import com.arcadedb.graph.MutableEdge;
import com.arcadedb.graph.MutableVertex;
import com.arcadedb.graph.Vertex;
import com.arcadedb.index.IndexCursor;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import org.km.llmwiki.graph.GraphAuthorityEligibility;
import org.km.llmwiki.graph.GraphAuthorityKind;
import org.km.llmwiki.graph.GraphAuthorityReference;
import org.km.llmwiki.graph.GraphEntity;
import org.km.llmwiki.graph.GraphEntityIdentity;
import org.km.llmwiki.graph.GraphEntityType;
import org.km.llmwiki.graph.GraphFreshness;
import org.km.llmwiki.graph.GraphMetadata;
import org.km.llmwiki.graph.GraphProjectionCleanupResult;
import org.km.llmwiki.graph.GraphProjectionException;
import org.km.llmwiki.graph.GraphProjectionFailure;
import org.km.llmwiki.graph.GraphProjectionFailureType;
import org.km.llmwiki.graph.GraphProjectionInput;
import org.km.llmwiki.graph.GraphProjectionReconciliation;
import org.km.llmwiki.graph.GraphProjectionSnapshot;
import org.km.llmwiki.graph.GraphProjectionVersion;
import org.km.llmwiki.graph.GraphProjectionWriteContext;
import org.km.llmwiki.graph.GraphProjectionWriteResult;
import org.km.llmwiki.graph.GraphProjectionWriteStatus;
import org.km.llmwiki.graph.GraphProjectionWriter;
import org.km.llmwiki.graph.GraphProvenance;
import org.km.llmwiki.graph.GraphRelation;
import org.km.llmwiki.graph.GraphRelationIdentity;
import org.km.llmwiki.graph.GraphRelationType;
import org.km.llmwiki.graph.GraphWorkspaceScope;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Test-only ArcadeDB implementation of the provider-neutral projection contract.
 *
 * <p>This class deliberately lives in the opt-in spike source set. Its public methods expose only
 * domain objects and application-owned proof; ArcadeDB records and RIDs stay inside this mapping
 * boundary.
 */
public final class ArcadeDbGraphProjectionWriter implements GraphProjectionWriter, AutoCloseable {

    static final String ENTITY_TYPE = "KmGraphEntity";
    static final String RELATION_TYPE = "KmGraphRelation";
    static final String STATE_TYPE = "KmGraphProjectionState";
    static final String OWNER_TYPE = "KmGraphProjectionOwner";

    private static final String ROW_KEY = "row_key";
    private static final String STATE_KEY = "state_key";
    private static final String OWNER_KEY = "owner_key";
    private static final String WORKSPACE_ID = "workspace_id";
    private static final String PROJECTION_VERSION = "projection_version";
    private static final String GENERATION = "generation";
    private static final String SOURCE_FINGERPRINT = "source_fingerprint";
    private static final String SNAPSHOT_TOKEN = "snapshot_token";
    private static final String STABLE_ID = "stable_id";
    private static final String CANONICAL_KEY = "canonical_key";
    private static final String ENTITY_TYPE_NAME = "entity_type";
    private static final String DISPLAY_NAME = "display_name";
    private static final String RELATION_TYPE_NAME = "relation_type";
    private static final String SOURCE_STABLE_ID = "source_stable_id";
    private static final String SOURCE_CANONICAL_KEY = "source_canonical_key";
    private static final String SOURCE_ENTITY_TYPE = "source_entity_type";
    private static final String TARGET_STABLE_ID = "target_stable_id";
    private static final String TARGET_CANONICAL_KEY = "target_canonical_key";
    private static final String TARGET_ENTITY_TYPE = "target_entity_type";
    private static final String AUTHORITY_KIND = "authority_kind";
    private static final String AUTHORITY_STABLE_ID = "authority_stable_id";
    private static final String FRESHNESS_REVISION = "freshness_revision";
    private static final String FRESHNESS_HASH = "freshness_hash";
    private static final String ELIGIBILITY = "eligibility";
    private static final String METADATA = "metadata";
    private static final String PROVENANCE_METADATA = "provenance_metadata";

    private static final String KNOWN_VERSION = "known_version";
    private static final String CURRENT_PRESENT = "current_present";
    private static final String CURRENT_GENERATION = "current_generation";
    private static final String CURRENT_FINGERPRINT = "current_fingerprint";
    private static final String CURRENT_TOKEN = "current_token";
    private static final String CLEARED_PRESENT = "cleared_present";
    private static final String CLEARED_GENERATION = "cleared_generation";
    private static final String CLEARED_VERSION = "cleared_version";
    private static final String CLEARED_FINGERPRINT = "cleared_fingerprint";
    private static final String CLEARED_TOKEN = "cleared_token";

    private final DatabaseFactory databaseFactory;
    private final Database database;
    private volatile boolean closed;

    public ArcadeDbGraphProjectionWriter(Path databasePath) {
        if (databasePath == null) {
            throw new IllegalArgumentException("ArcadeDB database path is required");
        }

        DatabaseFactory openedFactory = null;
        Database openedDatabase = null;
        try {
            openedFactory = new DatabaseFactory(databasePath.toString()).setAutoTransaction(false);
            openedDatabase = openedFactory.exists() ? openedFactory.open() : openedFactory.create();
            openedDatabase.setReadYourWrites(true);
            final Database schemaDatabase = openedDatabase;
            openedDatabase.transaction(() -> initialiseSchema(schemaDatabase));
            this.databaseFactory = openedFactory;
            this.database = openedDatabase;
        } catch (RuntimeException exception) {
            closeQuietly(openedDatabase, openedFactory);
            throw new GraphProjectionException(
                    new GraphProjectionFailure(GraphProjectionFailureType.CAPABILITY_UNAVAILABLE,
                            "embedded projection backend could not be opened"), exception);
        }
    }

    @Override
    public GraphProjectionWriteResult upsertEntity(GraphProjectionWriteContext context,
                                                   GraphEntity entity) {
        validateEntity(context, entity);
        return inTransaction(() -> {
            StoredState state = readState(context.workspace());
            GraphProjectionWriteStatus currentStatus = validateCurrent(context, state);
            if (currentStatus != null) {
                return currentStatus == GraphProjectionWriteStatus.SUPERSEDED
                        ? GraphProjectionWriteResult.superseded(context)
                        : GraphProjectionWriteResult.noOp(context);
            }
            claim(context);

            String rowKey = entityRowKey(context, entity.identity().stableId());
            Identifiable existing = findByKey(ENTITY_TYPE, ROW_KEY, rowKey);
            if (existing != null) {
                GraphEntity previous = readEntity(existing.asDocument(true));
                if (!previous.equals(entity)) {
                    throw invalidInput("entity contents conflict with its write proof");
                }
                return GraphProjectionWriteResult.noOp(context);
            }

            MutableVertex vertex = database.newVertex(ENTITY_TYPE);
            writeEntity(vertex, context, entity, rowKey).save();
            return GraphProjectionWriteResult.applied(context);
        });
    }

    @Override
    public GraphProjectionWriteResult upsertRelation(GraphProjectionWriteContext context,
                                                      GraphRelation relation) {
        validateRelation(context, relation);
        return inTransaction(() -> {
            StoredState state = readState(context.workspace());
            GraphProjectionWriteStatus currentStatus = validateCurrent(context, state);
            if (currentStatus != null) {
                return currentStatus == GraphProjectionWriteStatus.SUPERSEDED
                        ? GraphProjectionWriteResult.superseded(context)
                        : GraphProjectionWriteResult.noOp(context);
            }
            claim(context);

            String rowKey = relationRowKey(context, relation.identity().stableId());
            Identifiable existing = findByKey(RELATION_TYPE, ROW_KEY, rowKey);
            if (existing != null) {
                GraphRelation previous = readRelation(existing.asEdge(true));
                if (!previous.equals(relation)) {
                    throw invalidInput("relation contents conflict with its write proof");
                }
                return GraphProjectionWriteResult.noOp(context);
            }

            MutableVertex source = stagedVertex(context, relation.source());
            MutableVertex target = stagedVertex(context, relation.target());
            MutableEdge edge = source.newEdge(RELATION_TYPE, target);
            writeRelation(edge, context, relation, rowKey).save();
            return GraphProjectionWriteResult.applied(context);
        });
    }

    @Override
    public GraphProjectionWriteResult publish(GraphProjectionWriteContext context) {
        requireContext(context);
        return inTransaction(() -> {
            StoredState state = readState(context.workspace());
            GraphProjectionSnapshot current = state.currentSnapshot();
            if (current != null && !context.projectionVersion().equals(current.projectionVersion())) {
                throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_INCOMPATIBLE);
            }
            if (context.isSupersededBy(current)) {
                return GraphProjectionWriteResult.superseded(context);
            }
            if (context.conflictsWith(current)) {
                throw invalidInput("publish proof conflicts with current generation");
            }
            if (current == null && state.clearedSnapshotMatches(context)) {
                return GraphProjectionWriteResult.noOp(context);
            }
            if (current == null && state.isClearedByNewerGeneration(context)) {
                return GraphProjectionWriteResult.superseded(context);
            }
            claim(context);
            if (context.owns(current)) {
                return GraphProjectionWriteResult.noOp(context);
            }

            writeCurrentState(state, context.snapshot());
            return GraphProjectionWriteResult.applied(context);
        });
    }

    @Override
    public GraphProjectionCleanupResult removeStale(GraphProjectionReconciliation reconciliation) {
        if (reconciliation == null) {
            throw new IllegalArgumentException("Graph reconciliation is required");
        }
        return inTransaction(() -> {
            StoredState state = readState(reconciliation.workspace());
            GraphProjectionSnapshot current = state.currentSnapshot();
            if (current == null) {
                throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_NOT_READY);
            }
            if (!reconciliation.snapshot().projectionVersion().equals(current.projectionVersion())) {
                throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_INCOMPATIBLE);
            }
            if (reconciliation.isSupersededBy(current)) {
                return GraphProjectionCleanupResult.superseded(reconciliation);
            }
            if (reconciliation.conflictsWith(current)) {
                throw invalidInput("cleanup proof conflicts with current generation");
            }
            if (!reconciliation.owns(current)) {
                throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_STALE);
            }
            claim(reconciliation.writeContext());

            Set<String> activeEntities = reconciliation.activeEntities().stream()
                    .map(GraphEntityIdentity::stableId).collect(java.util.stream.Collectors.toSet());
            Set<String> activeRelations = reconciliation.activeRelations().stream()
                    .map(GraphRelationIdentity::stableId).collect(java.util.stream.Collectors.toSet());
            int removedRelations = deleteRowsNotActive(RELATION_TYPE, reconciliation.workspace(),
                    current.generation(), activeRelations);
            int removedEntities = deleteRowsNotActive(ENTITY_TYPE, reconciliation.workspace(),
                    current.generation(), activeEntities);
            return GraphProjectionCleanupResult.applied(reconciliation, removedEntities,
                    removedRelations);
        });
    }

    @Override
    public GraphProjectionWriteResult clearWorkspace(GraphWorkspaceScope workspace,
                                                      GraphProjectionWriteContext context) {
        requireWorkspace(workspace);
        requireContext(context);
        if (!workspace.equals(context.workspace())) {
            throw new GraphProjectionException(GraphProjectionFailureType.CROSS_WORKSPACE);
        }
        return inTransaction(() -> {
            StoredState state = readState(workspace);
            GraphProjectionSnapshot current = state.currentSnapshot();
            if (current != null && !context.projectionVersion().equals(current.projectionVersion())) {
                throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_INCOMPATIBLE);
            }
            if (current == null) {
                if (state.isClearedByNewerGeneration(context)) {
                    return GraphProjectionWriteResult.superseded(context);
                }
                if (state.clearedSnapshotMatches(context)) {
                    return GraphProjectionWriteResult.noOp(context);
                }
                if (state.clearedSnapshotConflicts(context)) {
                    throw invalidInput("clear proof conflicts with an applied generation");
                }
                if (state.knownVersion() != null
                        && !state.knownVersion().equals(context.projectionVersion().value())) {
                    throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_INCOMPATIBLE);
                }
                throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_NOT_READY);
            }
            if (context.isSupersededBy(current)) {
                return GraphProjectionWriteResult.superseded(context);
            }
            if (context.conflictsWith(current)) {
                throw invalidInput("clear proof conflicts with current generation");
            }
            if (!context.owns(current)) {
                throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_STALE);
            }

            claim(context);
            int removedRelations = deleteRowsThroughGeneration(RELATION_TYPE, workspace,
                    context.generation());
            int removedEntities = deleteRowsThroughGeneration(ENTITY_TYPE, workspace,
                    context.generation());
            writeClearedState(state, context.snapshot());
            deleteOwnersThroughGeneration(workspace, context.generation());
            return GraphProjectionWriteResult.applied(context);
        });
    }

    /** Returns the current application-owned snapshot proof, if the projection is visible. */
    public Optional<GraphProjectionSnapshot> currentSnapshot(GraphWorkspaceScope workspace) {
        requireWorkspace(workspace);
        return Optional.ofNullable(inTransaction(() -> readState(workspace).currentSnapshot()));
    }

    /** Returns one current-visible entity without exposing a backend record identity. */
    public Optional<GraphEntity> currentEntity(GraphEntityIdentity identity) {
        if (identity == null) {
            throw new IllegalArgumentException("Graph entity identity is required");
        }
        return Optional.ofNullable(inTransaction(() -> {
            GraphProjectionSnapshot current = readState(identity.workspace()).currentSnapshot();
            if (current == null) {
                return null;
            }
            Identifiable record = findByKey(ENTITY_TYPE, ROW_KEY,
                    entityRowKey(current, identity.stableId()));
            return record == null ? null : readEntity(record.asDocument(true));
        }));
    }

    /** Returns one current-visible relation without exposing a backend record identity. */
    public Optional<GraphRelation> currentRelation(GraphRelationIdentity identity) {
        if (identity == null) {
            throw new IllegalArgumentException("Graph relation identity is required");
        }
        return Optional.ofNullable(inTransaction(() -> {
            GraphProjectionSnapshot current = readState(identity.workspace()).currentSnapshot();
            if (current == null) {
                return null;
            }
            Identifiable record = findByKey(RELATION_TYPE, ROW_KEY,
                    relationRowKey(current, identity.stableId()));
            return record == null ? null : readRelation(record.asEdge(true));
        }));
    }

    /** Returns whether a row is staged for the supplied, not-yet-published context. */
    public boolean hasStagedEntity(GraphProjectionWriteContext context, GraphEntityIdentity identity) {
        requireContext(context);
        if (identity == null) {
            throw new IllegalArgumentException("Graph entity identity is required");
        }
        return inTransaction(() -> findByKey(ENTITY_TYPE, ROW_KEY,
                entityRowKey(context.snapshot(), identity.stableId())) != null);
    }

    /** Allocates a monotonic generation from persistent backend state for the rebuild spike. */
    long nextGeneration(GraphWorkspaceScope workspace) {
        requireWorkspace(workspace);
        return inTransaction(() -> {
            StoredState state = readState(workspace);
            long maximum = 0;
            if (state.currentSnapshot() != null) {
                maximum = Math.max(maximum, state.currentSnapshot().generation());
            }
            if (state.clearedSnapshot() != null) {
                maximum = Math.max(maximum, state.clearedSnapshot().generation());
            }
            maximum = Math.max(maximum, maximumGeneration(OWNER_TYPE, workspace));
            maximum = Math.max(maximum, maximumGeneration(ENTITY_TYPE, workspace));
            maximum = Math.max(maximum, maximumGeneration(RELATION_TYPE, workspace));
            if (maximum == Long.MAX_VALUE) {
                throw invalidInput("projection generation exhausted");
            }
            return maximum + 1;
        });
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeQuietly(database, databaseFactory);
    }

    private static void initialiseSchema(Database database) {
        DocumentType entity = database.getSchema().getOrCreateVertexType(ENTITY_TYPE);
        DocumentType relation = database.getSchema().getOrCreateEdgeType(RELATION_TYPE);
        DocumentType state = database.getSchema().getOrCreateDocumentType(STATE_TYPE);
        DocumentType owner = database.getSchema().getOrCreateDocumentType(OWNER_TYPE);

        defineCommonProjectionProperties(entity);
        defineCommonProjectionProperties(relation);
        property(entity, ENTITY_TYPE_NAME, Type.STRING);
        property(entity, DISPLAY_NAME, Type.STRING);
        property(relation, RELATION_TYPE_NAME, Type.STRING);
        property(relation, SOURCE_STABLE_ID, Type.STRING);
        property(relation, SOURCE_CANONICAL_KEY, Type.STRING);
        property(relation, SOURCE_ENTITY_TYPE, Type.STRING);
        property(relation, TARGET_STABLE_ID, Type.STRING);
        property(relation, TARGET_CANONICAL_KEY, Type.STRING);
        property(relation, TARGET_ENTITY_TYPE, Type.STRING);

        property(state, STATE_KEY, Type.STRING);
        property(state, WORKSPACE_ID, Type.LONG);
        property(state, KNOWN_VERSION, Type.STRING);
        property(state, CURRENT_PRESENT, Type.BOOLEAN);
        property(state, CURRENT_GENERATION, Type.LONG);
        property(state, CURRENT_FINGERPRINT, Type.STRING);
        property(state, CURRENT_TOKEN, Type.STRING);
        property(state, CLEARED_PRESENT, Type.BOOLEAN);
        property(state, CLEARED_GENERATION, Type.LONG);
        property(state, CLEARED_VERSION, Type.STRING);
        property(state, CLEARED_FINGERPRINT, Type.STRING);
        property(state, CLEARED_TOKEN, Type.STRING);

        property(owner, OWNER_KEY, Type.STRING);
        property(owner, WORKSPACE_ID, Type.LONG);
        property(owner, GENERATION, Type.LONG);
        property(owner, PROJECTION_VERSION, Type.STRING);
        property(owner, SOURCE_FINGERPRINT, Type.STRING);
        property(owner, SNAPSHOT_TOKEN, Type.STRING);

        entity.getOrCreateTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, ROW_KEY);
        relation.getOrCreateTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, ROW_KEY);
        state.getOrCreateTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, STATE_KEY);
        owner.getOrCreateTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, OWNER_KEY);
    }

    private static void defineCommonProjectionProperties(DocumentType type) {
        property(type, ROW_KEY, Type.STRING);
        property(type, WORKSPACE_ID, Type.LONG);
        property(type, PROJECTION_VERSION, Type.STRING);
        property(type, GENERATION, Type.LONG);
        property(type, SOURCE_FINGERPRINT, Type.STRING);
        property(type, SNAPSHOT_TOKEN, Type.STRING);
        property(type, STABLE_ID, Type.STRING);
        property(type, CANONICAL_KEY, Type.STRING);
        property(type, AUTHORITY_KIND, Type.STRING);
        property(type, AUTHORITY_STABLE_ID, Type.STRING);
        property(type, FRESHNESS_REVISION, Type.LONG);
        property(type, FRESHNESS_HASH, Type.STRING);
        property(type, ELIGIBILITY, Type.STRING);
        property(type, METADATA, Type.MAP);
        property(type, PROVENANCE_METADATA, Type.MAP);
    }

    private static void property(DocumentType type, String name, Type propertyType) {
        type.getOrCreateProperty(name, propertyType);
    }

    private <T> T inTransaction(Supplier<T> action) {
        ensureOpen();
        Object[] result = new Object[1];
        try {
            database.transaction(() -> result[0] = action.get());
            @SuppressWarnings("unchecked")
            T typedResult = (T) result[0];
            return typedResult;
        } catch (GraphProjectionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw backendFailure(exception);
        }
    }

    private StoredState readState(GraphWorkspaceScope workspace) {
        Identifiable record = findByKey(STATE_TYPE, STATE_KEY, stateKey(workspace));
        return record == null ? StoredState.empty() : StoredState.read(record.asDocument(true));
    }

    private void claim(GraphProjectionWriteContext context) {
        String ownerKey = ownerKey(context);
        Identifiable existing = findByKey(OWNER_TYPE, OWNER_KEY, ownerKey);
        if (existing != null) {
            Document owner = existing.asDocument(true);
            if (!context.projectionVersion().value().equals(owner.getString(PROJECTION_VERSION))
                    || !context.sourceFingerprint().equals(owner.getString(SOURCE_FINGERPRINT))
                    || !context.snapshotToken().equals(owner.getString(SNAPSHOT_TOKEN))) {
                throw invalidInput("generation already has another write proof");
            }
            return;
        }

        database.newDocument(OWNER_TYPE)
                .set(OWNER_KEY, ownerKey)
                .set(WORKSPACE_ID, context.workspace().id())
                .set(GENERATION, context.generation())
                .set(PROJECTION_VERSION, context.projectionVersion().value())
                .set(SOURCE_FINGERPRINT, context.sourceFingerprint())
                .set(SNAPSHOT_TOKEN, context.snapshotToken())
                .save();
    }

    private GraphProjectionWriteStatus validateCurrent(GraphProjectionWriteContext context,
                                                        StoredState state) {
        GraphProjectionSnapshot current = state.currentSnapshot();
        if (current != null && !context.projectionVersion().equals(current.projectionVersion())) {
            throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_INCOMPATIBLE);
        }
        if (context.isSupersededBy(current)) {
            return GraphProjectionWriteStatus.SUPERSEDED;
        }
        if (context.conflictsWith(current)) {
            throw invalidInput("write proof conflicts with current generation");
        }
        if (current == null && state.clearedSnapshotMatches(context)) {
            return GraphProjectionWriteStatus.NO_OP;
        }
        if (current == null && state.isClearedByNewerGeneration(context)) {
            return GraphProjectionWriteStatus.SUPERSEDED;
        }
        if (current == null && state.clearedSnapshotConflicts(context)) {
            throw invalidInput("write proof conflicts with an applied generation");
        }
        return null;
    }

    private MutableVertex stagedVertex(GraphProjectionWriteContext context,
                                       GraphEntityIdentity identity) {
        Identifiable record = findByKey(ENTITY_TYPE, ROW_KEY,
                entityRowKey(context.snapshot(), identity.stableId()));
        if (record == null) {
            throw invalidInput("relation endpoint is not staged in the write context");
        }
        return record.asVertex(true).modify();
    }

    private int deleteRowsNotActive(String type, GraphWorkspaceScope workspace, long throughGeneration,
                                    Set<String> activeStableIds) {
        return deleteRows(type, document -> workspace.id() == document.getLong(WORKSPACE_ID)
                && document.getLong(GENERATION) <= throughGeneration
                && !activeStableIds.contains(document.getString(STABLE_ID)));
    }

    private int deleteRowsThroughGeneration(String type, GraphWorkspaceScope workspace,
                                            long throughGeneration) {
        return deleteRows(type, document -> workspace.id() == document.getLong(WORKSPACE_ID)
                && document.getLong(GENERATION) <= throughGeneration);
    }

    private int deleteRows(String type, java.util.function.Predicate<Document> predicate) {
        List<Record> records = recordsOfType(type);
        int deleted = 0;
        for (Record record : records) {
            Document document = record.asDocument(true);
            if (predicate.test(document)) {
                record.delete();
                deleted++;
            }
        }
        return deleted;
    }

    private void deleteOwnersThroughGeneration(GraphWorkspaceScope workspace, long throughGeneration) {
        deleteRows(OWNER_TYPE, document -> workspace.id() == document.getLong(WORKSPACE_ID)
                && document.getLong(GENERATION) <= throughGeneration);
    }

    private long maximumGeneration(String type, GraphWorkspaceScope workspace) {
        long maximum = 0;
        for (Record record : recordsOfType(type)) {
            Document document = record.asDocument(true);
            if (workspace.id() == document.getLong(WORKSPACE_ID)) {
                Long generation = document.getLong(GENERATION);
                if (generation != null) {
                    maximum = Math.max(maximum, generation);
                }
            }
        }
        return maximum;
    }

    private List<Record> recordsOfType(String type) {
        List<Record> records = new ArrayList<>();
        Iterator<Record> iterator = database.iterateType(type, false);
        while (iterator.hasNext()) {
            records.add(iterator.next());
        }
        return records;
    }

    private Identifiable findByKey(String type, String keyProperty, String key) {
        try (IndexCursor cursor = database.lookupByKey(type, keyProperty, key)) {
            if (cursor != null && cursor.hasNext()) {
                return cursor.next();
            }
            return null;
        }
    }

    private static MutableVertex writeEntity(MutableVertex vertex,
                                              GraphProjectionWriteContext context,
                                              GraphEntity entity, String rowKey) {
        setCommon(vertex, context, rowKey, entity.identity().stableId(),
                entity.identity().canonicalKey(), entity.provenance());
        vertex.set(ENTITY_TYPE_NAME, entity.identity().type().name())
                .set(DISPLAY_NAME, entity.displayName());
        setMetadata(vertex, entity.metadata());
        setProvenanceMetadata(vertex, entity.provenance());
        return vertex;
    }

    private static MutableEdge writeRelation(MutableEdge edge,
                                             GraphProjectionWriteContext context,
                                             GraphRelation relation, String rowKey) {
        setCommon(edge, context, rowKey, relation.identity().stableId(),
                relation.identity().stableId(), relation.provenance());
        edge.set(RELATION_TYPE_NAME, relation.type().name())
                .set(SOURCE_STABLE_ID, relation.source().stableId())
                .set(SOURCE_CANONICAL_KEY, relation.source().canonicalKey())
                .set(SOURCE_ENTITY_TYPE, relation.source().type().name())
                .set(TARGET_STABLE_ID, relation.target().stableId())
                .set(TARGET_CANONICAL_KEY, relation.target().canonicalKey())
                .set(TARGET_ENTITY_TYPE, relation.target().type().name());
        setMetadata(edge, relation.metadata());
        setProvenanceMetadata(edge, relation.provenance());
        return edge;
    }

    private static void setCommon(MutableDocument document, GraphProjectionWriteContext context,
                                  String rowKey, String stableId, String canonicalKey,
                                  GraphProvenance provenance) {
        document.set(ROW_KEY, rowKey)
                .set(WORKSPACE_ID, context.workspace().id())
                .set(PROJECTION_VERSION, context.projectionVersion().value())
                .set(GENERATION, context.generation())
                .set(SOURCE_FINGERPRINT, context.sourceFingerprint())
                .set(SNAPSHOT_TOKEN, context.snapshotToken())
                .set(STABLE_ID, stableId)
                .set(CANONICAL_KEY, canonicalKey)
                .set(AUTHORITY_KIND, provenance.authority().kind().name())
                .set(AUTHORITY_STABLE_ID, provenance.authority().stableId())
                .set(ELIGIBILITY, provenance.eligibility().name());
        if (provenance.freshness().revision() == null) {
            document.remove(FRESHNESS_REVISION);
        } else {
            document.set(FRESHNESS_REVISION, provenance.freshness().revision().longValue());
        }
        if (provenance.freshness().contentHash() == null) {
            document.remove(FRESHNESS_HASH);
        } else {
            document.set(FRESHNESS_HASH, provenance.freshness().contentHash());
        }
    }

    private static void setMetadata(MutableDocument document, GraphMetadata metadata) {
        document.set(METADATA, new LinkedHashMap<>(metadata.entries()));
    }

    private static void setProvenanceMetadata(MutableDocument document, GraphProvenance provenance) {
        document.set(PROVENANCE_METADATA, new LinkedHashMap<>(provenance.metadata().entries()));
    }

    private static GraphEntity readEntity(Document document) {
        GraphWorkspaceScope workspace = workspace(document);
        GraphEntityType type = GraphEntityType.valueOf(document.getString(ENTITY_TYPE_NAME));
        GraphEntityIdentity identity = new GraphEntityIdentity(workspace, type,
                document.getString(CANONICAL_KEY), document.getString(STABLE_ID));
        return new GraphEntity(identity, document.getString(DISPLAY_NAME), provenance(document),
                metadata(document), new GraphProjectionVersion(document.getString(PROJECTION_VERSION)));
    }

    private static GraphRelation readRelation(Edge edge) {
        GraphWorkspaceScope workspace = workspace(edge);
        GraphEntityIdentity source = new GraphEntityIdentity(workspace,
                GraphEntityType.valueOf(edge.getString(SOURCE_ENTITY_TYPE)),
                edge.getString(SOURCE_CANONICAL_KEY), edge.getString(SOURCE_STABLE_ID));
        GraphEntityIdentity target = new GraphEntityIdentity(workspace,
                GraphEntityType.valueOf(edge.getString(TARGET_ENTITY_TYPE)),
                edge.getString(TARGET_CANONICAL_KEY), edge.getString(TARGET_STABLE_ID));
        GraphRelationType type = GraphRelationType.valueOf(edge.getString(RELATION_TYPE_NAME));
        GraphRelationIdentity identity = new GraphRelationIdentity(workspace, source, type, target,
                edge.getString(STABLE_ID));
        return new GraphRelation(identity, source, type, target, provenance(edge), metadata(edge),
                new GraphProjectionVersion(edge.getString(PROJECTION_VERSION)));
    }

    private static GraphProvenance provenance(Document document) {
        GraphWorkspaceScope workspace = workspace(document);
        GraphAuthorityReference authority = new GraphAuthorityReference(workspace,
                GraphAuthorityKind.valueOf(document.getString(AUTHORITY_KIND)),
                document.getString(AUTHORITY_STABLE_ID));
        Long revision = document.getLong(FRESHNESS_REVISION);
        String contentHash = document.getString(FRESHNESS_HASH);
        GraphFreshness freshness = new GraphFreshness(revision == null ? null : revision.intValue(),
                contentHash);
        return new GraphProvenance(authority, freshness,
                GraphAuthorityEligibility.valueOf(document.getString(ELIGIBILITY)),
                metadataValue(document.get(PROVENANCE_METADATA)));
    }

    private static GraphWorkspaceScope workspace(Document document) {
        return new GraphWorkspaceScope(document.getLong(WORKSPACE_ID));
    }

    private static GraphMetadata metadata(Document document) {
        return metadataValue(document.get(METADATA));
    }

    private static GraphMetadata metadataValue(Object value) {
        if (!(value instanceof Map<?, ?> values)) {
            return GraphMetadata.empty();
        }
        Map<String, String> entries = new HashMap<>();
        values.forEach((key, mapValue) -> entries.put(String.valueOf(key), String.valueOf(mapValue)));
        return GraphMetadata.of(entries);
    }

    private static String stateKey(GraphWorkspaceScope workspace) {
        return "workspace|" + workspace.id();
    }

    private static String ownerKey(GraphProjectionWriteContext context) {
        return context.workspace().id() + "|" + context.generation();
    }

    private static String entityRowKey(GraphProjectionWriteContext context, String stableId) {
        return entityRowKey(context.snapshot(), stableId);
    }

    private static String entityRowKey(GraphProjectionSnapshot snapshot, String stableId) {
        return snapshot.workspace().id() + "|" + snapshot.projectionVersion().value() + "|entity|"
                + stableId + "|" + snapshot.generation();
    }

    private static String relationRowKey(GraphProjectionWriteContext context, String stableId) {
        return relationRowKey(context.snapshot(), stableId);
    }

    private static String relationRowKey(GraphProjectionSnapshot snapshot, String stableId) {
        return snapshot.workspace().id() + "|" + snapshot.projectionVersion().value() + "|relation|"
                + stableId + "|" + snapshot.generation();
    }

    private void writeCurrentState(StoredState state, GraphProjectionSnapshot snapshot) {
        MutableDocument document = state.mutableDocument(database);
        document.set(STATE_KEY, stateKey(snapshot.workspace()))
                .set(WORKSPACE_ID, snapshot.workspace().id())
                .set(KNOWN_VERSION, snapshot.projectionVersion().value())
                .set(CURRENT_PRESENT, true)
                .set(CURRENT_GENERATION, snapshot.generation())
                .set(CURRENT_FINGERPRINT, snapshot.sourceFingerprint())
                .set(CURRENT_TOKEN, snapshot.snapshotToken());
        document.remove(CLEARED_VERSION);
        document.remove(CLEARED_GENERATION);
        document.remove(CLEARED_FINGERPRINT);
        document.remove(CLEARED_TOKEN);
        document.set(CLEARED_PRESENT, false).save();
    }

    private void writeClearedState(StoredState state, GraphProjectionSnapshot snapshot) {
        MutableDocument document = state.mutableDocument(database);
        document.set(STATE_KEY, stateKey(snapshot.workspace()))
                .set(WORKSPACE_ID, snapshot.workspace().id())
                .set(KNOWN_VERSION, snapshot.projectionVersion().value())
                .set(CURRENT_PRESENT, false)
                .set(CLEARED_PRESENT, true)
                .set(CLEARED_GENERATION, snapshot.generation())
                .set(CLEARED_VERSION, snapshot.projectionVersion().value())
                .set(CLEARED_FINGERPRINT, snapshot.sourceFingerprint())
                .set(CLEARED_TOKEN, snapshot.snapshotToken());
        document.remove(CURRENT_GENERATION);
        document.remove(CURRENT_FINGERPRINT);
        document.remove(CURRENT_TOKEN);
        document.save();
    }

    private static void validateEntity(GraphProjectionWriteContext context, GraphEntity entity) {
        requireContext(context);
        if (entity == null || !context.workspace().equals(entity.identity().workspace())) {
            throw new GraphProjectionException(GraphProjectionFailureType.CROSS_WORKSPACE);
        }
        if (!context.projectionVersion().equals(entity.projectionVersion())) {
            throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_INCOMPATIBLE);
        }
        if (!context.matches(entity)) {
            throw invalidInput("entity does not belong to its write proof");
        }
    }

    private static void validateRelation(GraphProjectionWriteContext context,
                                         GraphRelation relation) {
        requireContext(context);
        if (relation == null || !context.workspace().equals(relation.identity().workspace())) {
            throw new GraphProjectionException(GraphProjectionFailureType.CROSS_WORKSPACE);
        }
        if (!context.projectionVersion().equals(relation.projectionVersion())) {
            throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_INCOMPATIBLE);
        }
        if (!context.matches(relation)) {
            throw invalidInput("relation does not belong to its write proof");
        }
    }

    private static void requireContext(GraphProjectionWriteContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Graph projection write context is required");
        }
    }

    private static void requireWorkspace(GraphWorkspaceScope workspace) {
        if (workspace == null) {
            throw new IllegalArgumentException("Graph workspace is required");
        }
    }

    private void ensureOpen() {
        if (closed || !database.isOpen()) {
            throw new IllegalStateException("ArcadeDB projection writer is closed");
        }
    }

    private static GraphProjectionException invalidInput(String diagnostic) {
        return new GraphProjectionException(new GraphProjectionFailure(
                GraphProjectionFailureType.INVALID_PROJECTION_INPUT, diagnostic));
    }

    private static GraphProjectionException backendFailure(RuntimeException cause) {
        return new GraphProjectionException(new GraphProjectionFailure(
                GraphProjectionFailureType.BACKEND_FAILURE,
                "embedded projection transaction failed"), cause);
    }

    private static void closeQuietly(Database database, DatabaseFactory factory) {
        if (database != null) {
            try {
                if (database.isOpen()) {
                    database.close();
                }
            } catch (RuntimeException ignored) {
                // Preserve the original capability failure during construction/close.
            }
        }
        if (factory != null) {
            try {
                factory.close();
            } catch (RuntimeException ignored) {
                // Preserve the original capability failure during construction/close.
            }
        }
    }

    private static final class StoredState {
        private final Document document;
        private final String knownVersion;
        private final GraphProjectionSnapshot currentSnapshot;
        private final GraphProjectionSnapshot clearedSnapshot;

        private StoredState(Document document, String knownVersion,
                            GraphProjectionSnapshot currentSnapshot,
                            GraphProjectionSnapshot clearedSnapshot) {
            this.document = document;
            this.knownVersion = knownVersion;
            this.currentSnapshot = currentSnapshot;
            this.clearedSnapshot = clearedSnapshot;
        }

        static StoredState empty() {
            return new StoredState(null, null, null, null);
        }

        static StoredState read(Document document) {
            return new StoredState(document, document.getString(KNOWN_VERSION),
                    readSnapshot(document, CURRENT_PRESENT, CURRENT_GENERATION,
                            KNOWN_VERSION, CURRENT_FINGERPRINT, CURRENT_TOKEN),
                    readSnapshot(document, CLEARED_PRESENT, CLEARED_GENERATION,
                            CLEARED_VERSION, CLEARED_FINGERPRINT, CLEARED_TOKEN));
        }

        private static GraphProjectionSnapshot readSnapshot(Document document, String presentKey,
                                                            String generationKey, String versionKey,
                                                            String fingerprintKey, String tokenKey) {
            if (!Boolean.TRUE.equals(document.getBoolean(presentKey))) {
                return null;
            }
            Long generation = document.getLong(generationKey);
            String version = document.getString(versionKey);
            String fingerprint = document.getString(fingerprintKey);
            String token = document.getString(tokenKey);
            return new GraphProjectionSnapshot(new GraphWorkspaceScope(document.getLong(WORKSPACE_ID)),
                    new GraphProjectionVersion(version), generation, fingerprint, token);
        }

        String knownVersion() {
            return knownVersion;
        }

        GraphProjectionSnapshot currentSnapshot() {
            return currentSnapshot;
        }

        GraphProjectionSnapshot clearedSnapshot() {
            return clearedSnapshot;
        }

        boolean clearedSnapshotMatches(GraphProjectionWriteContext context) {
            return clearedSnapshot != null && clearedSnapshot.equals(context.snapshot());
        }

        boolean isClearedByNewerGeneration(GraphProjectionWriteContext context) {
            return clearedSnapshot != null
                    && clearedSnapshot.workspace().equals(context.workspace())
                    && clearedSnapshot.generation() > context.generation();
        }

        boolean clearedSnapshotConflicts(GraphProjectionWriteContext context) {
            return clearedSnapshot != null
                    && clearedSnapshot.workspace().equals(context.workspace())
                    && clearedSnapshot.generation() == context.generation()
                    && !clearedSnapshot.equals(context.snapshot());
        }

        MutableDocument mutableDocument(Database database) {
            return document == null ? database.newDocument(STATE_TYPE) : document.modify();
        }
    }
}
