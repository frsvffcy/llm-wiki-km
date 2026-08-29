package org.km.llmwiki.wiki;

import org.km.llmwiki.ai.LlmProposalAction;
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.stereotype.Service;

/** Dispatches the explicit publish endpoint without broadening either action-specific service. */
@Service
public class WikiPublishService {

    private final WorkspaceService workspaceService;
    private final WikiDraftRepository draftRepository;
    private final WikiCreatePublishService createPublishService;
    private final WikiMergePublishService mergePublishService;

    public WikiPublishService(WorkspaceService workspaceService, WikiDraftRepository draftRepository,
                              WikiCreatePublishService createPublishService,
                              WikiMergePublishService mergePublishService) {
        this.workspaceService = workspaceService;
        this.draftRepository = draftRepository;
        this.createPublishService = createPublishService;
        this.mergePublishService = mergePublishService;
    }

    public WikiPublishResult publish(long draftId) {
        long workspaceId = workspaceService.findActiveWithoutValidation()
                .orElseThrow(NoActiveWorkspaceException::new).id();
        StoredWikiDraft draft = draftRepository.findById(workspaceId, draftId)
                .orElseThrow(() -> new WikiDraftNotFoundException(draftId));
        return draft.action() == LlmProposalAction.CREATE
                ? createPublishService.publish(draftId)
                : mergePublishService.publish(draftId);
    }
}
