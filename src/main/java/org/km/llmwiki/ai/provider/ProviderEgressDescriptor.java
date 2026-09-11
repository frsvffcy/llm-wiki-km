package org.km.llmwiki.ai.provider;

import java.util.List;

/**
 * Safe, application-owned provider egress descriptor for the Browser transparency contract.
 * It contains only allowlisted metadata (purpose, destination class, provider type, a display
 * model name, and the data-category disclosure); it never contains API keys, bearer tokens,
 * raw endpoints, secret-bearing URLs, filesystem paths, provider fingerprints, internal
 * diagnostics, or backend identifiers.
 */
public record ProviderEgressDescriptor(
        ProviderPurpose purpose,
        ProviderDestination destinationClass,
        String providerType,
        String modelDisplayName,
        java.util.List<ProviderEgressCategory> egressCategories
) {
    public ProviderEgressDescriptor {
        if (purpose == null || destinationClass == null) {
            throw new IllegalArgumentException(
                    "provider egress purpose and destination class are required");
        }
        providerType = sanitized(providerType);
        modelDisplayName = sanitized(modelDisplayName);
        egressCategories = java.util.List.copyOf(egressCategories == null ? java.util.List.of()
                : egressCategories);
    }

    private static String sanitized(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            return null;
        }
        String stripped = value.strip();
        // A display field is allowlisted metadata, not an endpoint: anything URL-like,
        // path-like, or multi-line is treated as operator misconfiguration and dropped.
        if (stripped.contains("://") || stripped.contains("/") || stripped.contains("\\")
                || stripped.indexOf('\n') >= 0 || stripped.indexOf('\r') >= 0) {
            return null;
        }
        return stripped;
    }

    /** Which provider boundary the descriptor describes. */
    public enum ProviderPurpose {
        ANSWER,
        EMBEDDING
    }
}
