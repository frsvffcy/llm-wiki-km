package org.km.llmwiki.wiki;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.km.llmwiki.ai.LlmProposalAction;
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** Human-explicit, CREATE-only Vault publish orchestration. No other workflow invokes this service. */
@Service
public class WikiCreatePublishService {

    private static final int INITIAL_REVISION = 1;

    private final WorkspaceService workspaceService;
    private final KnowledgeProposalRepository proposalRepository;
    private final WikiDraftRepository draftRepository;
    private final WikiTargetCatalog targetCatalog;
    private final ActiveWorkspaceWikiPathResolver pathResolver;
    private final WikiPublishedMarkdownRenderer renderer;
    private final WikiAtomicFilePublisher filePublisher;
    private final WikiPublicationRepository publicationRepository;
    private final WikiCreatePublicationFinalizer finalizer;
    private final ObjectMapper objectMapper;

    public WikiCreatePublishService(WorkspaceService workspaceService,
                                    KnowledgeProposalRepository proposalRepository,
                                    WikiDraftRepository draftRepository,
                                    WikiTargetCatalog targetCatalog,
                                    ActiveWorkspaceWikiPathResolver pathResolver,
                                    WikiPublishedMarkdownRenderer renderer,
                                    WikiAtomicFilePublisher filePublisher,
                                    WikiPublicationRepository publicationRepository,
                                    WikiCreatePublicationFinalizer finalizer,
                                    ObjectMapper objectMapper) {
        this.workspaceService = workspaceService;
        this.proposalRepository = proposalRepository;
        this.draftRepository = draftRepository;
        this.targetCatalog = targetCatalog;
        this.pathResolver = pathResolver;
        this.renderer = renderer;
        this.filePublisher = filePublisher;
        this.publicationRepository = publicationRepository;
        this.finalizer = finalizer;
        this.objectMapper = objectMapper;
    }

    public WikiCreatePublishResponse publish(long draftId) {
        WorkspaceResponse workspace = activeWorkspace();
        StoredWikiDraft draft = requireDraft(workspace.id(), draftId);
        if (draft.status() == WikiDraftStatus.PUBLISHED) {
            return repeatNoOp(workspace.id(), draft);
        }
        requirePublishable(workspace.id(), draft);
        if (publicationRepository.findByDraft(workspace.id(), draft.id()).isPresent()) {
            throw failure(WikiPublishException.Reason.OPERATION_CONFLICT,
                    "This Wiki Draft already has a prior publish operation and requires reconciliation");
        }

        WikiDraft structuredDraft = deserialize(draft);
        String canonicalPath = requireCanonicalCreateTarget(draft, structuredDraft);
        Path target = pathResolver.resolveAndValidateRealPath(canonicalPath);
        if (targetCatalog.existsAtCanonicalPath(workspace.id(), canonicalPath)) {
            throw failure(WikiPublishException.Reason.TARGET_CONFLICT,
                    "CREATE target already exists in Wiki metadata and will not be overwritten");
        }

        String publishedAt = now();
        String knowledgeId = "wiki:" + UUID.randomUUID();
        String content = renderer.render(structuredDraft, knowledgeId, draft.id(), INITIAL_REVISION, publishedAt);
        String contentHash = WikiContentHash.sha256(content);
        StoredWikiPublishOperation operation = publicationRepository.prepare(new NewWikiPublishOperation(
                workspace.id(), draft.id(), draft.proposalId(), knowledgeId, canonicalPath, contentHash,
                INITIAL_REVISION, publishedAt));

        StagedWikiFile staged = null;
        boolean finalFileCommitted = false;
        try {
            staged = filePublisher.stage(target, content, contentHash, structuredDraft);
            filePublisher.commit(staged);
            finalFileCommitted = true;
            publicationRepository.markFileCommitted(workspace.id(), operation.id());
            StoredWikiPublishOperation completed = finalizer.complete(draft,
                    requireOperation(workspace.id(), draft.id()), publishedAt);
            return WikiCreatePublishResponse.from(WikiPublishOutcome.CREATED, completed);
        } catch (RuntimeException exception) {
            filePublisher.discard(staged);
            compensateFailure(workspace.id(), operation, target, finalFileCommitted, exception);
            if (exception instanceof WikiPublishException publishException) {
                throw publishException;
            }
            throw new WikiPublishException(WikiPublishException.Reason.METADATA_FAILURE,
                    "CREATE Wiki file was compensated after DB metadata finalization failed", exception);
        }
    }

    private WikiCreatePublishResponse repeatNoOp(long workspaceId, StoredWikiDraft draft) {
        StoredWikiPublishOperation operation = requireOperation(workspaceId, draft.id());
        if (operation.status() != WikiPublishOperationStatus.COMPLETED) {
            throw failure(WikiPublishException.Reason.RECONCILIATION_REQUIRED,
                    "Published Wiki Draft does not have a completed publish operation");
        }
        Path target = pathResolver.resolveAndValidateRealPath(operation.targetPath());
        if (!filePublisher.matches(target, operation.contentHash())) {
            throw failure(WikiPublishException.Reason.PUBLISHED_FILE_DRIFT,
                    "Published Wiki Markdown is missing or no longer matches its recorded content hash");
        }
        return WikiCreatePublishResponse.from(WikiPublishOutcome.NO_OP, operation);
    }

    private void requirePublishable(long workspaceId, StoredWikiDraft draft) {
        if (draft.status() != WikiDraftStatus.READY) {
            throw failure(WikiPublishException.Reason.DRAFT_NOT_READY,
                    "Only a READY Wiki Draft can be published");
        }
        if (draft.action() != LlmProposalAction.CREATE) {
            throw failure(WikiPublishException.Reason.ACTION_NOT_CREATE,
                    "STORY-405 publishes CREATE Drafts only; MERGE is reserved for STORY-406");
        }
        boolean proposalValid = proposalRepository.findDraftConversionSource(workspaceId, draft.proposalId())
                .filter(source -> source.status() == KnowledgeProposalStatus.APPROVED)
                .filter(source -> source.action() == LlmProposalAction.CREATE)
                .isPresent();
        if (!proposalValid) {
            throw failure(WikiPublishException.Reason.PROPOSAL_INVALID,
                    "The source Proposal is no longer an approved CREATE in the active workspace");
        }
    }

    private String requireCanonicalCreateTarget(StoredWikiDraft stored, WikiDraft draft) {
        WikiPage canonical = WikiPage.create(draft.title(), draft.pageType(), null, null, null, null);
        String authoritative = pathResolver.resolveLogicalPath(draft.pageType(), draft.title());
        boolean valid = draft.proposalId() == stored.proposalId()
                && draft.action() == LlmProposalAction.CREATE
                && draft.target().kind() == WikiDraftTarget.Kind.CREATE_NEW
                && draft.pageType() == stored.pageType()
                && draft.title().equals(stored.title())
                && canonical.logicalRelativePath().equals(authoritative)
                && authoritative.equals(draft.target().logicalRelativePath())
                && authoritative.equals(stored.targetPath());
        if (!valid) {
            throw failure(WikiPublishException.Reason.TARGET_CONFLICT,
                    "Persisted Draft target is not the canonical target authorized by the active workspace");
        }
        return authoritative;
    }

    private void compensateFailure(long workspaceId, StoredWikiPublishOperation operation, Path target,
                                   boolean finalFileCommitted, RuntimeException cause) {
        String detail = cause.getClass().getSimpleName() + ": " + nullToEmpty(cause.getMessage());
        boolean fileSafe = !finalFileCommitted || filePublisher.compensate(target, operation.contentHash());
        try {
            if (fileSafe) {
                publicationRepository.markRolledBack(workspaceId, operation.id(), detail);
            } else {
                publicationRepository.markReconciliationRequired(workspaceId, operation.id(), detail);
            }
        } catch (RuntimeException ledgerFailure) {
            throw new WikiPublishException(WikiPublishException.Reason.RECONCILIATION_REQUIRED,
                    "Publish compensation finished, but its DB reconciliation state could not be persisted",
                    ledgerFailure);
        }
        if (!fileSafe) {
            throw new WikiPublishException(WikiPublishException.Reason.RECONCILIATION_REQUIRED,
                    "CREATE publish failed and the exact final file could not be safely compensated", cause);
        }
    }

    private StoredWikiDraft requireDraft(long workspaceId, long draftId) {
        if (draftId <= 0) {
            throw new WikiDraftNotFoundException(draftId);
        }
        return draftRepository.findById(workspaceId, draftId)
                .orElseThrow(() -> new WikiDraftNotFoundException(draftId));
    }

    private StoredWikiPublishOperation requireOperation(long workspaceId, long draftId) {
        return publicationRepository.findByDraft(workspaceId, draftId)
                .orElseThrow(() -> failure(WikiPublishException.Reason.RECONCILIATION_REQUIRED,
                        "Wiki Draft publish operation metadata is missing"));
    }

    private WikiDraft deserialize(StoredWikiDraft draft) {
        try {
            return objectMapper.readValue(draft.structuredDraftJson(), WikiDraft.class);
        } catch (JsonProcessingException exception) {
            throw new WikiPublishException(WikiPublishException.Reason.CONTENT_VALIDATION_FAILED,
                    "Persisted READY Wiki Draft could not be deserialized", exception);
        }
    }

    private WorkspaceResponse activeWorkspace() {
        return workspaceService.findActiveWithoutValidation().orElseThrow(NoActiveWorkspaceException::new);
    }

    private static WikiPublishException failure(WikiPublishException.Reason reason, String message) {
        return new WikiPublishException(reason, message);
    }

    private static String now() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
