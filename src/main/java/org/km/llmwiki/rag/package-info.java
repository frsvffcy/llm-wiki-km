/**
 * Provider-neutral lexical, semantic, and hybrid Retrieval plus bounded authoritative Evidence
 * Assembly for the current stateless Ask/Answer MVP.
 *
 * <p>Retrieval and Evidence Assembly are maintained product surfaces that supply authoritative,
 * bounded context to the Sprint 6 provider-neutral Answer contract and Ask orchestration. This
 * package does not perform persistent knowledge mutation; vector candidate search is provider
 * neutral and requires a workspace- and corpus-scoped embedding projection in {@code READY}
 * state before serving semantic candidates. Provider/model/dimension and projection contract
 * mismatches are treated as readiness failures; every candidate remains authority-revalidated
 * before use. {@code HYBRID_FTS} remains the Wiki + Source FTS-only strategy, while
 * {@code HYBRID_VECTOR} may expose only a typed, degraded lexical fallback when its vector signal
 * is unavailable. Graph expansion and GraphRAG remain later-phase capabilities.
 */
package org.km.llmwiki.rag;
