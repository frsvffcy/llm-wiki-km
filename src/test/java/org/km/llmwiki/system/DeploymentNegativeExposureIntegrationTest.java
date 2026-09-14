package org.km.llmwiki.system;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.web.security.OwnerSecurityProperties;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Raw backend exposure negative contract (#418 §B).
 *
 * <p>Proves three layers at once: a loopback-bound listener is addressable as
 * loopback only (the forwarder path) and never as a wildcard bind; the profile
 * validator rejects a non-loopback backend and a wildcard forwarder at startup;
 * and the rejection is repeatable so restart/redeploy cannot silently widen
 * exposure. No documentation claim substitutes for these checks.
 */
@Tag("integration")
class DeploymentNegativeExposureIntegrationTest {

    private static OwnerSecurityProperties ownerDisabled() {
        return new OwnerSecurityProperties(false, "", null, null, 0, false,
                null, null, null, null, false, 0, null, 0, null);
    }

    @Test
    void loopbackListenerIsLoopbackOnly() throws Exception {
        try (ServerSocket backend =
                     new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            assertThat(backend.getInetAddress().isLoopbackAddress()).isTrue();
            assertThat(backend.getInetAddress().getHostAddress()).isEqualTo("127.0.0.1");

            int port = backend.getLocalPort();
            try (Socket forwarder = new Socket()) {
                forwarder.connect(new InetSocketAddress("127.0.0.1", port), 2000);
                assertThat(forwarder.isConnected()).isTrue();
            }
        }
    }

    @Test
    void wildcardBackendBindIsRejected() {
        DeploymentProperties properties = new DeploymentProperties(
                DeploymentMode.LOCAL_ONLY, List.of(), "127.0.0.1:8765", 1);

        assertThatThrownBy(() -> new DeploymentProfileValidator(
                        properties, ownerDisabled(), "0.0.0.0", 8765).validate())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void wildcardForwarderScopeIsRejected() {
        DeploymentProperties properties = new DeploymentProperties(
                DeploymentMode.PRIVATE_INGRESS,
                List.of("0.0.0.0:8766"),
                "127.0.0.1:8765",
                1);

        OwnerSecurityProperties owner = new OwnerSecurityProperties(true,
                "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
                null, null, 0, true, null, null, null, null, false, 0, null, 0, null);

        assertThatThrownBy(() -> new DeploymentProfileValidator(
                        properties, owner, "127.0.0.1", 8765).validate())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectionRepeatsAfterRestart() {
        DeploymentProperties properties = new DeploymentProperties(
                DeploymentMode.LOCAL_ONLY, List.of(), "127.0.0.1:8765", 1);
        DeploymentProfileValidator validator = new DeploymentProfileValidator(
                properties, ownerDisabled(), "0.0.0.0", 8765);

        assertThatThrownBy(validator::validate).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(validator::validate).isInstanceOf(IllegalStateException.class);
    }
}
