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

    @Test
    void classifiesLoopbackHttpAndHttpsAsLocal() {
        assertThat(ProviderEndpointSecurityPolicy.classify("http://127.0.0.1:1234/v1", false))
                .isEqualTo(ProviderDestination.LOCAL_LOOPBACK);
        assertThat(ProviderEndpointSecurityPolicy.classify("http://localhost/v1", false))
                .isEqualTo(ProviderDestination.LOCAL_LOOPBACK);
        assertThat(ProviderEndpointSecurityPolicy.classify("https://localhost/v1", false))
                .isEqualTo(ProviderDestination.LOCAL_LOOPBACK);
        assertThat(ProviderEndpointSecurityPolicy.classify("http://[::1]/v1", false))
                .isEqualTo(ProviderDestination.LOCAL_LOOPBACK);
    }

    @Test
    void classifiesRemoteHttpsAsSecureAndRemoteHttpByOptIn() {
        assertThat(ProviderEndpointSecurityPolicy.classify("https://api.example.com/v1", false))
                .isEqualTo(ProviderDestination.REMOTE_SECURE);
        assertThat(ProviderEndpointSecurityPolicy.classify("http://api.example.com/v1", true))
                .isEqualTo(ProviderDestination.REMOTE_INSECURE_OPT_IN);
        // Without the explicit opt-in, the transport policy would reject the endpoint; the
        // disclosure must never disagree with that policy.
        assertThat(ProviderEndpointSecurityPolicy.classify("http://api.example.com/v1", false))
                .isEqualTo(ProviderDestination.UNAVAILABLE_OR_INVALID);
    }

    @Test
    void classifiesMalformedOrMissingEndpointsAsUnavailable() {
        assertThat(ProviderEndpointSecurityPolicy.classify(null, false))
                .isEqualTo(ProviderDestination.UNAVAILABLE_OR_INVALID);
        assertThat(ProviderEndpointSecurityPolicy.classify("", false))
                .isEqualTo(ProviderDestination.UNAVAILABLE_OR_INVALID);
        assertThat(ProviderEndpointSecurityPolicy.classify("ftp://provider.example/v1", false))
                .isEqualTo(ProviderDestination.UNAVAILABLE_OR_INVALID);
        assertThat(ProviderEndpointSecurityPolicy.classify("http://user:pass@host/v1", true))
                .isEqualTo(ProviderDestination.UNAVAILABLE_OR_INVALID);
        assertThat(ProviderEndpointSecurityPolicy.classify("http://host/v1?key=secret", false))
                .isEqualTo(ProviderDestination.UNAVAILABLE_OR_INVALID);
        // A hostname merely prefixed with localhost is NOT loopback.
        assertThat(ProviderEndpointSecurityPolicy.classify("http://localhost.example/v1", true))
                .isEqualTo(ProviderDestination.REMOTE_INSECURE_OPT_IN);
    }
}
