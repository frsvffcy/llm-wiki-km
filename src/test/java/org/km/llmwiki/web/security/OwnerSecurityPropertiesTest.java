package org.km.llmwiki.web.security;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class OwnerSecurityPropertiesTest {

    private static final String HASH =
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

    @Test
    void localOnlyDefaultLeavesEveryEndpointOpen() {
        OwnerSecurityProperties properties = new OwnerSecurityProperties(false, "",
                null, null, 0, false, null, null, null, null, false, 0, null, 0, null);

        assertThat(properties.authEnabled()).isFalse();
        assertThat(properties.sessionAbsoluteTimeout()).isEqualTo(Duration.ofHours(12));
        assertThat(properties.sessionIdleTimeout()).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.maxSessions()).isEqualTo(8);
        assertThat(properties.cookieName()).isEqualTo("km-owner-session");
        assertThat(properties.allowedHosts()).contains("localhost", "127.0.0.1");
    }

    @Test
    void enabledWithoutPasswordHashFailsFast() {
        OwnerSecurityProperties properties = new OwnerSecurityProperties(true, "",
                null, null, 0, true, null, null, null, null, false, 0, null, 0, null);

        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enabledWithMalformedHashFailsFast() {
        OwnerSecurityProperties properties = new OwnerSecurityProperties(true, "not-a-hash",
                null, null, 0, true, null, null, null, null, false, 0, null, 0, null);

        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enabledWithValidHashAndPositiveTimeoutsPasses() {
        OwnerSecurityProperties properties = new OwnerSecurityProperties(true, HASH,
                Duration.ofHours(12), Duration.ofMinutes(30), 8, true, "km-owner-session",
                List.of("localhost"), List.of("http://localhost:8765"), List.of(), false,
                5, Duration.ofMinutes(1), 60, Duration.ofMinutes(1));

        properties.validate();
    }

    @Test
    void nonPositiveTimeoutsFailFast() {
        OwnerSecurityProperties zeroIdle = new OwnerSecurityProperties(true, HASH,
                Duration.ofHours(12), Duration.ZERO, 8, true, "km-owner-session",
                List.of("localhost"), List.of("http://localhost:8765"), List.of(), false,
                5, Duration.ofMinutes(1), 60, Duration.ofMinutes(1));

        assertThatThrownBy(zeroIdle::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void idleBeyondAbsoluteFailsFast() {
        OwnerSecurityProperties properties = new OwnerSecurityProperties(true, HASH,
                Duration.ofHours(1), Duration.ofHours(2), 8, true, "km-owner-session",
                List.of("localhost"), List.of("http://localhost:8765"), List.of(), false,
                5, Duration.ofMinutes(1), 60, Duration.ofMinutes(1));

        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void proxyTrustWithoutPeersFailsFast() {
        // #422 §B: remote origins must never be trusted via arbitrary
        // Forwarded / X-Forwarded-* material without an explicit peer allowlist.
        OwnerSecurityProperties properties = new OwnerSecurityProperties(true, HASH,
                Duration.ofHours(12), Duration.ofMinutes(30), 8, true, "km-owner-session",
                List.of("localhost"), List.of("http://localhost:8765"), List.of(), true,
                5, Duration.ofMinutes(1), 60, Duration.ofMinutes(1));

        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }
}
