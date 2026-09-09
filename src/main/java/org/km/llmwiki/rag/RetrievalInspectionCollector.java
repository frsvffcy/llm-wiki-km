package org.km.llmwiki.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Thread-confined observation point handed through the production retrieval path. Ask flow
 * passes no collector at all; only the read-only Retrieval Inspector creates one. Collecting
 * never influences selection, ordering, or currentness semantics.
 */
final class RetrievalInspectionCollector {

    private final Map<CandidateSignal, List<RetrievalInspectionTrace.CandidateTrace>> channelCandidates =
            new LinkedHashMap<>();
    private final Map<CandidateSignal, List<RetrievalInspectionTrace.RejectedTrace>> channelRejections =
            new LinkedHashMap<>();
    private final List<RetrievalInspectionTrace.SelectionTrace> selection = new ArrayList<>();
    private final List<String> fusedOrder = new ArrayList<>();
    private Map<String, Set<CandidateSignal>> itemModalities = Map.of();

    void ensureChannel(CandidateSignal modality) {
        channelCandidates.computeIfAbsent(modality, ignored -> new ArrayList<>());
    }

    void channelCandidate(CandidateSignal modality, String identity) {
        channelCandidates.computeIfAbsent(modality, ignored -> new ArrayList<>())
                .add(new RetrievalInspectionTrace.CandidateTrace(identity,
                        channelCandidates.get(modality).size() + 1));
    }

    void channelRejected(CandidateSignal modality, String identity, String reasonCode) {
        channelRejections.computeIfAbsent(modality, ignored -> new ArrayList<>())
                .add(new RetrievalInspectionTrace.RejectedTrace(identity, reasonCode));
    }

    void fusedOrder(List<String> identities) {
        fusedOrder.clear();
        fusedOrder.addAll(identities);
    }

    void itemModalities(Map<String, Set<CandidateSignal>> modalities) {
        this.itemModalities = new LinkedHashMap<>(modalities);
    }

    void selected(String identity) {
        selection.add(RetrievalInspectionTrace.SelectionTrace.selected(identity));
    }

    void rejected(String identity, String reasonCode) {
        selection.add(RetrievalInspectionTrace.SelectionTrace.rejected(identity, reasonCode));
    }

    void duplicateFolded(String identity) {
        selection.add(RetrievalInspectionTrace.SelectionTrace.duplicateFolded(identity));
    }

    void budgetExcluded(String identity) {
        selection.add(RetrievalInspectionTrace.SelectionTrace.budgetExcluded(identity));
    }

    RetrievalInspectionTrace toTrace() {
        List<RetrievalInspectionTrace.ModalityTrace> modalities = new ArrayList<>();
        for (CandidateSignal modality : channelCandidates.keySet()) {
            modalities.add(new RetrievalInspectionTrace.ModalityTrace(modality,
                    List.copyOf(channelCandidates.get(modality)),
                    List.copyOf(channelRejections.getOrDefault(modality, List.of()))));
        }
        for (CandidateSignal modality : channelRejections.keySet()) {
            if (!channelCandidates.containsKey(modality)) {
                modalities.add(new RetrievalInspectionTrace.ModalityTrace(modality, List.of(),
                        List.copyOf(channelRejections.get(modality))));
            }
        }
        return new RetrievalInspectionTrace(modalities, fusedOrder, selection, itemModalities);
    }
}
