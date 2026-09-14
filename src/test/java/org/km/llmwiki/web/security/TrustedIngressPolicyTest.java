package org.km.llmwiki.web.security;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class TrustedIngressPolicyTest {

    private static OwnerSecurityProperties untrusted() {
        return new OwnerSecurityProperties(false, "", null, null, 0, true, null,
                List.of("localhost", "127.0.0.1"),
                List.of("http://localhost:8765"), List.of(), false, 0, null, 0, null);
    }

    private static OwnerSecurityProperties trusted() {
        return new OwnerSecurityProperties(false, "", null, null, 0, true, null,
                List.of("localhost", "127.0.0.1"),
                List.of("http://localhost:8765"), List.of("10.0.0.5"), true, 0, null, 0, null);
    }

    @Test
    void directRequestIgnoresSpoofedProxyHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.9");
        request.addHeader("Host", "evil.example");
        request.addHeader("X-Forwarded-Host", "localhost");
        request.addHeader("X-Forwarded-Proto", "http");
        request.addHeader("X-Forwarded-User", "owner");
        request.addHeader("Forwarded", "for=10.0.0.5;user=owner");

        assertThat(TrustedIngressPolicy.useProxyHeaders(request, untrusted())).isFalse();
        assertThat(TrustedIngressPolicy.effectiveHost(request, untrusted())).isEqualTo("evil.example");
        assertThat(HostOriginPolicy.validHost(
                TrustedIngressPolicy.effectiveHost(request, untrusted()),
                untrusted().allowedHosts())).isFalse();
    }

    @Test
    void upstreamIdentityHeadersNeverEstablishTrust() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.9");
        request.addHeader("Host", "localhost");
        request.addHeader("X-Forwarded-User", "owner");
        request.addHeader("X-Remote-User", "owner");

        assertThat(TrustedIngressPolicy.useProxyHeaders(request, untrusted())).isFalse();
        assertThat(TrustedIngressPolicy.useProxyHeaders(request, trusted())).isFalse();
    }

    @Test
    void configuredTrustedIngressMayEstablishProxyTrust() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("Host", "internal:8765");
        request.addHeader("X-Forwarded-Host", "localhost:8765");
        request.addHeader("X-Forwarded-Proto", "http");
        request.addHeader("Origin", "http://ignored.invalid");

        assertThat(TrustedIngressPolicy.useProxyHeaders(request, trusted())).isTrue();
        assertThat(TrustedIngressPolicy.effectiveHost(request, trusted()))
                .isEqualTo("localhost:8765");
        assertThat(HostOriginPolicy.validHost(
                TrustedIngressPolicy.effectiveHost(request, trusted()),
                trusted().allowedHosts())).isTrue();
    }

    @Test
    void trustedFlagWithoutAllowlistedPeerStaysUntrusted() {
        OwnerSecurityProperties properties = new OwnerSecurityProperties(false, "", null, null, 0,
                true, null, List.of("localhost"), List.of("http://localhost:8765"),
                List.of("10.0.0.5"), false, 0, null, 0, null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("Host", "internal:8765");
        request.addHeader("X-Forwarded-Host", "localhost");

        assertThat(TrustedIngressPolicy.useProxyHeaders(request, properties)).isFalse();
        assertThat(TrustedIngressPolicy.effectiveHost(request, properties))
                .isEqualTo("internal:8765");
    }

    @Test
    void duplicatedForwardedValuesNeverOverride() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("Host", "internal:8765");
        request.addHeader("X-Forwarded-Host", "localhost");
        request.addHeader("X-Forwarded-Host", "evil.example");

        assertThat(TrustedIngressPolicy.effectiveHost(request, trusted()))
                .isEqualTo("internal:8765");
    }
}
