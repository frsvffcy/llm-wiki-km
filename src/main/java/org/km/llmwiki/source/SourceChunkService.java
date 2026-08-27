package org.km.llmwiki.source;

import org.km.llmwiki.workspace.NoActiveWorkspaceException;
import org.km.llmwiki.workspace.WorkspaceResponse;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SourceChunkService {

    private final WorkspaceService workspaceService;
    private final DocumentRepository documentRepository;
    private final SourceChunkRepository sourceChunkRepository;

    public SourceChunkService(WorkspaceService workspaceService, DocumentRepository documentRepository,
                              SourceChunkRepository sourceChunkRepository) {
        this.workspaceService = workspaceService;
        this.documentRepository = documentRepository;
        this.sourceChunkRepository = sourceChunkRepository;
    }

    public List<SourceChunk> listByDocumentId(long documentId) {
        WorkspaceResponse workspace = activeWorkspace();
        documentRepository.findExtractionTarget(workspace.id(), documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        return sourceChunkRepository.findByDocumentId(documentId);
    }

    public SourceChunk findById(long chunkId) {
        WorkspaceResponse workspace = activeWorkspace();
        return sourceChunkRepository.findByIdAndWorkspaceId(chunkId, workspace.id())
                .orElseThrow(() -> new SourceChunkNotFoundException(chunkId));
    }

    private WorkspaceResponse activeWorkspace() {
        return workspaceService.findActiveWithoutValidation().orElseThrow(NoActiveWorkspaceException::new);
    }
}
