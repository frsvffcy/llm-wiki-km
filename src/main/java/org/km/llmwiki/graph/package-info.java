/**
 * Provider-neutral Knowledge Graph and Graph Retrieval capability boundary.
 *
 * <p>This package is responsible for the conceptual graph contract: stable graph entity and
 * relation identity, provenance, workspace scope, rebuildable projection, and bounded traversal.
 * Graph backends are replaceable adapters behind that contract; Neo4j is only a local-first
 * interactive reference-adapter candidate, while BigQuery Graph and Spanner Graph are optional or
 * future deployment candidates. No backend, query language, vendor DTO, or credential is an
 * application/domain contract.
 *
 * <p>Graph projections are derived state rebuilt from canonical archive/vault content and
 * authoritative metadata. Graph candidates must be bounded and pass workspace, authority,
 * provenance, freshness, and eligibility revalidation before they can contribute to an
 * {@code EvidenceBundle}. Graph retrieval must preserve the existing citation and grounded
 * Answer boundary, and backend unavailability must not invalidate the lexical/vector baseline.
 */
package org.km.llmwiki.graph;
