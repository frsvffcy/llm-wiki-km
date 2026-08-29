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

/** Human-explicit MERGE publish with a filesystem-authoritative optimistic lock. */
@Service
public class WikiMergePublishService {

    private final WorkspaceService workspaceService;
    private final KnowledgeProposalRepository proposalRepository;
    private final WikiDraftRepository draftRepository;
    private final WikiTargetResolver targetResolver;
    private final ActiveWorkspaceWikiPathResolver pathResolver;
    private final WikiPublishedMarkdownRenderer renderer;
    private final WikiAtomicFileReplacer fileReplacer;
    private final WikiPublicationRepository publicationRepository;
    private final WikiMergePublicationFinalizer finalizer;
    private final ObjectMapper objectMapper;

    public WikiMergePublishService(WorkspaceService workspaceService,
                                   KnowledgeProposalRepository proposalRepository,
                                   WikiDraftRepository draftRepository,
                                   WikiTargetResolver targetResolver,
                                   ActiveWorkspaceWikiPathResolver pathResolver,
                                   WikiPublishedMarkdownRenderer renderer,
                                   WikiAtomicFileReplacer fileReplacer,
                                   WikiPublicationRepository publicationRepository,
                                   WikiMergePublicationFinalizer finalizer,
                                   ObjectMapper objectMapper) {
        this.workspaceService = workspaceService;
        this.proposalRepository = proposalRepository;
        this.draftRepository = draftRepository;
        this.targetResolver = targetResolver;
        this.pathResolver = pathResolver;
        this.renderer = renderer;
        this.fileReplacer = fileReplacer;
        this.publicationRepository = publicationRepository;
        this.finalizer = finalizer;
        this.objectMapper = objectMapper;
    }

    public WikiMergePublishResponse publish(long draftId) {
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
        WikiTargetSnapshot resolved = resolveTarget(workspace.id(), structuredDraft);
        WikiMergeTargetMetadata targetMetadata = requireTargetMetadata(workspace.id(), draft, resolved);
        Path target = pathResolver.resolveAndValidateRealPath(targetMetadata.targetPath());
        String publishedAt = now();
        int revision = Math.addExact(targetMetadata.revision(), 1);
        String content = renderer.render(structuredDraft, targetMetadata.knowledgeId(), targetMetadata.title(),
                draft.id(), revision, targetMetadata.createdAt(), publishedAt);
        String afterHash = WikiContentHash.sha256(content);

        // This reads raw filesystem bytes and checks expectedContentHash before any publish ledger write.
        StagedWikiReplacement staged = fileReplacer.stage(target, content, draft.expectedContentHash(),
                afterHash, structuredDraft, targetMetadata.title());
        StoredWikiPublishOperation operation;
        try {
            operation = publicationRepository.prepare(new NewWikiPublishOperation(
                    workspace.id(), draft.id(), draft.proposalId(), LlmProposalAction.MERGE,
                    targetMetadata.knowledgeId(), targetMetadata.targetPath(), draft.expectedContentHash(),
                    afterHash, revision, publishedAt));
        } catch (RuntimeException exception) {
            fileReplacer.discard(staged);
            throw exception;
        }

        try {
            fileReplacer.commit(staged);
            publicationRepository.markFileCommitted(workspace.id(), operation.id());
            StoredWikiPublishOperation completed = finalizer.complete(draft,
                    requireOperation(workspace.id(), draft.id()), targetMetadata.id(), publishedAt);
            return WikiMergePublishResponse.from(WikiPublishOutcome.MERGED, completed);
        } catch (RuntimeException exception) {
            fileReplacer.discard(staged);
            compensateFailure(workspace.id(), operation, staged, exception);
            if (exception instanceof WikiPublishException publishException) {
                throw publishException;
            }
            throw new WikiPublishException(WikiPublishException.Reason.METADATA_FAILURE,
                    "MERGE Wiki file was restored after DB metadata finalization failed", exception);
        }
    }

    private WikiMergePublishResponse repeatNoOp(long workspaceId, StoredWikiDraft draft) {
        StoredWikiPublishOperation operation = requireOperation(workspaceId, draft.id());
        if (operation.action() != LlmProposalAction.MERGE
                || operation.status() != WikiPublishOperationStatus.COMPLETED
                || operation.knowledgePageId() == null
                || operation.beforeContentHash() == null) {
            throw failure(WikiPublishException.Reason.RECONCILIATION_REQUIRED,
                    "Published MERGE Draft does not have a completed MERGE operation");
        }
        WikiMergeTargetMetadata metadata = publicationRepository
                .findMergeTarget(workspaceId, operation.knowledgeId())
                .orElseThrow(() -> failure(WikiPublishException.Reason.RECONCILIATION_REQUIRED,
                        "Published MERGE target metadata is missing"));
        boolean metadataMatches = metadata.id() == operation.knowledgePageId()
                && metadata.workspaceId() == workspaceId
                && metadata.status() == PageStatus.PUBLISHED
                && metadata.knowledgeId().equals(draft.targetKnowledgeId())
                && metadata.title().equals(draft.targetTitle())
                && metadata.pageType() == draft.targetPageType()
                && metadata.targetPath().equals(draft.targetPath())
                && metadata.targetPath().equals(operation.targetPath())
                && metadata.contentHash().equals(operation.contentHash())
                && metadata.revision() == operation.revision()
                && draftRepository.matchesPublishedState(workspaceId, draft.id(), operation);
        if (!metadataMatches) {
            throw failure(WikiPublishException.Reason.RECONCILIATION_REQUIRED,
                    "Published MERGE metadata no longer matches its completed operation");
        }
        Path target = pathResolver.resolveAndValidateRealPath(operation.targetPath());
        if (!fileReplacer.matches(target, operation.contentHash())) {
            throw failure(WikiPublishException.Reason.PUBLISHED_FILE_DRIFT,
                    "Published MERGE file is missing or no longer matches its recorded content hash");
        }
        return WikiMergePublishResponse.from(WikiPublishOutcome.NO_OP, operation);
    }

    private void requirePublishable(long workspaceId, StoredWikiDraft draft) {
        if (draft.status() != WikiDraftStatus.READY) {
            throw failure(WikiPublishException.Reason.DRAFT_NOT_READY,
                    "Only a READY Wiki Draft can be published");
        }
        if (draft.action() != LlmProposalAction.MERGE) {
            throw failure(WikiPublishException.Reason.ACTION_NOT_MERGE,
                    "MERGE publish requires a MERGE Wiki Draft");
        }
        boolean proposalValid = proposalRepository.findDraftConversionSource(workspaceId, draft.proposalId())
                .filter(source -> source.status() == KnowledgeProposalStatus.APPROVED)
                .filter(source -> source.action() == LlmProposalAction.MERGE)
                .isPresent();
        if (!proposalValid) {
            throw failure(WikiPublishException.Reason.PROPOSAL_INVALID,
                    "The source Proposal is no longer an approved MERGE in the active workspace");
        }
    }

    private WikiTargetSnapshot resolveTarget(long workspaceId, WikiDraft draft) {
        try {
            return targetResolver.resolveMerge(workspaceId, draft);
        } catch (WikiTargetResolutionException exception) {
            throw new WikiPublishException(WikiPublishException.Reason.TARGET_CONFLICT,
                    "MERGE target could not be resolved safely: " + exception.getMessage(), exception);
        }
    }

    private WikiMergeTargetMetadata requireTargetMetadata(long workspaceId, StoredWikiDraft draft,
                                                          WikiTargetSnapshot resolved) {
        WikiMergeTargetMetadata metadata = publicationRepository.findMergeTarget(workspaceId,
                        resolved.stableIdentifier())
                .orElseThrow(() -> failure(WikiPublishException.Reason.TARGET_MISSING,
                        "MERGE target metadata no longer exists in the active workspace"));
        boolean valid = draft.action() == LlmProposalAction.MERGE
                && draft.targetKnowledgeId() != null
                && draft.expectedContentHash() != null
                && draft.expectedContentHash().equals(draft.baseContentHash())
                && resolved.kind() == WikiTargetSnapshot.Kind.EXISTING
                && resolved.stableIdentifier().equals(draft.targetKnowledgeId())
                && resolved.title().equals(draft.targetTitle())
                && resolved.pageType() == draft.targetPageType()
                && resolved.logicalRelativePath().equals(draft.targetPath())
                && metadata.workspaceId() == workspaceId
                && metadata.knowledgeId().equals(draft.targetKnowledgeId())
                && metadata.title().equals(draft.targetTitle())
                && metadata.pageType() == draft.targetPageType()
                && metadata.targetPath().equals(draft.targetPath())
                && metadata.status() == PageStatus.PUBLISHED;
        if (!valid) {
            throw failure(WikiPublishException.Reason.TARGET_CONFLICT,
                    "Persisted MERGE Draft no longer matches the unique canonical target identity");
        }
        String canonicalPath = pathResolver.resolveLogicalPath(metadata.pageType(), metadata.title());
        if (!canonicalPath.equals(metadata.targetPath())) {
            throw failure(WikiPublishException.Reason.TARGET_CONFLICT,
                    "MERGE target path no longer matches the active canonical path authority");
        }
        return metadata;
    }

    private void compensateFailure(long workspaceId, StoredWikiPublishOperation operation,
                                   StagedWikiReplacement staged, RuntimeException cause) {
        String detail = cause.getClass().getSimpleName() + ": " + nullToEmpty(cause.getMessage());
        // A failed atomic-replace call may still have moved the staged file before throwing.
        // Always inspect the filesystem rather than trusting an in-memory commit flag.
        boolean fileSafe = fileReplacer.compensate(staged);
        try {
            if (fileSafe) {
                publicationRepository.markRolledBack(workspaceId, operation.id(), detail);
            } else {
                publicationRepository.markReconciliationRequired(workspaceId, operation.id(), detail);
            }
        } catch (RuntimeException ledgerFailure) {
            throw new WikiPublishException(WikiPublishException.Reason.RECONCILIATION_REQUIRED,
                    "MERGE compensation finished, but its DB state could not be persisted", ledgerFailure);
        }
        if (!fileSafe) {
            throw new WikiPublishException(WikiPublishException.Reason.RECONCILIATION_REQUIRED,
                    "MERGE publish failed and the original raw bytes could not be safely restored", cause);
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
                    "Persisted READY MERGE Draft could not be deserialized", exception);
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
