package org.km.llmwiki.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/** Trusted, local application configuration for the optional derived Graph projection. */
@ConfigurationProperties("app.graph.projection")
public class GraphProjectionProperties {

    private boolean enabled;
    private String provider = "arcadedb";
    private Path path = Path.of("data/graph");

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider == null ? "" : provider.strip().toLowerCase(java.util.Locale.ROOT);
    }

    public Path getPath() {
        return path;
    }

    public void setPath(Path path) {
        this.path = path;
    }
}
