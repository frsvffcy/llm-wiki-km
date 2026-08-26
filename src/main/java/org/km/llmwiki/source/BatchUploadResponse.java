package org.km.llmwiki.source;

import java.util.List;

public record BatchUploadResponse(
        int total,
        int accepted,
        int duplicate,
        int failed,
        List<UploadedFileResponse> documents,
        List<FailedFile> failures) {

    public record FailedFile(String fileName, String error) {
    }
}
