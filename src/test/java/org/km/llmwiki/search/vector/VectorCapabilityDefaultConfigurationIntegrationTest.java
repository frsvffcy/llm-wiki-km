package org.km.llmwiki.search.vector;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class VectorCapabilityDefaultConfigurationIntegrationTest extends IsolatedIntegrationTest {

    @Autowired
    private VectorCapability vectorCapability;

    @Test
    void vectorCapabilityIsDisabledByDefaultAndDoesNotPretendToHaveNoResults() {
        VectorCapabilityReport report = vectorCapability.inspect(
                new VectorCapabilityRequest(3, VectorEncoding.FLOAT32));

        assertThat(report.availability()).isEqualTo(VectorAvailability.UNAVAILABLE);
        assertThat(report.failure()).isEqualTo(VectorCapabilityFailure.DISABLED);
        assertThat(report.extensionLoadStatus()).isEqualTo(VectorExtensionLoadStatus.NOT_ATTEMPTED);
    }
}
