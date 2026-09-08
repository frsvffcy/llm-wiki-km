package org.km.llmwiki.rag;

import org.km.llmwiki.graph.GraphProjectionReadinessReader;
import org.km.llmwiki.graph.GraphSnapshotCurrentness;
import org.km.llmwiki.graph.GraphWorkspaceScope;
import org.km.llmwiki.search.SourceSearchAuthorityDocument;
import org.km.llmwiki.search.SourceSearchAuthorityRepository;
import org.km.llmwiki.wiki.PublishedWikiContentReader;
import org.km.llmwiki.wiki.PublishedWikiRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Ask-facing Graph-grounded fused retrieval orchestration for the {@code HYBRID_GRAPH} mode.
 *
 * <p>Orchestration is application-owned end to end: deterministic fusion is delegated to
 * {@link FusedEvidenceService} (which owns bounded traversal, canonical admission, and
 * identity-level ranking), and this boundary assembles the authoritative {@link EvidenceBundle}
 * consumed by Ask. Controllers, backend adapters, and providers never own fusion, authority, or
 * currentness semantics.
 *
 * <p>Because "current at fusion publication" does not imply "still current at Ask handoff",
 * every bundle passes a last-mile consumption-window guard before it leaves the retrieval
 * boundary:
 *
 * <ol>
 *   <li>the graph projection snapshot re-check runs first (mirroring the fusion terminal guard
 *       ordering) so projection drift is diagnosed even when per-item checks would drop the
 *       graph-derived items first; graph-only evidence loses its only validity chain and is
 *       dropped, while cross-modality evidence keeps its independent lexical/vector proof for
 *       the per-item check;</li>
 *   <li>every surviving item is revalidated against canonical Wiki/Source authority (workspace,
 *       identity, revision/content hash, eligibility, freshness) in a fresh consumption window;</li>
 *   <li>dropped items are never replaced: no lower-ranked candidate is silently promoted, and a
 *       degraded graph signal stays a typed diagnostic distinct from a normal empty result.</li>
 * </ol>
 *
 * <p>Graph failures at this boundary follow the shared {@link GraphRetrievalFailurePolicy}:
 * recognized operational faults degrade the graph modality only (typed diagnostics, baseline
 * continues), integrity/correctness violations fail closed with a typed
 * {@link RetrievalUnavailableException}, and unrecognized programming defects propagate
 * unchanged. Required lexical/vector authority infrastructure failures surface as typed
 * {@link RetrievalUnavailableException}s and are never reported as insufficient evidence. This
 * boundary never mutates canonical knowledge.
 */
@Service
public class FusedRetrievalOrchestrator {

    private final FusedEvidenceService fusedEvidenceService;
    private final CandidateAuthorityRevalidator authorityRevalidator;
    private final GraphProjectionReadinessReader graphReadiness;

    public FusedRetrievalOrchestrator(FusedEvidenceService fusedEvidenceService,
                                      PublishedWikiRepository publishedWikiRepository,
                                      PublishedWikiContentReader publishedWikiContentReader,
                                      SourceSearchAuthorityRepository sourceAuthorityRepository,
                                      GraphProjectionReadinessReader graphReadiness) {
        this.fusedEvidenceService = fusedEvidenceService;
        this.authorityRevalidator = new CandidateAuthorityRevalidator(publishedWikiRepository,
                publishedWikiContentReader, sourceAuthorityRepository);
        this.graphReadiness = graphReadiness;
    }

    /** Retrieval entry point for {@link RetrievalStrategy#FUSED}; budget stays request-bounded. */
    public EvidenceBundle retrieveFused(RetrievalRequest request) {
        RetrievalBudgetPolicy.ResolvedBudget limits = RetrievalBudgetPolicy.resolve(request);
        FusedEvidenceResult result = fusedEvidenceService.fuse(FusedEvidenceRequest.of(
                request.query(), limits.maxItems(), limits.maxCharacters(), true));
        return assembleBundle(request, result);
    }

    private EvidenceBundle assembleBundle(RetrievalRequest request, FusedEvidenceResult result) {
        List<EvidenceItem> items = new ArrayList<>(result.items());
        ModalityOutcome handoffOutcome = null;
        String graphDetail = null;
        int graphDroppedAtHandoff = 0;

        // Last-mile guard, step 1: the graph projection must still prove exactly the snapshot
        // the graph channel admitted under. The check runs before the per-item canonical checks
        // so projection drift is diagnosed even when those checks would drop the graph-derived
        // items first. Graph-only evidence loses its only validity chain; cross-modality
        // evidence keeps its independent lexical/vector proof. Like the fusion terminal guard,
        // the check applies whenever the graph channel actually participated, and the shared
        // failure policy keeps operational faults optional-modality-degradable while integrity
        // violations fail closed typed.
        if (result.graphSnapshot() != null
                && (result.diagnostics().graph() == ModalityOutcome.CONTRIBUTED
                || hasGraphDerivedEvidence(result))) {
            try {
                GraphSnapshotCurrentness.requireCurrent(
                        graphReadiness.readiness(new GraphWorkspaceScope(result.workspace().id())),
                        result.graphSnapshot(), true);
            } catch (RuntimeException failure) {
                GraphRetrievalFailurePolicy.NormalizedFailure normalized =
                        GraphRetrievalFailurePolicy.normalize(failure);
                switch (normalized.verdict()) {
                    case FAIL_CLOSED ->
                            throw GraphRetrievalFailurePolicy.typedFailure(failure);
                    case PROPAGATE -> throw failure;
                    case DEGRADE -> {
                        handoffOutcome = normalized.outcome();
                        graphDetail = "handoff graph currentness check failed: "
                                + normalized.detail();
                    }
                }
                Iterator<EvidenceItem> graphIterator = items.iterator();
                while (graphIterator.hasNext()) {
                    if (isGraphOnly(result, graphIterator.next())) {
                        graphIterator.remove();
                        graphDroppedAtHandoff++;
                    }
                }
            }
        }

        // Last-mile guard, step 2: canonical authority revalidation in a fresh consumption
        // window. Drift drops the affected item; nothing is silently promoted to fill the gap.
        Map<Long, Optional<SourceSearchAuthorityDocument>> handoffSourceCache = new HashMap<>();
        int handoffRejected = graphDroppedAtHandoff;
        Iterator<EvidenceItem> iterator = items.iterator();
        while (iterator.hasNext()) {
            if (!authorityRevalidator.publicationCurrent(iterator.next(),
                    result.workspace().id(), handoffSourceCache)) {
                iterator.remove();
                handoffRejected++;
            }
        }

        List<EvidenceItem> guarded = List.copyOf(items);
        int usedCharacters = guarded.stream()
                .mapToInt(item -> item.content().codePointCount(0, item.content().length()))
                .sum();
        EvidenceBudget budget = new EvidenceBudget(result.budget().maxItems(),
                result.budget().maxCharacters(), guarded.size(), usedCharacters,
                (usedCharacters + 3) / 4, result.budget().truncated());
        return new EvidenceBundle(request.query().strip(), request.mode(), result.workspace(),
                guarded, budget, result.searchedCandidateCount(),
                result.rejectedCandidateCount() + handoffRejected, guarded.isEmpty(),
                RetrievalDiagnostics.fused(handoffOutcome == null ? result.diagnostics()
                        : withHandoffOutcome(result.diagnostics(), handoffOutcome, graphDetail)));
    }

    private static boolean hasGraphDerivedEvidence(FusedEvidenceResult result) {
        return result.items().stream()
                .anyMatch(item -> result.itemModalities().getOrDefault(item.stableIdentity(),
                        Set.of()).contains(CandidateSignal.GRAPH));
    }

    private static boolean isGraphOnly(FusedEvidenceResult result, EvidenceItem item) {
        return result.itemModalities().getOrDefault(item.stableIdentity(), Set.of())
                .equals(Set.of(CandidateSignal.GRAPH));
    }

    /** Rebuilds the fusion diagnostics with the typed outcome observed at the Ask handoff. */
    private static FusedModalityDiagnostics withHandoffOutcome(
            FusedModalityDiagnostics diagnostics, ModalityOutcome handoffOutcome,
            String handoffDetail) {
        return new FusedModalityDiagnostics(diagnostics.lexical(), diagnostics.vector(),
                handoffOutcome, diagnostics.vectorDetail(), handoffDetail,
                diagnostics.terminalRejectedCount());
    }
}
