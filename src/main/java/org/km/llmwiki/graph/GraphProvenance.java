package org.km.llmwiki.graph;

/**
 * Bounded provenance carried by every graph entity and relation.
 *
 * <p>Adapters must use this information to revalidate canonical authority before graph results
 * become retrieval evidence. Provenance never grants citation authority to the projection.
 */
public record GraphProvenance(GraphAuthorityReference authority, GraphFreshness freshness,
                              GraphAuthorityEligibility eligibility, GraphMetadata metadata) {

    public GraphProvenance {
        if (authority == null || freshness == null || eligibility == null || metadata == null) {
            throw new IllegalArgumentException("Graph provenance is incomplete");
        }
    }

    public static GraphProvenance of(GraphAuthorityReference authority, GraphFreshness freshness) {
        return new GraphProvenance(authority, freshness, GraphAuthorityEligibility.ELIGIBLE,
                GraphMetadata.empty());
    }

    public boolean eligible() {
        return eligibility == GraphAuthorityEligibility.ELIGIBLE;
    }
}
