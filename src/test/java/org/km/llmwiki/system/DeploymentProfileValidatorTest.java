package org.km.llmwiki.system;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.web.security.OwnerSecurityProperties;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class DeploymentProfileValidatorTest {

    private static final String HASH =
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

    private static OwnerSecurityProperties ownerDisabled() {
        return new OwnerSecurityProperties(false, "", null, null, 0, false,
                null, null, null, null, false, 0, null, 0, null);
    }

    private static OwnerSecurityProperties ownerEnabled() {
        return new OwnerSecurityProperties(true, HASH, null, null, 0, true,
                null, null, null, null, false, 0, null, 0, null);
    }

    private static DeploymentProperties localOnly() {
        return new DeploymentProperties(DeploymentMode.LOCAL_ONLY, List.of(), "127.0.0.1:8765", 1);
    }

    private static DeploymentProperties privateIngress(String... binds) {
        return new DeploymentProperties(
                DeploymentMode.PRIVATE_INGRESS, List.of(binds), "127.0.0.1:8765", 1);
    }

    @Test
    void localOnlyBaselineValidates() {
        new DeploymentProfileValidator(localOnly(), ownerDisabled(), "127.0.0.1", 8765).validate();
    }

    @Test
    void nonLoopbackBackendBindFailsFast() {
        DeploymentProperties properties = localOnly();

        assertThatThrownBy(() -> new DeploymentProfileValidator(
                        properties, ownerDisabled(), "0.0.0.0", 8765).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("loopback");
    }

    @Test
    void privateIngressRequiresExplicitForwarderScope() {
        assertThatThrownBy(() -> new DeploymentProfileValidator(
                        privateIngress(), ownerEnabled(), "127.0.0.1", 8765).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forwarder scope");
    }

    @Test
    void wildcardForwarderBindFailsFast() {
        for (String wildcard : List.of("0.0.0.0", "0.0.0.0:8766", "::", "*", "*:8766")) {
            assertThatThrownBy(() -> new DeploymentProfileValidator(
                            privateIngress(wildcard), ownerEnabled(), "127.0.0.1", 8765).validate())
                    .as("wildcard bind %s must fail closed", wildcard)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("all interfaces");
        }
    }

    @Test
    void privateIngressRequiresOwnerAuthentication() {
        assertThatThrownBy(() -> new DeploymentProfileValidator(
                        privateIngress("100.64.0.5:8766"), ownerDisabled(), "127.0.0.1", 8765)
                        .validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("owner authentication");
    }

    @Test
    void privateIngressWithBoundedForwarderAndOwnerAuthValidates() {
        new DeploymentProfileValidator(
                        privateIngress("100.64.0.5:8766"), ownerEnabled(), "127.0.0.1", 8765)
                .validate();
    }

    @Test
    void forwarderTargetMustStayLoopbackBackend() {
        DeploymentProperties offTarget = new DeploymentProperties(
                DeploymentMode.PRIVATE_INGRESS, List.of("100.64.0.5:8766"), "192.168.1.10:8765", 1);

        assertThatThrownBy(() -> new DeploymentProfileValidator(
                        offTarget, ownerEnabled(), "127.0.0.1", 8765).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("loopback backend");
    }

    @Test
    void multiInstanceFailsFast() {
        DeploymentProperties multi = new DeploymentProperties(
                DeploymentMode.LOCAL_ONLY, List.of(), "127.0.0.1:8765", 2);

        assertThatThrownBy(() -> new DeploymentProfileValidator(
                        multi, ownerDisabled(), "127.0.0.1", 8765).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("single instance");
    }

    @Test
    void localOnlyWithForwarderScopeFailsFast() {
        DeploymentProperties mixed = new DeploymentProperties(
                DeploymentMode.LOCAL_ONLY, List.of("100.64.0.5:8766"), "127.0.0.1:8765", 1);

        assertThatThrownBy(() -> new DeploymentProfileValidator(
                        mixed, ownerDisabled(), "127.0.0.1", 8765).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local-only");
    }

    @Test
    void validationIsRepeatableForRestart() {
        DeploymentProfileValidator validator = new DeploymentProfileValidator(
                privateIngress("100.64.0.5:8766"), ownerEnabled(), "127.0.0.1", 8765);

        validator.validate();
        validator.validate();
    }
}
