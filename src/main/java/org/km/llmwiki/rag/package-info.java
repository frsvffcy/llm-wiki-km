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
 * is unavailable. Bounded Graph candidates may enter this package only through
 * {@code GraphEvidenceAdmissionService}: the returned traversal snapshot must still be current at
 * admission time, and every candidate must pass workspace-scoped authority, provenance, freshness,
 * and eligibility revalidation against canonical Wiki/Source authority before it becomes a
 * canonical {@code EvidenceItem}. Graph topology rows, paths, and vendor identifiers never become
 * citation authority; graph contribution is bounded by a hard admission budget; graph backend
 * availability must not invalidate lexical/vector retrieval. {@code FusedEvidenceService} fuses
 * the lexical, vector, and graph channels deterministically by canonical evidence identity with
 * reciprocal rank fusion, hard global/per-modality budgets, and a terminal publication guard that
 * revalidates every selected item before the result may leave this boundary. The additive
 * {@code HYBRID_GRAPH} Ask mode is served by {@code FusedRetrievalOrchestrator}, which assembles
 * the authoritative {@code EvidenceBundle} and applies a last-mile Ask handoff guard that
 * re-checks the graph projection snapshot and every item's canonical authority in a fresh
 * consumption window; dropped evidence is never silently backfilled, graph degradation stays a
 * typed diagnostic, and infrastructure failures stay typed failures. This package never mutates
 * canonical knowledge.
 */
package org.km.llmwiki.rag;
