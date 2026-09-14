package org.km.llmwiki.ai.query.provider.openai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Backend-only configuration for the independent query rewrite provider boundary. */
@ConfigurationProperties("app.ai.query-transformation.provider")
public class OpenAiCompatibleQueryRewriteProperties {
    private boolean enabled;
    private String provider = "openai-compatible";
    private String baseUrl = "https://api.openai.com/v1";
    private boolean allowInsecureTransport;
    private String model = "";
    private String apiKey = "";
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(10);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public boolean isAllowInsecureTransport() { return allowInsecureTransport; }
    public void setAllowInsecureTransport(boolean value) { this.allowInsecureTransport = value; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration value) { this.connectTimeout = value; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration value) { this.readTimeout = value; }
}
