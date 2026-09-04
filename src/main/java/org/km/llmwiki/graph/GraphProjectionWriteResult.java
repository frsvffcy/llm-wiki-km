package org.km.llmwiki.graph;

/** Safe result for one generation-owned Graph projection mutation or publication. */
public record GraphProjectionWriteResult(GraphProjectionWriteStatus status,
                                         GraphWorkspaceScope workspace, long generation) {

    public GraphProjectionWriteResult {
        if (status == null || workspace == null || generation < 1) {
            throw new IllegalArgumentException("Graph projection write result is invalid");
        }
    }

    public static GraphProjectionWriteResult applied(GraphProjectionWriteContext context) {
        return of(context, GraphProjectionWriteStatus.APPLIED);
    }

    public static GraphProjectionWriteResult noOp(GraphProjectionWriteContext context) {
        return of(context, GraphProjectionWriteStatus.NO_OP);
    }

    public static GraphProjectionWriteResult superseded(GraphProjectionWriteContext context) {
        return of(context, GraphProjectionWriteStatus.SUPERSEDED);
    }

    private static GraphProjectionWriteResult of(GraphProjectionWriteContext context,
                                                 GraphProjectionWriteStatus status) {
        if (context == null) {
            throw new IllegalArgumentException("Graph projection write context is required");
        }
        return new GraphProjectionWriteResult(status, context.workspace(), context.generation());
    }

    public boolean mutated() {
        return status == GraphProjectionWriteStatus.APPLIED;
    }
}
