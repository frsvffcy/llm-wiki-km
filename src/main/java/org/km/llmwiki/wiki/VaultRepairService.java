package org.km.llmwiki.wiki;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.km.llmwiki.ai.LlmProposalAction;
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Single backend authority for repair eligibility and repair plans (#384). A finding is
 * diagnostic evidence, never a mutation authorization: this service decides — from
 * backend authorities only — whether a deterministic repair plan exists, and rebuilds
 * the full plan at command time so client-supplied detail, paths, or text can never
 * become canonical authority.
 *
 * <p>The only v1 eligible class is {@code RESTORE_FROM_GOVERNED_LINEAGE}: a
 * {@code CANONICAL_CONTENT_INVALID} finding whose target page still resolves to a
 * renderable governed origin proposal. The repair re-publishes the page from its own
 * origin data through the unchanged Proposal → Draft → Publish flow, so the human
 * reviews the complete current-file versus regenerated diff and no unreviewed byte
 * can be laundered into canonical state. Every other finding class resolves to a
 * typed {@link RepairRefusalReason} and stays triage-only.
 */
@Service
public class VaultRepairService {

    static final String REPAIR_KIND = "RESTORE_FROM_GOVERNED_LINEAGE";
    static final String REPAIR_KIND_VERSION = "v1";
    static final String REPAIR_PROVIDER = "vault-lint";
    static final String REPAIR_MODEL = "deterministic-restore-v1";
    static final String REPAIR_PROMPT_IDENTIFIER = "vault-lint-restore";
    static final String REPAIR_PROMPT_VERSION = "v1";

    private final WorkspaceService workspaceService;
    private final PublishedWikiRepository publishedWikiRepository;
    private final VaultLintService lintService;
    private final KnowledgeProposalRepository proposalRepository;
    private final RepairProposalIngressRepository repairRepository;
    private final WikiDraftConverter draftConverter;
    private final WikiMarkdownSnapshotReader snapshotReader;
    private final ObjectMapper objectMapper;

    public VaultRepairService(WorkspaceService workspaceService,
                              PublishedWikiRepository publishedWikiRepository,
                              VaultLintService lintService,
                              KnowledgeProposalRepository proposalRepository,
                              RepairProposalIngressRepository repairRepository,
                              WikiDraftConverter draftConverter,
                              WikiMarkdownSnapshotReader snapshotReader,
                              ObjectMapper objectMapper) {
        this.workspaceService = workspaceService;
        this.publishedWikiRepository = publishedWikiRepository;
        this.lintService = lintService;
        this.proposalRepository = proposalRepository;
        this.repairRepository = repairRepository;
        this.draftConverter = draftConverter;
        this.snapshotReader = snapshotReader;
        this.objectMapper = objectMapper;
    }

    /**
     * List-time assessment for a finding of the current lint run. The finding is current
     * by construction (it comes from the just-produced report); only class-level
     * eligibility and lineage resolvability are evaluated — no mutation, no re-lint.
     */
    public RepairAssessment assessFinding(long workspaceId, VaultLintFinding finding) {
        if (finding.code() != VaultLintFinding.Code.CANONICAL_CONTENT_INVALID) {
            return new RepairAssessment.Ineligible(staticRefusal(finding.code()));
        }
        Optional<StoredPublishedWiki> page =
                publishedWikiRepository.findPublishedByKnowledgeId(workspaceId, finding.knowledgeId());
        if (page.isEmpty()) {
            return new RepairAssessment.Ineligible(RepairRefusalReason.NO_RESOLVABLE_LINEAGE);
        }
        // Repair-baseline readability: the drifted (or missing) vault bytes are what the
        // human reviews against, so a target that cannot be read as a regular file can
        // never produce a draft — it is ineligible here instead of becoming a dead
        // proposal whose draft creation would fail closed later.
        try {
            snapshotReader.hashActiveVaultFile(page.get().markdownPath());
        } catch (WikiDraftTargetException unreadable) {
            return new RepairAssessment.Ineligible(RepairRefusalReason.TARGET_NOT_READABLE);
        }
        return buildPlan(workspaceId, page.get(), finding)
                .<RepairAssessment>map(RepairAssessment.Eligible::new)
                .orElseGet(() -> new RepairAssessment.Ineligible(RepairRefusalReason.NO_RESOLVABLE_LINEAGE));
    }

    /**
     * Command-time assessment: reloads the authoritative page row, re-runs lint, and
     * requires a current CANONICAL_CONTENT_INVALID finding for the same identity before
     * rebuilding the plan (the plan and its dedup hash are derived from the current
     * finding detail, so a changed failure class simply produces a new, freshly
     * reviewed plan rather than replaying an old one). Anything stale, ineligible, or
     * lineage-broken fails closed with a typed exception; the client-supplied
     * knowledgeId is only a lookup key into workspace-scoped backend state.
     */
    public RepairPlan assessCommand(String knowledgeId) {
        WorkspaceResponse workspace = workspaceService.findActiveWithoutValidation()
                .orElseThrow(NoActiveWorkspaceException::new);
        StoredPublishedWiki page = publishedWikiRepository
                .findPublishedByKnowledgeId(workspace.id(), knowledgeId)
                .orElseThrow(() -> new WikiPageNotFoundException(knowledgeId));
        List<VaultLintFinding> current = lintService.lintActiveWorkspace().findings().stream()
                .filter(finding -> finding.knowledgeId().equals(page.knowledgeId()))
                .toList();
        if (current.isEmpty()) {
            throw new RepairFindingStaleException(
                    "no finding is present for page: " + page.knowledgeId());
        }
        VaultLintFinding invalid = current.stream()
                .filter(finding -> finding.code() == VaultLintFinding.Code.CANONICAL_CONTENT_INVALID)
                .findFirst()
                .orElseThrow(() -> new RepairNotEligibleException(
                        staticRefusal(current.getFirst().code())));
        RepairAssessment assessment = assessFinding(workspace.id(), invalid);
        if (assessment instanceof RepairAssessment.Ineligible ineligible) {
            throw new RepairNotEligibleException(ineligible.reason());
        }
        // Every plan input above was re-derived from current backend rows inside this
        // command (page row, fresh lint finding, origin proposal, origin data); later
        // drift is still caught by the flow's own draft/publish optimistic checks.
        return ((RepairAssessment.Eligible) assessment).plan();
    }

    private Optional<RepairPlan> buildPlan(long workspaceId, StoredPublishedWiki page,
                                           VaultLintFinding finding) {
        Long originProposalId = repairRepository.findPageProposalId(workspaceId, page.id());
        if (originProposalId == null) {
            return Optional.empty();
        }
        Optional<WikiDraftConversionSource> origin =
                proposalRepository.findDraftConversionSource(workspaceId, originProposalId);
        if (origin.isEmpty() || (origin.get().action() != LlmProposalAction.CREATE
                && origin.get().action() != LlmProposalAction.MERGE)) {
            return Optional.empty();
        }
        WikiDraftConversionSource source = origin.get();
        if (source.proposalEvidence().isEmpty()) {
            return Optional.empty();
        }
        // Dry-run proof through the real converter (single authority): the copied
        // origin data must render today, otherwise the repair could never produce a
        // draft and must not become a proposal. The synthetic APPROVED status and MERGE
        // action mirror exactly what the repair proposal will carry — nothing here is
        // persisted and the origin row itself is never modified.
        try {
            draftConverter.convert(new WikiDraftConversionSource(
                    source.proposalId(), source.workspaceId(), source.documentId(),
                    LlmProposalAction.MERGE, KnowledgeProposalStatus.APPROVED,
                    "WIKI:" + page.knowledgeId(), source.normalizedDataJson(),
                    source.candidateType(), source.candidateTitle(), source.candidateSummary(),
                    source.candidateEvidenceSourceChunkIds(), source.proposalEvidence(),
                    source.evidenceDocumentIds()));
        } catch (WikiDraftValidationException invalid) {
            return Optional.empty();
        }
        List<Long> evidenceChunkIds = source.proposalEvidence().stream()
                .map(KnowledgeProposalEvidence::sourceChunkId).sorted().toList();
        String payload = repairPayload(finding, page, originProposalId);
        String dedupHash = WikiContentHash.sha256("vault-repair:v1:" + workspaceId + ":"
                + page.knowledgeId() + ":" + finding.code().name() + ":"
                + page.contentHash() + ":" + finding.detail());
        return Optional.of(new RepairPlan(REPAIR_KIND, REPAIR_KIND_VERSION, page.knowledgeId(),
                finding.code().name(), page.contentHash(), originProposalId,
                source.normalizedDataJson(), "WIKI:" + page.knowledgeId(), evidenceChunkIds,
                payload, dedupHash));
    }

    private String repairPayload(VaultLintFinding finding, StoredPublishedWiki page,
                                 long originProposalId) {
        try {
            return objectMapper.writeValueAsString(new RepairPayload(REPAIR_KIND,
                    REPAIR_KIND_VERSION, finding.code().name(), page.knowledgeId(),
                    page.contentHash(), originProposalId, finding.detail()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("repair payload could not be serialized", exception);
        }
    }

    private record RepairPayload(String repairKind, String repairKindVersion, String findingCode,
                                 String knowledgeId, String baseContentHash, long originProposalId,
                                 String detail) {
    }

    private static RepairRefusalReason staticRefusal(VaultLintFinding.Code code) {
        return switch (code) {
            case BROKEN_INTERNAL_LINK -> RepairRefusalReason.AMBIGUOUS_TARGET;
            case ORPHAN_PAGE -> RepairRefusalReason.SEMANTIC_JUDGMENT_REQUIRED;
            case CANONICAL_CONTENT_UNREADABLE -> RepairRefusalReason.TARGET_NOT_READABLE;
            case DUPLICATE_IDENTITY -> RepairRefusalReason.AMBIGUOUS_IDENTITY;
            case DANGLING_PROVENANCE -> RepairRefusalReason.NO_RESOLVABLE_LINEAGE;
            case CANONICAL_CONTENT_INVALID -> throw new IllegalStateException(
                    "invalid content findings require lineage assessment, not a static refusal");
        };
    }
}
