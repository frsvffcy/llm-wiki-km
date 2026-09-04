package org.km.llmwiki.ai.answer.provider.openai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Backend-only configuration for the first OpenAI-compatible answer adapter. */
@ConfigurationProperties("app.ai.answer")
public class OpenAiCompatibleAnswerProperties {

    private boolean enabled;
    private String provider = "openai-compatible";
    private String baseUrl = "https://api.openai.com/v1";
    private String model = "";
    private String apiKey = "";
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(15);
    private int maxOutputTokens = 4_000;
    private double temperature;

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

    /**
     * The credential is intentionally exposed only to the adapter configuration boundary.
     * This class has no toString implementation and must never be serialized or logged.
     */
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

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }
}
