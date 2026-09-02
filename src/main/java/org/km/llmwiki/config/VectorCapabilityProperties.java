package org.km.llmwiki.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/** Configuration for the optional native vector capability boundary. */
@ConfigurationProperties("app.search.vector")
public class VectorCapabilityProperties {

    private boolean enabled;
    private Path extensionPath;
    private String requiredExtensionVersion = "v0.1.9";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Path getExtensionPath() {
        return extensionPath;
    }

    public void setExtensionPath(Path extensionPath) {
        this.extensionPath = extensionPath;
    }

    public String getRequiredExtensionVersion() {
        return requiredExtensionVersion;
    }

    public void setRequiredExtensionVersion(String requiredExtensionVersion) {
        if (requiredExtensionVersion == null || requiredExtensionVersion.isBlank()) {
            throw new IllegalArgumentException("Required vector extension version must not be blank");
        }
        this.requiredExtensionVersion = requiredExtensionVersion.strip();
    }
}
