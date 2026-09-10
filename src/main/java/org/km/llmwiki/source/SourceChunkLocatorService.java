package org.km.llmwiki.source;

import org.km.llmwiki.rag.AuthorityRejectionReason;
import org.km.llmwiki.search.SourceSearchAuthorityDocument;
import org.km.llmwiki.search.SourceSearchAuthorityRepository;
import org.km.llmwiki.search.SourceSearchEligibilityPolicy;
import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Read-only navigation boundary for cited Source Chunks. The locator resolves a citation's
 * chunk against the active workspace and revalidates it against the canonical authority
 * snapshot (the same eligibility contract retrieval revalidates against), so a citation opened
 * after drift gets explicit not-current semantics instead of stale content posing as current.
 * It never mutates canonical state, never re-extracts or re-chunks, and never exposes
 * filesystem internals.
 */
@Service
public class SourceChunkLocatorService {

    /** Hard bound for the authoritative preview, measured in Unicode code points. */
    static final int MAX_PREVIEW_CODE_POINTS = 1_600;

    private final WorkspaceService workspaceService;
    private final SourceChunkRepository sourceChunkRepository;
    private final SourceSearchAuthorityRepository sourceAuthorityRepository;

    public SourceChunkLocatorService(WorkspaceService workspaceService,
                                     SourceChunkRepository sourceChunkRepository,
                                     SourceSearchAuthorityRepository sourceAuthorityRepository) {
        this.workspaceService = workspaceService;
        this.sourceChunkRepository = sourceChunkRepository;
        this.sourceAuthorityRepository = sourceAuthorityRepository;
    }

    public SourceLocator locate(long chunkId) {
        WorkspaceResponse workspace = activeWorkspace();
        SourceChunk chunk = sourceChunkRepository.findByIdAndWorkspaceId(chunkId, workspace.id())
                .orElseThrow(() -> new SourceChunkNotFoundException(chunkId));
        Optional<SourceSearchAuthorityDocument> document =
                sourceAuthorityRepository.findDocument(workspace.id(), chunk.documentId());
        if (document.isEmpty()) {
            return notCurrent(chunk, AuthorityRejectionReason.AUTHORITY_MISSING);
        }
        SourceSearchAuthorityDocument authority = document.get();
        if (!SourceSearchEligibilityPolicy.documentEligible(authority)) {
            return notCurrent(chunk, AuthorityRejectionReason.INELIGIBLE);
        }
        var chunks = authority.chunks().stream()
                .filter(authorityChunk -> authorityChunk.sourceChunkId() == chunkId)
                .toList();
        if (chunks.isEmpty()) {
            return notCurrent(chunk, AuthorityRejectionReason.AUTHORITY_MISSING);
        }
        var authorityChunk = chunks.get(0);
        if (!SourceSearchEligibilityPolicy.chunkEligible(authorityChunk)) {
            return notCurrent(chunk, AuthorityRejectionReason.INELIGIBLE);
        }
        BoundedPreview preview = bound(authorityChunk.normalizedContent());
        return new SourceLocator(authorityChunk.sourceChunkId(), authority.documentId(),
                authority.documentName(), authorityChunk.chunkNo(), authorityChunk.pageNo(),
                authorityChunk.section(), authorityChunk.headingPath(),
                ChunkCurrentness.CURRENT, null, preview.text(), preview.truncated());
    }

    /** Navigation metadata stays honest: it comes from the persisted chunk, never from callers. */
    private static SourceLocator notCurrent(SourceChunk chunk, AuthorityRejectionReason reason) {
        return new SourceLocator(chunk.id(), chunk.documentId(), null, chunk.chunkNo(),
                chunk.pageNo(), chunk.section(), chunk.headingPath(),
                ChunkCurrentness.NOT_CURRENT, reason.name(), null, false);
    }

    static BoundedPreview bound(String content) {
        int count = content.codePointCount(0, content.length());
        if (count <= MAX_PREVIEW_CODE_POINTS) {
            return new BoundedPreview(content, false);
        }
        int end = content.offsetByCodePoints(0, MAX_PREVIEW_CODE_POINTS);
        return new BoundedPreview(content.substring(0, end), true);
    }

    record BoundedPreview(String text, boolean truncated) {
    }

    private WorkspaceResponse activeWorkspace() {
        return workspaceService.findActiveWithoutValidation()
                .orElseThrow(NoActiveWorkspaceException::new);
    }
}
