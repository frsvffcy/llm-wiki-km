/**
 * Provider-neutral Knowledge Graph and Graph Retrieval capability boundary.
 *
 * <p>Phase 3A owns the provider-neutral graph domain/projection contract: stable graph entity and
 * relation identity, provenance, workspace scope, rebuildable projection input/snapshot, bounded
 * metadata, and typed projection failures. Graph backends are replaceable adapters behind that
 * contract; ArcadeDB is the currently preferred embedded multi-model adapter candidate, while
 * Neo4j, RyuGraph, BigQuery Graph, and Spanner Graph remain optional or future candidates. No
 * backend, query language, vendor DTO, or credential is an application/domain contract.
 *
 * <p>Graph projections are derived state rebuilt from canonical archive/vault content and
 * authoritative metadata. Graph candidates must be bounded and pass workspace, authority,
 * provenance, freshness, and eligibility revalidation before they can contribute to an
 * {@code EvidenceBundle}. Future graph retrieval must preserve the existing citation and grounded
 * Answer boundary, and backend unavailability must not invalidate the lexical/vector baseline.
 */
package org.km.llmwiki.graph;
