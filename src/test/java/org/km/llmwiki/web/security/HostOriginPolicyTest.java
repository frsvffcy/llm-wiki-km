package org.km.llmwiki.web.security;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class HostOriginPolicyTest {

    private static final List<String> HOSTS = List.of("localhost", "127.0.0.1");
    private static final List<String> ORIGINS =
            List.of("http://localhost:8765", "http://127.0.0.1:8765");

    @Test
    void allowlistedHostsWithAndWithoutPortPass() {
        assertThat(HostOriginPolicy.validHost("localhost", HOSTS)).isTrue();
        assertThat(HostOriginPolicy.validHost("127.0.0.1:8765", HOSTS)).isTrue();
        assertThat(HostOriginPolicy.validHost("LOCALHOST:8765", HOSTS)).isTrue();
    }

    @Test
    void foreignMalformedAndDuplicatedHostsFailClosed() {
        assertThat(HostOriginPolicy.validHost("evil.example", HOSTS)).isFalse();
        assertThat(HostOriginPolicy.validHost("localhost.evil.example", HOSTS)).isFalse();
        assertThat(HostOriginPolicy.validHost(null, HOSTS)).isFalse();
        assertThat(HostOriginPolicy.validHost("", HOSTS)).isFalse();
        assertThat(HostOriginPolicy.validHost(" localhost", HOSTS)).isFalse();
        assertThat(HostOriginPolicy.validHost("localhost,evil.example", HOSTS)).isFalse();
        assertThat(HostOriginPolicy.validHost("user@localhost", HOSTS)).isFalse();
        assertThat(HostOriginPolicy.validHost("localhost:", HOSTS)).isFalse();
        assertThat(HostOriginPolicy.validHost("localhost:0", HOSTS)).isFalse();
        assertThat(HostOriginPolicy.validHost("localhost:99999", HOSTS)).isFalse();
        assertThat(HostOriginPolicy.validHost("localhost:8765:1", HOSTS)).isFalse();
        assertThat(HostOriginPolicy.validHost(":8765", HOSTS)).isFalse();
    }

    @Test
    void allowlistedOriginsPassExactly() {
        assertThat(HostOriginPolicy.validOrigin("http://localhost:8765", ORIGINS)).isTrue();
        assertThat(HostOriginPolicy.validOrigin("http://127.0.0.1:8765", ORIGINS)).isTrue();
    }

    @Test
    void wildcardIsNeverAnAllowlistedOrigin() {
        assertThat(HostOriginPolicy.validOrigin("*", ORIGINS)).isFalse();
        assertThat(HostOriginPolicy.validOrigin("null", ORIGINS)).isFalse();
        assertThat(HostOriginPolicy.validOrigin("https://localhost:8765", ORIGINS)).isFalse();
        assertThat(HostOriginPolicy.validOrigin("http://localhost:9999", ORIGINS)).isFalse();
        assertThat(HostOriginPolicy.validOrigin("http://evil.example", ORIGINS)).isFalse();
        assertThat(HostOriginPolicy.validOrigin("http://localhost.evil.example:8765", ORIGINS)).isFalse();
    }

    @Test
    void malformedOriginsFailClosed() {
        assertThat(HostOriginPolicy.validOrigin(null, ORIGINS)).isFalse();
        assertThat(HostOriginPolicy.validOrigin("", ORIGINS)).isFalse();
        assertThat(HostOriginPolicy.validOrigin(" http://localhost:8765", ORIGINS)).isFalse();
        assertThat(HostOriginPolicy.validOrigin("http://localhost:8765,http://evil.example", ORIGINS))
                .isFalse();
        assertThat(HostOriginPolicy.validOrigin("http://user@localhost:8765", ORIGINS)).isFalse();
        assertThat(HostOriginPolicy.validOrigin("http://localhost:8765/path", ORIGINS)).isFalse();
        assertThat(HostOriginPolicy.validOrigin("http://localhost:8765?q=1", ORIGINS)).isFalse();
        assertThat(HostOriginPolicy.validOrigin("http://localhost:8765#frag", ORIGINS)).isFalse();
        assertThat(HostOriginPolicy.validOrigin("file://localhost:8765", ORIGINS)).isFalse();
        assertThat(HostOriginPolicy.validOrigin("http://localhost:0", ORIGINS)).isFalse();
    }
}
