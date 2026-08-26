package org.km.llmwiki.workspace;

import org.km.llmwiki.web.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {

    private final WorkspaceService service;

    public WorkspaceController(WorkspaceService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<WorkspaceResponse>> create(@RequestBody CreateWorkspaceRequest request) {
        WorkspaceResponse response = service.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(response));
    }

    @GetMapping
    public ApiResponse<List<WorkspaceResponse>> list() {
        return new ApiResponse<>(service.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<WorkspaceResponse> get(@PathVariable long id) {
        return new ApiResponse<>(service.get(id));
    }

    @GetMapping("/current")
    public ApiResponse<WorkspaceStatusResponse> current() {
        return new ApiResponse<>(service.current());
    }

    @PutMapping("/current")
    public ApiResponse<WorkspaceStatusResponse> openCurrent(@RequestBody OpenWorkspaceRequest request) {
        if (request.workspaceId() == null) {
            throw new IllegalArgumentException("workspaceId must not be blank");
        }
        return new ApiResponse<>(service.open(request.workspaceId()));
    }
}
