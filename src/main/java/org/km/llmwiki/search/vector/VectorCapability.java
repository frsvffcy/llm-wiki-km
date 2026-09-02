package org.km.llmwiki.search.vector;

/**
 * Provider- and extension-neutral boundary for asking whether vector retrieval can run.
 *
 * <p>This is deliberately a capability probe, not a vector search API. A caller must treat an
 * unavailable capability as an infrastructure/capability state and must not turn it into an
 * empty semantic result set.
 */
public interface VectorCapability {

    VectorCapabilityReport inspect(VectorCapabilityRequest request);
}
