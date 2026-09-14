package org.km.llmwiki.system;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class DeploymentPropertiesTest {

    @Test
    void defaultsPreserveLocalOnlyBaseline() {
        DeploymentProperties properties =
                new DeploymentProperties(null, null, null, 0);

        assertThat(properties.mode()).isEqualTo(DeploymentMode.LOCAL_ONLY);
        assertThat(properties.forwarderBinds()).isEmpty();
        assertThat(properties.forwarderTarget()).isEqualTo("127.0.0.1:8765");
        assertThat(properties.maxInstances()).isEqualTo(1);
    }

    @Test
    void blankForwarderEntriesAreNormalizedAway() {
        DeploymentProperties properties = new DeploymentProperties(
                DeploymentMode.PRIVATE_INGRESS,
                List.of("  ", "100.64.0.5:8766", ""),
                "127.0.0.1:8765",
                1);

        assertThat(properties.forwarderBinds()).containsExactly("100.64.0.5:8766");
    }
}
