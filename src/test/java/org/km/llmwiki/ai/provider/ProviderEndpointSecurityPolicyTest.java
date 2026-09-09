package org.km.llmwiki.ai.provider;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("contract")
class ProviderEndpointSecurityPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:1234/v1",
            "http://LOCALHOST:1234/v1",
            "http://127.0.0.1:1234/v1",
            "http://127.42.7.9:1234/v1",
            "http://[::1]:1234/v1"
    })
    void allowsLoopbackHttpWithoutOptIn(String baseUrl) throws Exception {
        URI endpoint = ProviderEndpointSecurityPolicy.validateAndAppend(
                baseUrl, "/test", false);

        assertThat(endpoint.getPath()).isEqualTo("/v1/test");
    }

    @Test
    void allowsHttpsRemoteHostWithoutInsecureOptIn() throws Exception {
        URI endpoint = ProviderEndpointSecurityPolicy.validateAndAppend(
                "https://provider.example/v1", "/test", false);

        assertThat(endpoint).isEqualTo(URI.create("https://provider.example/v1/test"));
    }

    @Test
    void rejectsRemoteHttpByDefaultWithoutExposingConfiguredUrl() {
        String configuredUrl = "http://provider.example/v1/private-token";

        assertThatThrownBy(() -> ProviderEndpointSecurityPolicy.validateAndAppend(
                configuredUrl, "/test", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("provider endpoint requires secure transport")
                .hasMessageNotContaining(configuredUrl);
    }

    @Test
    void sanitizesMalformedUriDiagnosticsWithoutExposingConfiguredUrl() {
        String configuredUrl = "https://provider.example/v1/%zz-secret";

        assertThatThrownBy(() -> ProviderEndpointSecurityPolicy.validateAndAppend(
                configuredUrl, "/test", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("provider endpoint is invalid")
                .hasMessageNotContaining(configuredUrl);
    }

    @Test
    void allowsRemoteHttpOnlyWithExplicitOptIn() throws Exception {
        URI endpoint = ProviderEndpointSecurityPolicy.validateAndAppend(
                "http://provider.example/v1", "/test", true);

        assertThat(endpoint).isEqualTo(URI.create("http://provider.example/v1/test"));
    }

    @Test
    void doesNotTreatHostnameWithLocalhostPrefixAsLoopback() {
        assertThatThrownBy(() -> ProviderEndpointSecurityPolicy.validateAndAppend(
                "http://localhost.evil.example/v1", "/test", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retainsFailClosedUriValidation() {
        for (String invalidUrl : new String[]{
                "http://user:password@provider.example/v1",
                "http://provider.example/v1?api-key=secret",
                "http://provider.example/v1#secret",
                "http://provider.example/%zz",
                "ftp://provider.example/v1",
                "http:///v1"
        }) {
            assertThatThrownBy(() -> ProviderEndpointSecurityPolicy.validateAndAppend(
                    invalidUrl, "/test", true))
                    .isInstanceOf(Exception.class);
        }
    }
}
