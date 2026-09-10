package org.km.llmwiki.source;

import org.km.llmwiki.web.ApiResponse;
import org.km.llmwiki.web.SourceLocatorResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class SourceChunkController {

    private final SourceChunkService sourceChunkService;
    private final SourceChunkLocatorService locatorService;

    public SourceChunkController(SourceChunkService sourceChunkService,
                                 SourceChunkLocatorService locatorService) {
        this.sourceChunkService = sourceChunkService;
        this.locatorService = locatorService;
    }

    @GetMapping("/documents/{documentId}/chunks")
    public ApiResponse<List<SourceChunk>> list(@PathVariable long documentId) {
        return new ApiResponse<>(sourceChunkService.listByDocumentId(documentId));
    }

    @GetMapping("/source-chunks/{chunkId}")
    public ApiResponse<SourceChunk> get(@PathVariable long chunkId) {
        return new ApiResponse<>(sourceChunkService.findById(chunkId));
    }

    @GetMapping("/source-chunks/{chunkId}/locator")
    public ApiResponse<SourceLocatorResponse> locator(@PathVariable long chunkId) {
        return new ApiResponse<>(toResponse(locatorService.locate(chunkId)));
    }

    private static SourceLocatorResponse toResponse(SourceLocator locator) {
        return new SourceLocatorResponse(
                locator.sourceChunkId(),
                locator.documentId(),
                locator.documentName(),
                locator.chunkNo(),
                locator.pageNo(),
                locator.section(),
                locator.headingPath(),
                locator.currentness().name(),
                locator.notCurrentReason(),
                locator.preview(),
                locator.previewTruncated());
    }
}
