package org.km.llmwiki.graph;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic projection input assembled from canonical authority and bounded metadata.
 *
 * <p>The input is sorted and copied at construction time. An adapter therefore receives the same
 * logical order and source fingerprint on every rebuild, regardless of backend insertion order.
 */
public record GraphProjectionInput(GraphWorkspaceScope workspace,
                                   GraphProjectionVersion projectionVersion,
                                   List<GraphEntity> entities, List<GraphRelation> relations,
                                   String sourceFingerprint) {

    public GraphProjectionInput {
        if (workspace == null || projectionVersion == null || entities == null || relations == null) {
            throw new IllegalArgumentException("Graph projection input is incomplete");
        }
        entities = sortedEntities(workspace, entities);
        relations = sortedRelations(workspace, relations);
        Set<GraphEntityIdentity> entityIdentities = new HashSet<>();
        for (GraphEntity entity : entities) {
            if (!entityIdentities.add(entity.identity())) {
                throw new IllegalArgumentException("Graph projection input contains duplicate entities");
            }
            if (!projectionVersion.equals(entity.projectionVersion())) {
                throw new IllegalArgumentException("Graph entity projection version differs from input");
            }
            if (!entity.provenance().eligible()) {
                throw new IllegalArgumentException("Graph projection input contains ineligible authority");
            }
        }
        Set<GraphRelationIdentity> relationIdentities = new HashSet<>();
        for (GraphRelation relation : relations) {
            if (!relationIdentities.add(relation.identity())) {
                throw new IllegalArgumentException("Graph projection input contains duplicate relations");
            }
            if (!projectionVersion.equals(relation.projectionVersion())) {
                throw new IllegalArgumentException("Graph relation projection version differs from input");
            }
            if (!relation.provenance().eligible()) {
                throw new IllegalArgumentException("Graph projection input contains ineligible relation authority");
            }
            if (!entityIdentities.contains(relation.source()) || !entityIdentities.contains(relation.target())) {
                throw new IllegalArgumentException("Graph projection input contains an orphan relation");
            }
        }
        String expected = fingerprint(workspace, projectionVersion, entities, relations);
        if (sourceFingerprint != null && !expected.equals(sourceFingerprint)) {
            throw new IllegalArgumentException("Graph projection source fingerprint is not deterministic");
        }
        sourceFingerprint = expected;
    }

    public GraphProjectionInput(GraphWorkspaceScope workspace, GraphProjectionVersion projectionVersion,
                                List<GraphEntity> entities, List<GraphRelation> relations) {
        this(workspace, projectionVersion, entities, relations, null);
    }

    private static List<GraphEntity> sortedEntities(GraphWorkspaceScope workspace,
                                                     List<GraphEntity> input) {
        List<GraphEntity> result = new ArrayList<>(input);
        if (result.stream().anyMatch(entity -> entity == null)) {
            throw new IllegalArgumentException("Graph projection input contains a null entity");
        }
        result.sort(java.util.Comparator.comparing(entity -> entity.identity().stableId()));
        result.forEach(entity -> requireWorkspace(workspace, entity.identity().workspace(), "entity"));
        return List.copyOf(result);
    }

    private static List<GraphRelation> sortedRelations(GraphWorkspaceScope workspace,
                                                        List<GraphRelation> input) {
        List<GraphRelation> result = new ArrayList<>(input);
        if (result.stream().anyMatch(relation -> relation == null)) {
            throw new IllegalArgumentException("Graph projection input contains a null relation");
        }
        result.sort(java.util.Comparator.comparing(relation -> relation.identity().stableId()));
        result.forEach(relation -> requireWorkspace(workspace, relation.identity().workspace(), "relation"));
        return List.copyOf(result);
    }

    private static void requireWorkspace(GraphWorkspaceScope expected, GraphWorkspaceScope actual,
                                        String valueType) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("Graph projection " + valueType + " crosses workspace boundary");
        }
    }

    static String fingerprint(GraphWorkspaceScope workspace, GraphProjectionVersion version,
                              List<GraphEntity> entities, List<GraphRelation> relations) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            GraphIdentityCodec.update(digest, "graph-projection-input-v1");
            GraphIdentityCodec.update(digest, Long.toString(workspace.id()));
            GraphIdentityCodec.update(digest, version.value());
            for (GraphEntity entity : entities) {
                GraphIdentityCodec.update(digest, "entity");
                GraphIdentityCodec.update(digest, entity.identity().stableId());
                GraphIdentityCodec.update(digest, entity.displayName());
                GraphIdentityCodec.update(digest, entity.provenance().authority().kind().wireValue());
                GraphIdentityCodec.update(digest, entity.provenance().authority().stableId());
                GraphIdentityCodec.update(digest, freshnessEncoding(entity.provenance().freshness()));
                GraphIdentityCodec.update(digest, entity.provenance().eligibility().name());
                appendMetadata(digest, entity.metadata());
                appendMetadata(digest, entity.provenance().metadata());
            }
            for (GraphRelation relation : relations) {
                GraphIdentityCodec.update(digest, "relation");
                GraphIdentityCodec.update(digest, relation.identity().stableId());
                GraphIdentityCodec.update(digest, relation.source().stableId());
                GraphIdentityCodec.update(digest, relation.type().wireValue());
                GraphIdentityCodec.update(digest, relation.target().stableId());
                GraphIdentityCodec.update(digest, relation.provenance().authority().kind().wireValue());
                GraphIdentityCodec.update(digest, relation.provenance().authority().stableId());
                GraphIdentityCodec.update(digest, freshnessEncoding(relation.provenance().freshness()));
                GraphIdentityCodec.update(digest, relation.provenance().eligibility().name());
                appendMetadata(digest, relation.metadata());
                appendMetadata(digest, relation.provenance().metadata());
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String freshnessEncoding(GraphFreshness freshness) {
        return (freshness.revision() == null ? "" : freshness.revision()) + ":"
                + (freshness.contentHash() == null ? "" : freshness.contentHash());
    }

    private static void appendMetadata(MessageDigest digest, GraphMetadata metadata) {
        for (var entry : metadata.entries().entrySet()) {
            GraphIdentityCodec.update(digest, entry.getKey());
            GraphIdentityCodec.update(digest, entry.getValue());
        }
    }
}
