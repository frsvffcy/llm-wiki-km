package org.km.llmwiki.source;

import org.km.llmwiki.web.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/inbox")
public class InboxController {

    private final InboxFileService inboxFileService;

    public InboxController(InboxFileService inboxFileService) {
        this.inboxFileService = inboxFileService;
    }

    @PostMapping("/files")
    public ResponseEntity<ApiResponse<UploadedFileResponse>> upload(@RequestPart("file") MultipartFile file) {
        UploadedFileResponse response = inboxFileService.upload(file);
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(response));
    }
}
