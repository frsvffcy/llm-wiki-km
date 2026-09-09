package org.km.llmwiki.source;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Fails fast on unknown or duplicate policy versions and resolves the active chunking policy. */
@Component
public class ChunkingPolicyRegistry {

    private final Map<String, ChunkingPolicy> policiesByVersion;
    private final String activeVersion;

    public ChunkingPolicyRegistry(List<ChunkingPolicy> policies,
                                  @Value("${app.source.chunking.policy-version:"
                                          + SourceChunker.CHUNK_POLICY_VERSION + "}") String activeVersion) {
        this.policiesByVersion = policies.stream()
                .collect(Collectors.toUnmodifiableMap(ChunkingPolicy::version, Function.identity()));
        if (!policiesByVersion.containsKey(activeVersion)) {
            throw new IllegalArgumentException(
                    "未知的 chunking policy version：" + activeVersion);
        }
        this.activeVersion = activeVersion;
    }

    public ChunkingPolicy active() {
        return policiesByVersion.get(activeVersion);
    }

    public String activeVersion() {
        return activeVersion;
    }
}
