package org.km.llmwiki.wiki;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Orchestrates #89 conversion, #90 planning, deterministic rendering, and persisted Draft review. */
@Service
public class WikiDraftPersistenceService {

    private final WorkspaceService workspaceService;
    private final KnowledgeProposalRepository proposalRepository;
    private final WikiDraftService draftService;
    private final WikiActionPlanningService planningService;
    private final WikiMarkdownSnapshotReader snapshotReader;
    private final WikiDraftMarkdownRenderer markdownRenderer;
    private final WikiDraftDiffRenderer diffRenderer;
    private final WikiDraftRepository repository;
    private final ObjectMapper objectMapper;

    public WikiDraftPersistenceService(WorkspaceService workspaceService,
                                       KnowledgeProposalRepository proposalRepository,
                                       WikiDraftService draftService,
                                       WikiActionPlanningService planningService,
                                       WikiMarkdownSnapshotReader snapshotReader,
                                       WikiDraftMarkdownRenderer markdownRenderer,
                                       WikiDraftDiffRenderer diffRenderer,
                                       WikiDraftRepository repository,
                                       ObjectMapper objectMapper) {
        this.workspaceService = workspaceService;
        this.proposalRepository = proposalRepository;
        this.draftService = draftService;
        this.planningService = planningService;
        this.snapshotReader = snapshotReader;
        this.markdownRenderer = markdownRenderer;
        this.diffRenderer = diffRenderer;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public WikiDraftResponse create(CreateWikiDraftRequest request) {
        if (request == null || request.proposalId() <= 0) {
            throw new IllegalArgumentException("proposalId must be positive");
        }
        WorkspaceResponse workspace = activeWorkspace();
        return response(createReadyDraft(workspace.id(), request.proposalId(), null));
    }

    @Transactional
    public WikiDraftResponse get(long draftId) {
        WorkspaceResponse workspace = activeWorkspace();
        StoredWikiDraft draft = require(workspace.id(), draftId);
        return response(refreshValidity(workspace.id(), draft));
    }

    @Transactional
    public WikiDraftPreviewResponse preview(long draftId) {
        WorkspaceResponse workspace = activeWorkspace();
        StoredWikiDraft draft = refreshValidity(workspace.id(), require(workspace.id(), draftId));
        return WikiDraftPreviewResponse.from(draft, deserialize(draft));
    }

    @Transactional
    public WikiDraftDiffResponse diff(long draftId) {
        WorkspaceResponse workspace = activeWorkspace();
        StoredWikiDraft draft = refreshValidity(workspace.id(), require(workspace.id(), draftId));
        return new WikiDraftDiffResponse(draft.id(), draft.status(), draft.publishReady(), draft.targetPath(),
                draft.baseContentHash(), draft.renderedContentHash(), draft.baseContent(), draft.renderedContent(),
                diffRenderer.render(draft.targetPath(), draft.baseContent(), draft.renderedContent()));
    }

    @Transactional
    public WikiDraftResponse invalidate(long draftId) {
        WorkspaceResponse workspace = activeWorkspace();
        StoredWikiDraft draft = require(workspace.id(), draftId);
        invalidate(workspace.id(), draft, WikiDraftInvalidationReason.MANUAL);
        return response(require(workspace.id(), draftId));
    }

    @Transactional
    public WikiDraftResponse regenerate(long draftId) {
        WorkspaceResponse workspace = activeWorkspace();
        StoredWikiDraft oldDraft = require(workspace.id(), draftId);
        if (oldDraft.status() == WikiDraftStatus.PUBLISHED) {
            throw new WikiDraftLifecycleException("PUBLISHED Wiki Draft cannot be regenerated");
        }
        StoredWikiDraft newDraft = createReadyDraft(workspace.id(), oldDraft.proposalId(), oldDraft.id());
        if (oldDraft.status() == WikiDraftStatus.DRAFT || oldDraft.status() == WikiDraftStatus.READY) {
            invalidate(workspace.id(), oldDraft, WikiDraftInvalidationReason.SUPERSEDED_BY_REGENERATION);
        }
        return response(newDraft);
    }

    private StoredWikiDraft createReadyDraft(long workspaceId, long proposalId, Long regeneratedFromDraftId) {
        WikiDraft structured = draftService.convertApproved(proposalId);
        WikiActionPlan plan = planningService.planApproved(proposalId);
        requireMatchingContracts(structured, plan);

        WikiTargetSnapshot target = plan.target();
        WikiTargetBaseline baseline = snapshotReader.capture(target);
        String structuredJson = serialize(structured);
        String rendered = markdownRenderer.render(structured);
        String inputHash = WikiContentHash.sha256(structuredJson + "\0" + target.kind().name() + "\0"
                + nullToEmpty(target.stableIdentifier()) + "\0" + target.logicalRelativePath() + "\0"
                + nullToEmpty(target.currentContentHash()));
        NewStoredWikiDraft newDraft = new NewStoredWikiDraft(workspaceId, proposalId, plan.action(),
                structured.pageType(), structured.title(), target.title(), target.pageType(),
                target.stableIdentifier(), target.logicalRelativePath(),
                target.currentContentHash(), baseline.contentHash(), WikiContentHash.sha256(rendered), inputHash,
                structuredJson, baseline.content(), rendered, regeneratedFromDraftId);
        long id = repository.insertDraft(newDraft);
        repository.transition(workspaceId, id, WikiDraftStatus.DRAFT, WikiDraftStatus.READY, null);
        return require(workspaceId, id);
    }

    private StoredWikiDraft refreshValidity(long workspaceId, StoredWikiDraft draft) {
        if (!draft.publishReady()) {
            return draft;
        }
        boolean proposalValid = proposalRepository.findDraftConversionSource(workspaceId, draft.proposalId())
                .filter(source -> source.status() == KnowledgeProposalStatus.APPROVED)
                .isPresent();
        if (!proposalValid) {
            invalidate(workspaceId, draft, WikiDraftInvalidationReason.SOURCE_PROPOSAL_INVALID);
            return require(workspaceId, draft.id());
        }
        WikiTargetSnapshot target = draft.action() == org.km.llmwiki.ai.LlmProposalAction.CREATE
                ? WikiTargetSnapshot.createNew(draft.targetTitle(), draft.targetPageType(), draft.targetPath())
                : new WikiTargetSnapshot(WikiTargetSnapshot.Kind.EXISTING, draft.targetKnowledgeId(),
                        draft.targetTitle(), draft.targetPageType(), draft.targetPath(), draft.expectedContentHash());
        try {
            WikiTargetBaseline current = snapshotReader.capture(target);
            if (!current.contentHash().equals(draft.baseContentHash())) {
                invalidate(workspaceId, draft, WikiDraftInvalidationReason.TARGET_CHANGED);
                return require(workspaceId, draft.id());
            }
        } catch (WikiDraftTargetException exception) {
            invalidate(workspaceId, draft, WikiDraftInvalidationReason.TARGET_CHANGED);
            return require(workspaceId, draft.id());
        }
        return draft;
    }

    private void invalidate(long workspaceId, StoredWikiDraft draft, WikiDraftInvalidationReason reason) {
        if (draft.status() != WikiDraftStatus.DRAFT && draft.status() != WikiDraftStatus.READY) {
            throw new WikiDraftLifecycleException("Only DRAFT or READY Wiki Draft can be invalidated");
        }
        repository.transition(workspaceId, draft.id(), draft.status(), WikiDraftStatus.INVALIDATED, reason);
    }

    private StoredWikiDraft require(long workspaceId, long draftId) {
        if (draftId <= 0) {
            throw new WikiDraftNotFoundException(draftId);
        }
        return repository.findById(workspaceId, draftId)
                .orElseThrow(() -> new WikiDraftNotFoundException(draftId));
    }

    private WorkspaceResponse activeWorkspace() {
        return workspaceService.findActiveWithoutValidation().orElseThrow(NoActiveWorkspaceException::new);
    }

    private static void requireMatchingContracts(WikiDraft draft, WikiActionPlan plan) {
        if (draft.proposalId() != plan.proposalId() || draft.action() != plan.action()
                || !draft.sourceChunkIds().equals(plan.sourceChunkIds()) || plan.target() == null) {
            throw new WikiDraftLifecycleException("#89 Wiki Draft and #90 action plan contracts do not agree");
        }
        if (draft.action() == org.km.llmwiki.ai.LlmProposalAction.CREATE
                && (!draft.target().logicalRelativePath().equals(plan.target().logicalRelativePath())
                || draft.pageType() != plan.target().pageType() || !draft.title().equals(plan.target().title()))) {
            throw new WikiDraftLifecycleException("#90 CREATE target does not match the #89 Draft contract");
        }
    }

    private String serialize(WikiDraft draft) {
        try {
            return objectMapper.writeValueAsString(draft);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Validated Wiki Draft could not be serialized", exception);
        }
    }

    private WikiDraft deserialize(StoredWikiDraft draft) {
        try {
            return objectMapper.readValue(draft.structuredDraftJson(), WikiDraft.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted Wiki Draft could not be deserialized", exception);
        }
    }

    private WikiDraftResponse response(StoredWikiDraft draft) {
        return WikiDraftResponse.from(draft, deserialize(draft));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
