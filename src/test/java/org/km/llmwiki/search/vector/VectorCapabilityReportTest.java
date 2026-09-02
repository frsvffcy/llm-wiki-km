package org.km.llmwiki.search.vector;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class VectorCapabilityReportTest {

    @Test
    void availableReportCannotCarryAFailureCode() {
        VectorCapabilityRequest request = new VectorCapabilityRequest(3, VectorEncoding.FLOAT32);

        assertThatThrownBy(() -> new VectorCapabilityReport(VectorAvailability.AVAILABLE,
                VectorHealth.HEALTHY, VectorExtensionLoadStatus.LOADED, 3, VectorEncoding.FLOAT32,
                true, Set.of(VectorEncoding.FLOAT32), VectorCapabilityFailure.CAPABILITY_CHECK_FAILED,
                "bad report", "v0.1.9"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unavailableReportHasAStableTypedFailure() {
        VectorCapabilityReport report = VectorCapabilityReport.unavailable(
                new VectorCapabilityRequest(3, VectorEncoding.FLOAT32),
                VectorCapabilityFailure.EXTENSION_LOAD_FAILED,
                VectorExtensionLoadStatus.FAILED,
                "Vector extension load failed", null);

        assertThat(report.availability()).isEqualTo(VectorAvailability.UNAVAILABLE);
        assertThat(report.health()).isEqualTo(VectorHealth.UNHEALTHY);
        assertThat(report.failure()).isEqualTo(VectorCapabilityFailure.EXTENSION_LOAD_FAILED);
        assertThat(report.requestedDimension()).isEqualTo(3);
    }
}
