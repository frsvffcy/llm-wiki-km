package org.km.llmwiki.wiki;

import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Governed repair ingress (#384): an explicit human repair intent for one canonical
 * page identity becomes a Proposal at REVIEW — never a vault write, never an
 * auto-publish. Every plan input is rebuilt from backend state at command time by
 * {@link VaultRepairService}; the client-supplied knowledgeId is only a lookup key.
 * Repeated submissions for the same finding state share one proposal through the
 * dedup contract (lost races return the winner).
 */
@Service
public class RepairProposalIngressService {

    private static final int MAX_KNOWLEDGE_ID = 200;

    private final WorkspaceService workspaceService;
    private final KnowledgeProposalRepository proposalRepository;
    private final RepairProposalIngressRepository ingressRepository;
    private final VaultRepairService repairService;

    public RepairProposalIngressService(WorkspaceService workspaceService,
                                        KnowledgeProposalRepository proposalRepository,
                                        RepairProposalIngressRepository ingressRepository,
                                        VaultRepairService repairService) {
        this.workspaceService = workspaceService;
        this.proposalRepository = proposalRepository;
        this.ingressRepository = ingressRepository;
        this.repairService = repairService;
    }

    @Transactional
    public RepairProposalIngressResponse createIngress(CreateRepairProposalRequest request) {
        String knowledgeId = validate(request);
        WorkspaceResponse workspace = workspaceService.findActiveWithoutValidation()
                .orElseThrow(NoActiveWorkspaceException::new);
        RepairPlan plan = repairService.assessCommand(knowledgeId);
        var existing = ingressRepository.findByRepairDedupHash(workspace.id(), plan.dedupHash());
        if (existing.isPresent()) {
            return new RepairProposalIngressResponse(
                    KnowledgeProposalReviewResponse.from(existing.get()), true);
        }
        String contractVersion = ingressRepository
                .findContractVersion(workspace.id(), plan.originProposalId())
                .orElseThrow(() -> new RepairFindingStaleException(
                        "repair lineage changed for page: " + knowledgeId));
        long proposalId;
        try {
            proposalId = ingressRepository.insertRepairProposal(workspace.id(), plan, contractVersion);
        } catch (DuplicateRepairProposalException lostRace) {
            var winner = ingressRepository.findByRepairDedupHash(workspace.id(), plan.dedupHash())
                    .orElseThrow(() -> new IllegalStateException(
                            "dedup race winner must exist", lostRace));
            return new RepairProposalIngressResponse(
                    KnowledgeProposalReviewResponse.from(winner), true);
        }
        return new RepairProposalIngressResponse(
                KnowledgeProposalReviewResponse.from(proposalRepository
                        .findReviewableById(workspace.id(), proposalId).orElseThrow()),
                false);
    }

    private static String validate(CreateRepairProposalRequest request) {
        if (request == null || request.knowledgeId() == null
                || request.knowledgeId().isBlank()) {
            throw new IllegalArgumentException("knowledgeId is required");
        }
        String knowledgeId = request.knowledgeId().strip();
        if (knowledgeId.length() > MAX_KNOWLEDGE_ID || knowledgeId.lines().count() != 1) {
            throw new IllegalArgumentException("knowledgeId is invalid");
        }
        return knowledgeId;
    }
}
