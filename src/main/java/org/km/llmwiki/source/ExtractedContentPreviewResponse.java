package org.km.llmwiki.source;

import org.km.llmwiki.web.PageResponse;

import java.util.List;

public record ExtractedContentPreviewResponse(long documentId, String parseStatus, int chunkCount,
                                              List<ExtractedContentChunk> chunks,
                                              PageResponse.PageMeta page) {
}
