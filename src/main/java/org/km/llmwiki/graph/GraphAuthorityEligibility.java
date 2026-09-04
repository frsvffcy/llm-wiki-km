package org.km.llmwiki.graph;

/**
 * Canonical-authority lifecycle carried into a projection input.
 *
 * <p>Only {@link #ELIGIBLE} authority may enter a rebuild input. Other states are retained as
 * explicit revalidation outcomes so an adapter cannot silently treat deleted or superseded data
 * as a valid zero-result or current graph candidate.
 */
public enum GraphAuthorityEligibility {
    ELIGIBLE,
    SUPERSEDED,
    DELETED,
    INELIGIBLE
}
