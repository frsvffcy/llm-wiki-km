package org.km.llmwiki.wiki;

import org.km.llmwiki.ai.LlmProposalAction;
import org.springframework.stereotype.Component;

import java.util.List;

/** Resolves CREATE/MERGE targets without filesystem writes or semantic selection. */
@Component
public class WikiTargetResolver {

    private final WikiLogicalPathAuthority pathAuthority;
    private final WikiTargetCatalog targetCatalog;

    public WikiTargetResolver(WikiLogicalPathAuthority pathAuthority, WikiTargetCatalog targetCatalog) {
        this.pathAuthority = pathAuthority;
        this.targetCatalog = targetCatalog;
    }

    public WikiTargetSnapshot resolveCreate(long activeWorkspaceId, WikiDraft draft) {
        requireDraft(activeWorkspaceId, draft, LlmProposalAction.CREATE);
        WikiPage canonical = WikiPage.create(draft.title(), draft.pageType(), null, null, null, null);
        String authoritativePath = pathAuthority.resolveLogicalPath(draft.pageType(), draft.title());
        if (!canonical.logicalRelativePath().equals(authoritativePath)
                || draft.target().kind() != WikiDraftTarget.Kind.CREATE_NEW
                || !authoritativePath.equals(draft.target().logicalRelativePath())) {
            throw failure(WikiTargetResolutionException.Reason.CANONICAL_INVARIANT_VIOLATION,
                    "CREATE draft target does not match the active canonical path authority");
        }
        if (targetCatalog.existsAtCanonicalPath(activeWorkspaceId, authoritativePath)) {
            throw failure(WikiTargetResolutionException.Reason.CREATE_TARGET_EXISTS,
                    "Canonical CREATE target already exists in the active workspace");
        }
        return WikiTargetSnapshot.createNew(canonical.title(), canonical.pageType(), authoritativePath);
    }

    public WikiTargetSnapshot resolveMerge(long activeWorkspaceId, WikiDraft draft) {
        requireDraft(activeWorkspaceId, draft, LlmProposalAction.MERGE);
        if (draft.target().kind() != WikiDraftTarget.Kind.EXISTING_REFERENCE) {
            throw failure(WikiTargetResolutionException.Reason.CANONICAL_INVARIANT_VIOLATION,
                    "MERGE draft must carry an unresolved exact reference");
        }
        WikiTargetReference reference = WikiTargetReference.parse(draft.target().reference());
        List<WikiTargetRecord> candidates = targetCatalog.findExact(reference);
        if (candidates.isEmpty()) {
            throw failure(WikiTargetResolutionException.Reason.TARGET_NOT_FOUND,
                    "MERGE target reference did not match an indexed Wiki page");
        }
        if (candidates.stream().anyMatch(candidate -> candidate.workspaceId() != activeWorkspaceId)) {
            throw failure(WikiTargetResolutionException.Reason.CROSS_WORKSPACE_AMBIGUITY,
                    "MERGE target reference matched a different workspace");
        }
        if (candidates.size() > 1) {
            throw failure(WikiTargetResolutionException.Reason.TARGET_AMBIGUOUS,
                    "MERGE target reference matched multiple Wiki pages");
        }
        WikiTargetRecord target = candidates.getFirst();
        if (target.status() != PageStatus.PUBLISHED) {
            throw failure(WikiTargetResolutionException.Reason.TARGET_NOT_VISIBLE,
                    "MERGE target is not a visible published Wiki page");
        }
        if (target.pageType() != draft.pageType()) {
            throw failure(WikiTargetResolutionException.Reason.TARGET_PAGE_TYPE_MISMATCH,
                    "MERGE target page type does not match the validated Wiki Draft");
        }
        String authoritativePath = pathAuthority.resolveLogicalPath(target.pageType(), target.title());
        if (!authoritativePath.equals(target.logicalRelativePath())) {
            throw failure(WikiTargetResolutionException.Reason.CANONICAL_INVARIANT_VIOLATION,
                    "MERGE target path does not match the active canonical path authority");
        }
        return WikiTargetSnapshot.existing(target);
    }

    private static void requireDraft(long workspaceId, WikiDraft draft, LlmProposalAction expectedAction) {
        if (workspaceId <= 0 || draft == null || draft.action() != expectedAction) {
            throw new IllegalArgumentException("Target resolution requires an active workspace and matching draft");
        }
    }

    private static WikiTargetResolutionException failure(WikiTargetResolutionException.Reason reason,
                                                          String message) {
        return new WikiTargetResolutionException(reason, message);
    }
}
