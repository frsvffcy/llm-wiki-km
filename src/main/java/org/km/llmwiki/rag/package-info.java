/**
 * Provider-neutral lexical, semantic, and hybrid Retrieval plus bounded authoritative Evidence
 * Assembly for the current stateless Ask/Answer MVP.
 *
 * <p>Retrieval and Evidence Assembly are maintained product surfaces that supply authoritative,
 * bounded context to the Sprint 6 provider-neutral Answer contract and Ask orchestration. This
 * package does not perform persistent knowledge mutation; vector candidate search is provider
 * neutral and all candidates are authority-revalidated before use. Graph expansion and GraphRAG
 * remain later-phase capabilities.
 */
package org.km.llmwiki.rag;
