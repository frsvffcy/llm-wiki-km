package org.km.llmwiki.graph.arcadedb;

import org.km.llmwiki.graph.GraphAuthorityEligibility;
import org.km.llmwiki.graph.GraphAuthorityKind;
import org.km.llmwiki.graph.GraphAuthorityReference;
import org.km.llmwiki.graph.GraphEntity;
import org.km.llmwiki.graph.GraphEntityIdentity;
import org.km.llmwiki.graph.GraphEntityType;
import org.km.llmwiki.graph.GraphFreshness;
import org.km.llmwiki.graph.GraphMetadata;
import org.km.llmwiki.graph.GraphProjectionInput;
import org.km.llmwiki.graph.GraphProjectionVersion;
import org.km.llmwiki.graph.GraphProvenance;
import org.km.llmwiki.graph.GraphRelation;
import org.km.llmwiki.graph.GraphRelationType;
import org.km.llmwiki.graph.GraphWorkspaceScope;

import java.util.List;
import java.util.Map;

/** Deterministic, application-owned fixtures for the embedded adapter spike. */
final class ArcadeDbGraphProjectionFixtures {

    static final GraphProjectionVersion VERSION = GraphProjectionVersion.initial();
    static final GraphWorkspaceScope WORKSPACE = new GraphWorkspaceScope(41);
    static final GraphWorkspaceScope OTHER_WORKSPACE = new GraphWorkspaceScope(42);

    private ArcadeDbGraphProjectionFixtures() {
    }

    static GraphProjectionInput input(GraphWorkspaceScope workspace, GraphEntity... entities) {
        return new GraphProjectionInput(workspace, VERSION, List.of(entities), List.of());
    }

    static GraphProjectionInput input(GraphWorkspaceScope workspace, List<GraphEntity> entities,
                                      List<GraphRelation> relations) {
        return new GraphProjectionInput(workspace, VERSION, entities, relations);
    }

    static GraphEntity page(GraphWorkspaceScope workspace, String id, String displayName) {
        return page(workspace, id, displayName, VERSION,
                GraphMetadata.of(Map.of("origin", "canonical", "kind", "wiki")),
                GraphMetadata.of(Map.of("authority", "wiki")), GraphFreshness.revision(7));
    }

    static GraphEntity page(GraphWorkspaceScope workspace, String id, String displayName,
                            GraphProjectionVersion version, GraphMetadata metadata,
                            GraphMetadata provenanceMetadata, GraphFreshness freshness) {
        GraphAuthorityReference authority = new GraphAuthorityReference(workspace,
                GraphAuthorityKind.WIKI_PAGE, id);
        GraphEntityIdentity identity = GraphEntityIdentity.fromAuthority(authority,
                GraphEntityType.WIKI_PAGE);
        GraphProvenance provenance = new GraphProvenance(authority, freshness,
                GraphAuthorityEligibility.ELIGIBLE, provenanceMetadata);
        return new GraphEntity(identity, displayName, provenance, metadata, version);
    }

    static GraphRelation links(GraphEntity source, GraphEntity target) {
        return GraphRelation.of(source.identity(), GraphRelationType.LINKS_TO, target.identity(),
                source.provenance(),
                GraphMetadata.of(Map.of("relation", "wiki-link", "confidence", "1.0")));
    }
}
