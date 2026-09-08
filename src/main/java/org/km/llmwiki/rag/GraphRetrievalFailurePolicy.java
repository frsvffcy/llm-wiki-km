package org.km.llmwiki.rag;

import org.jooq.exception.DataAccessException;
import org.km.llmwiki.graph.GraphProjectionException;
import org.km.llmwiki.graph.GraphProjectionFailureType;

/**
 * Central, provider-neutral normalization of graph failures observed at the optional-graph
 * retrieval boundaries: initial channel readiness, traversal/admission, the fusion terminal
 * publication guard, and the Ask handoff guard. Every boundary applies the same rules, so one
 * graph infrastructure fault cannot drag down a valid lexical/vector baseline at one boundary
 * and crash the request at another.
 *
 * <ul>
 *   <li>{@link Verdict#DEGRADE}: recognized operational failure (backend/readiness/control-plane
 *       infrastructure, stale or not-ready projection). The graph modality degrades into a typed
 *       outcome with a bounded, vendor-free detail; graph-only evidence loses its only validity
 *       chain and the lexical/vector baseline continues.</li>
 *   <li>{@link Verdict#FAIL_CLOSED}: integrity or correctness violation (corrupt projection
 *       proof, cross-workspace, invalid provenance/input, invalid traversal bounds, local
 *       validation defect). An optional modality must never disguise these as degradation; the
 *       whole request fails closed with a typed {@link RetrievalUnavailableException} whose
 *       cause stays server-side.</li>
 *   <li>{@link Verdict#PROPAGATE}: unrecognized runtime fault. This is a programming defect, not
 *       an operational failure; it is never blanket-swallowed into degradation and propagates
 *       unchanged.</li>
 * </ul>
 *
 * <p>Backend adapters already map known vendor infrastructure faults into typed
 * {@link GraphProjectionException}s; this policy reuses that taxonomy and its
 * {@link GraphProjectionFailureType#retryable()} semantics instead of re-classifying raw
 * exceptions per call site.
 */
final class GraphRetrievalFailurePolicy {

    private GraphRetrievalFailurePolicy() {
    }

    static NormalizedFailure normalize(RuntimeException failure) {
        if (failure instanceof GraphProjectionException projectionFailure) {
            return normalize(projectionFailure.failureType());
        }
        if (failure instanceof DataAccessException) {
            return NormalizedFailure.degrade(ModalityOutcome.UNAVAILABLE,
                    "graph infrastructure failure");
        }
        return NormalizedFailure.propagate();
    }

    private static NormalizedFailure normalize(GraphProjectionFailureType type) {
        // Exhaustive on purpose: a future failure type must be classified explicitly here
        // instead of silently falling into the operational-degradation bucket.
        return switch (type) {
            case CAPABILITY_DISABLED -> NormalizedFailure.degrade(ModalityOutcome.DISABLED,
                    type.publicCode());
            case PROJECTION_NOT_READY -> NormalizedFailure.degrade(ModalityOutcome.NOT_READY,
                    type.publicCode());
            case PROJECTION_STALE -> NormalizedFailure.degrade(ModalityOutcome.DEGRADED,
                    type.publicCode());
            // Operational family: backend locked/unavailable, filesystem, transaction,
            // incompatible projection, invalid configuration — the baseline continues.
            case CAPABILITY_UNAVAILABLE, PROJECTION_INCOMPATIBLE, BACKEND_LOCKED,
                    FILESYSTEM_UNAVAILABLE, TRANSACTION_FAILURE, BACKEND_FAILURE,
                    CONFIGURATION_INVALID ->
                    NormalizedFailure.degrade(ModalityOutcome.UNAVAILABLE, type.publicCode());
            // Integrity and correctness violations are never reported as a degraded optional
            // modality: the request fails closed with a typed retrieval failure instead.
            case PROJECTION_CORRUPT, INVALID_PROJECTION_INPUT, INVALID_PROVENANCE,
                    CROSS_WORKSPACE, INVALID_TRAVERSAL_BOUNDS, LOCAL_VALIDATION ->
                    NormalizedFailure.failClosed();
        };
    }

    /** Typed fail-closed vehicle; the cause chain stays server-side and is never exposed. */
    static RetrievalUnavailableException typedFailure(RuntimeException failure) {
        return new RetrievalUnavailableException(
                RetrievalUnavailableException.Dependency.GRAPH, failure);
    }

    record NormalizedFailure(Verdict verdict, ModalityOutcome outcome, String detail) {

        static NormalizedFailure degrade(ModalityOutcome outcome, String detail) {
            return new NormalizedFailure(Verdict.DEGRADE, outcome, detail);
        }

        static NormalizedFailure failClosed() {
            return new NormalizedFailure(Verdict.FAIL_CLOSED, null, null);
        }

        static NormalizedFailure propagate() {
            return new NormalizedFailure(Verdict.PROPAGATE, null, null);
        }
    }

    enum Verdict {
        DEGRADE,
        FAIL_CLOSED,
        PROPAGATE
    }
}
