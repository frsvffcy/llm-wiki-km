package com.frsvffcy.llmwiki.system;

import com.frsvffcy.llmwiki.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemStatusController {

    @GetMapping("/status")
    public ApiResponse<SystemStatusResponse> status() {
        return new ApiResponse<>(new SystemStatusResponse("READY", "0.1.0"));
    }
}

