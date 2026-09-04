package org.km.llmwiki.ai.embedding.provider.openai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Backend-only configuration for the OpenAI-compatible embedding adapter. */
@ConfigurationProperties("app.ai.embedding")
public class OpenAiCompatibleEmbeddingProperties {

    private boolean enabled;
    private String provider = "openai-compatible";
    private String baseUrl = "https://api.openai.com/v1";
    private String model = "";
    private String apiKey = "";
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(15);
    private int dimension;

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
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    /** Credential is intentionally available only to this adapter configuration boundary. */
    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    /** Zero means accept any bounded provider dimension; a positive value is enforced exactly. */
    public int getDimension() {
        return dimension;
    }

    public void setDimension(int dimension) {
        this.dimension = dimension;
    }
}
