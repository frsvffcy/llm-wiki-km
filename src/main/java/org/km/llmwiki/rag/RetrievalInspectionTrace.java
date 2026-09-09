package org.km.llmwiki.rag;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Application-owned observation of one retrieval execution. The trace only carries safe,
 * bounded diagnostics (canonical identities, modality-local ordinals, disposition and stable
 * rejection reason codes); raw backend scores, vendor identifiers, exception details, tokens,
 * paths, and fingerprints never enter this boundary. Traces are produced by the production
 * retrieval path itself, so the final evidence order is by construction identical to the
 * Ask-facing handoff.
 */
public record RetrievalInspectionTrace(
        List<ModalityTrace> modalities,
        List<String> fusedOrder,
        List<SelectionTrace> selection,
        Map<String, Set<CandidateSignal>> itemModalities) {

    public RetrievalInspectionTrace {
        modalities = List.copyOf(modalities);
        fusedOrder = List.copyOf(fusedOrder);
        selection = List.copyOf(selection);
        itemModalities = Map.copyOf(itemModalities);
    }

    public record ModalityTrace(CandidateSignal modality, List<CandidateTrace> candidates,
                                List<RejectedTrace> rejected) {
        public ModalityTrace {
            candidates = List.copyOf(candidates);
            rejected = List.copyOf(rejected);
        }
    }

    /** Modality-local candidate in fusion-input order; ordinal is 1-based and application-owned. */
    public record CandidateTrace(String identity, int ordinal) {
        public CandidateTrace {
            if (ordinal < 1) {
                throw new IllegalArgumentException("candidate ordinal must be positive");
            }
        }
    }

    /** A candidate rejected by authority/currentness admission with a stable reason code. */
    public record RejectedTrace(String identity, String reasonCode) {
        public RejectedTrace {
            if (reasonCode == null || reasonCode.isBlank()) {
                throw new IllegalArgumentException("reason code must not be blank");
            }
        }
    }

    /** Disposition of one fused (or merged) candidate; reason is present only for rejections. */
    public record SelectionTrace(String identity, Disposition disposition, String reasonCode) {
        public SelectionTrace {
            if (disposition == null) {
                throw new IllegalArgumentException("disposition is required");
            }
            if (disposition == Disposition.REJECTED && (reasonCode == null || reasonCode.isBlank())) {
                throw new IllegalArgumentException("rejections require a reason code");
            }
            if (disposition != Disposition.REJECTED && reasonCode != null) {
                throw new IllegalArgumentException("only rejections carry a reason code");
            }
        }

        public static SelectionTrace selected(String identity) {
            return new SelectionTrace(identity, Disposition.SELECTED, null);
        }

        public static SelectionTrace rejected(String identity, String reasonCode) {
            return new SelectionTrace(identity, Disposition.REJECTED, reasonCode);
        }

        public static SelectionTrace duplicateFolded(String identity) {
            return new SelectionTrace(identity, Disposition.DUPLICATE_FOLDED, null);
        }

        public static SelectionTrace budgetExcluded(String identity) {
            return new SelectionTrace(identity, Disposition.BUDGET_EXCLUDED, null);
        }
    }

    public enum Disposition {
        SELECTED,
        REJECTED,
        DUPLICATE_FOLDED,
        BUDGET_EXCLUDED
    }
}
