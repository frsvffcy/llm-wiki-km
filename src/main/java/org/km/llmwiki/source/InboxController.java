package org.km.llmwiki.source;

import org.km.llmwiki.web.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v1/inbox")
public class InboxController {

    private final InboxFileService inboxFileService;
    private final InboxScanService inboxScanService;

    public InboxController(InboxFileService inboxFileService, InboxScanService inboxScanService) {
        this.inboxFileService = inboxFileService;
        this.inboxScanService = inboxScanService;
    }

    @PostMapping("/rescan")
    public ApiResponse<RescanResponse> rescan() {
        return new ApiResponse<>(inboxScanService.rescan());
    }

    @PostMapping("/files")
    public ResponseEntity<ApiResponse<UploadedFileResponse>> upload(@RequestPart("file") MultipartFile file) {
        UploadedFileResponse response = inboxFileService.upload(file);
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(response));
    }

    @PostMapping("/files/batch")
    public ApiResponse<BatchUploadResponse> uploadBatch(
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            @RequestParam(value = "files[]", required = false) List<MultipartFile> filesWithBrackets) {
        List<MultipartFile> all = new ArrayList<>();
        if (files != null) {
            all.addAll(files);
        }
        if (filesWithBrackets != null) {
            all.addAll(filesWithBrackets);
        }
        if (all.isEmpty()) {
            throw new IllegalArgumentException("at least one file is required");
        }
        return new ApiResponse<>(inboxFileService.uploadAll(all));
    }
}
