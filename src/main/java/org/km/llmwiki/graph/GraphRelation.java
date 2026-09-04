package org.km.llmwiki.graph;

/** Immutable provider-neutral directed Graph Relation projection model. */
public record GraphRelation(GraphRelationIdentity identity, GraphEntityIdentity source,
                            GraphRelationType type, GraphEntityIdentity target,
                            GraphProvenance provenance, GraphMetadata metadata,
                            GraphProjectionVersion projectionVersion) {

    public GraphRelation {
        if (identity == null || source == null || type == null || target == null
                || provenance == null || metadata == null || projectionVersion == null) {
            throw new IllegalArgumentException("Graph relation is incomplete");
        }
        if (!source.workspace().equals(target.workspace())
                || !source.workspace().equals(provenance.authority().workspace())
                || !identity.workspace().equals(source.workspace())) {
            throw new IllegalArgumentException("Graph relation crosses workspace boundary");
        }
        GraphRelationIdentity expected = GraphRelationIdentity.of(source, type, target);
        if (!expected.equals(identity)) {
            throw new IllegalArgumentException("Graph relation identity does not match relation endpoints");
        }
    }

    public static GraphRelation of(GraphEntityIdentity source, GraphRelationType type,
                                   GraphEntityIdentity target, GraphProvenance provenance,
                                   GraphMetadata metadata) {
        return new GraphRelation(GraphRelationIdentity.of(source, type, target), source, type,
                target, provenance, metadata, GraphProjectionVersion.initial());
    }
}
