package org.km.llmwiki.system;

import org.km.llmwiki.ai.provider.ProviderEgressDescriptor;
import org.km.llmwiki.ai.provider.ProviderEgressService;
import org.km.llmwiki.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/system")
public class SystemStatusController {

    private final SystemStatusService systemService;
    private final ProviderEgressService providerEgressService;

    public SystemStatusController(SystemStatusService systemService,
                                  ProviderEgressService providerEgressService) {
        this.systemService = systemService;
        this.providerEgressService = providerEgressService;
    }

    @GetMapping("/status")
    public ApiResponse<SystemStatusResponse> status() {
        return new ApiResponse<>(systemService.getStatus());
    }

    /**
     * Read-only provider egress transparency: application-owned destination classification and
     * data-category disclosure for each provider boundary. Never contains credentials, raw
     * endpoints, paths, or provider details, and is not a readiness/capability surface.
     */
    @GetMapping("/ai-provider-egress")
    public ApiResponse<List<ProviderEgressDescriptor>> aiProviderEgress() {
        return new ApiResponse<>(providerEgressService.descriptors());
    }
}
