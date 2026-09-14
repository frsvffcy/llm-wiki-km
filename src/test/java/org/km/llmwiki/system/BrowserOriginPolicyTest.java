package org.km.llmwiki.system;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class BrowserOriginPolicyTest {

    @Test
    void httpOriginWithExplicitPortParses() {
        BrowserOriginPolicy.BrowserOrigin origin =
                BrowserOriginPolicy.parse("http://100.64.0.5:8766");

        assertThat(origin.scheme()).isEqualTo("http");
        assertThat(origin.host()).isEqualTo("100.64.0.5");
        assertThat(origin.port()).isEqualTo(8766);
        assertThat(origin.canonical()).isEqualTo("http://100.64.0.5:8766");
    }

    @Test
    void httpsOriginWithoutPortUsesDefaultPort() {
        BrowserOriginPolicy.BrowserOrigin origin =
                BrowserOriginPolicy.parse("https://wiki.internal.example");

        assertThat(origin.scheme()).isEqualTo("https");
        assertThat(origin.host()).isEqualTo("wiki.internal.example");
        assertThat(origin.port()).isEqualTo(443);
    }

    @Test
    void httpOriginWithoutPortUsesDefaultPort() {
        BrowserOriginPolicy.BrowserOrigin origin =
                BrowserOriginPolicy.parse("http://wiki.internal.example");

        assertThat(origin.port()).isEqualTo(80);
    }

    @Test
    void schemeAndHostAreLowercased() {
        BrowserOriginPolicy.BrowserOrigin origin =
                BrowserOriginPolicy.parse("HTTPS://Wiki.Internal.Example:443/");

        assertThat(origin.scheme()).isEqualTo("https");
        assertThat(origin.host()).isEqualTo("wiki.internal.example");
        assertThat(origin.port()).isEqualTo(443);
    }

    @Test
    void malformedOriginsFailFastWithoutEcho() {
        for (String malformed : new String[]{
                null, "", "  ", "not-a-url", "ftp://100.64.0.5:8766",
                "http://user@100.64.0.5:8766", "http://100.64.0.5:8766/path",
                "http://100.64.0.5:8766?query=1", "http://100.64.0.5:8766#frag",
                "http://100.64.0.5:99999", "http://:8766", "null",
                "http://100.64.0.5:8766,http://evil.example",
                " http://100.64.0.5:8766", "http://100.64.0.5:8766 "}) {
            assertThatThrownBy(() -> BrowserOriginPolicy.parse(malformed))
                    .as("malformed origin must fail: %s", malformed)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("browser origin");
        }
    }

    @Test
    void wildcardOriginsFailFast() {
        for (String wildcard : new String[]{
                "http://*:8766", "http://0.0.0.0:8766", "http://[::]:8766"}) {
            assertThatThrownBy(() -> BrowserOriginPolicy.parse(wildcard))
                    .as("wildcard origin %s must fail closed", wildcard)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void loopbackDetectionCoversLocalhostAndRange() {
        assertThat(BrowserOriginPolicy.isLoopbackHost("localhost")).isTrue();
        assertThat(BrowserOriginPolicy.isLoopbackHost("LOCALHOST")).isTrue();
        assertThat(BrowserOriginPolicy.isLoopbackHost("127.0.0.1")).isTrue();
        assertThat(BrowserOriginPolicy.isLoopbackHost("127.0.0.2")).isTrue();
        assertThat(BrowserOriginPolicy.isLoopbackHost("::1")).isTrue();
        assertThat(BrowserOriginPolicy.isLoopbackHost("100.64.0.5")).isFalse();
        assertThat(BrowserOriginPolicy.isLoopbackHost("wiki.internal.example")).isFalse();
        assertThat(BrowserOriginPolicy.isLoopbackHost(null)).isFalse();
    }

    @Test
    void ipLiteralDetectionSeparatesAddressesFromDns() {
        assertThat(BrowserOriginPolicy.isIpLiteral("100.64.0.5")).isTrue();
        assertThat(BrowserOriginPolicy.isIpLiteral("192.168.1.10")).isTrue();
        assertThat(BrowserOriginPolicy.isIpLiteral("::1")).isTrue();
        assertThat(BrowserOriginPolicy.isIpLiteral("wiki.internal.example")).isFalse();
        assertThat(BrowserOriginPolicy.isIpLiteral("localhost")).isFalse();
        assertThat(BrowserOriginPolicy.isIpLiteral(null)).isFalse();
    }
}
