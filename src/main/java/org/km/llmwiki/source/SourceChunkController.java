package org.km.llmwiki.source;

import org.km.llmwiki.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class SourceChunkController {

    private final SourceChunkService sourceChunkService;

    public SourceChunkController(SourceChunkService sourceChunkService) {
        this.sourceChunkService = sourceChunkService;
    }

    @GetMapping("/documents/{documentId}/chunks")
    public ApiResponse<List<SourceChunk>> list(@PathVariable long documentId) {
        return new ApiResponse<>(sourceChunkService.listByDocumentId(documentId));
    }

    @GetMapping("/source-chunks/{chunkId}")
    public ApiResponse<SourceChunk> get(@PathVariable long chunkId) {
        return new ApiResponse<>(sourceChunkService.findById(chunkId));
    }
}
