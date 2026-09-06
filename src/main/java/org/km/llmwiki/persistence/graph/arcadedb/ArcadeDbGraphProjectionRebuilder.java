package org.km.llmwiki.persistence.graph.arcadedb;

import org.km.llmwiki.graph.GraphProjectionException;
import org.km.llmwiki.graph.GraphProjectionFailureType;
import org.km.llmwiki.graph.GraphProjectionInput;
import org.km.llmwiki.graph.GraphProjectionCleanupStatus;
import org.km.llmwiki.graph.GraphProjectionRebuilder;
import org.km.llmwiki.graph.GraphProjectionReconciliation;
import org.km.llmwiki.graph.GraphProjectionSnapshot;
import org.km.llmwiki.graph.GraphProjectionWriteContext;
import org.km.llmwiki.graph.GraphProjectionWriteResult;
import org.km.llmwiki.graph.GraphProjectionWriteStatus;

/**
 * Generation-owned complete rebuild orchestration for the production adapter.
 *
 * <p>The writer owns all backend transactions. This class only sequences the provider-neutral
 * contract: stage every entity and relation, publish visibility, and reconcile rows absent from
 * the active input. The target generation is reserved by SQLite before this object is created.
 */
public final class ArcadeDbGraphProjectionRebuilder implements GraphProjectionRebuilder {

    private final ArcadeDbGraphProjectionWriter writer;
    private final GraphProjectionSnapshot target;

    public ArcadeDbGraphProjectionRebuilder(ArcadeDbGraphProjectionWriter writer,
                                            GraphProjectionSnapshot target) {
        if (writer == null || target == null) {
            throw new IllegalArgumentException("Projection writer and target snapshot are required");
        }
        this.writer = writer;
        this.target = target;
    }

    @Override
    public GraphProjectionSnapshot rebuild(GraphProjectionInput input) {
        if (input == null) {
            throw new IllegalArgumentException("Graph projection input is required");
        }

        GraphProjectionWriteContext context = GraphProjectionWriteContext.of(input, target);
        input.entities().forEach(entity -> requireAppliedOrNoOp(
                writer.upsertEntity(context, entity), context));
        input.relations().forEach(relation -> requireAppliedOrNoOp(
                writer.upsertRelation(context, relation), context));

        GraphProjectionWriteResult published = writer.publish(context);
        requireAppliedOrNoOp(published, context);

        var reconciliation = GraphProjectionReconciliation.from(input, context.snapshot());
        var cleanup = writer.removeStale(reconciliation);
        if (cleanup.status() == GraphProjectionCleanupStatus.SUPERSEDED) {
            throw staleProjection();
        }

        return writer.currentSnapshot(input.workspace())
                .filter(context.snapshot()::equals)
                .orElseThrow(ArcadeDbGraphProjectionRebuilder::staleProjection);
    }

    private static void requireAppliedOrNoOp(GraphProjectionWriteResult result,
                                             GraphProjectionWriteContext context) {
        if (result.status() == GraphProjectionWriteStatus.SUPERSEDED) {
            throw staleProjection();
        }
        if (!context.workspace().equals(result.workspace()) || context.generation() != result.generation()) {
            throw new GraphProjectionException(GraphProjectionFailureType.BACKEND_FAILURE);
        }
    }

    private static GraphProjectionException staleProjection() {
        return new GraphProjectionException(GraphProjectionFailureType.PROJECTION_STALE);
    }
}
