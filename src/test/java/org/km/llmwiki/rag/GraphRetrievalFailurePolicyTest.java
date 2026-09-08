package org.km.llmwiki.rag;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.km.llmwiki.graph.GraphProjectionException;
import org.km.llmwiki.graph.GraphProjectionFailureType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Classification contract for graph retrieval failures at the optional-graph boundaries:
 * recognized operational failures degrade the graph modality only, integrity violations fail
 * closed typed, and unrecognized runtime faults propagate unchanged.
 */
@Tag("unit")
class GraphRetrievalFailurePolicyTest {

    @ParameterizedTest
    @EnumSource(value = GraphProjectionFailureType.class, names = {
            "CAPABILITY_UNAVAILABLE", "PROJECTION_INCOMPATIBLE", "BACKEND_LOCKED",
            "FILESYSTEM_UNAVAILABLE", "TRANSACTION_FAILURE", "BACKEND_FAILURE",
            "CONFIGURATION_INVALID"
    }, mode = EnumSource.Mode.INCLUDE)
    void operationalProjectionFailuresDegradeTheGraphModalityOnly(
            GraphProjectionFailureType type) {
        GraphRetrievalFailurePolicy.NormalizedFailure normalized =
                GraphRetrievalFailurePolicy.normalize(new GraphProjectionException(type));

        assertThat(normalized.verdict()).isEqualTo(GraphRetrievalFailurePolicy.Verdict.DEGRADE);
        assertThat(normalized.outcome()).isEqualTo(ModalityOutcome.UNAVAILABLE);
        assertThat(normalized.detail()).isEqualTo(type.publicCode());
    }

    @Test
    void disabledCapabilityDegradesAsDisabledAndStaleAsDegradedAndNotReadyAsNotReady() {
        assertThat(GraphRetrievalFailurePolicy.normalize(
                new GraphProjectionException(GraphProjectionFailureType.CAPABILITY_DISABLED)))
                .satisfies(normalized -> {
                    assertThat(normalized.verdict())
                            .isEqualTo(GraphRetrievalFailurePolicy.Verdict.DEGRADE);
                    assertThat(normalized.outcome()).isEqualTo(ModalityOutcome.DISABLED);
                });
        assertThat(GraphRetrievalFailurePolicy.normalize(
                new GraphProjectionException(GraphProjectionFailureType.PROJECTION_STALE)))
                .satisfies(normalized -> {
                    assertThat(normalized.verdict())
                            .isEqualTo(GraphRetrievalFailurePolicy.Verdict.DEGRADE);
                    assertThat(normalized.outcome()).isEqualTo(ModalityOutcome.DEGRADED);
                });
        assertThat(GraphRetrievalFailurePolicy.normalize(
                new GraphProjectionException(GraphProjectionFailureType.PROJECTION_NOT_READY)))
                .satisfies(normalized -> {
                    assertThat(normalized.verdict())
                            .isEqualTo(GraphRetrievalFailurePolicy.Verdict.DEGRADE);
                    assertThat(normalized.outcome()).isEqualTo(ModalityOutcome.NOT_READY);
                });
    }

    @ParameterizedTest
    @EnumSource(value = GraphProjectionFailureType.class, names = {
            "PROJECTION_CORRUPT", "INVALID_PROJECTION_INPUT", "INVALID_PROVENANCE",
            "CROSS_WORKSPACE", "INVALID_TRAVERSAL_BOUNDS", "LOCAL_VALIDATION"
    }, mode = EnumSource.Mode.INCLUDE)
    void integrityAndCorrectnessViolationsFailClosedInsteadOfDegrading(
            GraphProjectionFailureType type) {
        RuntimeException failure = new GraphProjectionException(type);

        GraphRetrievalFailurePolicy.NormalizedFailure normalized =
                GraphRetrievalFailurePolicy.normalize(failure);

        assertThat(normalized.verdict())
                .isEqualTo(GraphRetrievalFailurePolicy.Verdict.FAIL_CLOSED);
        assertThat(GraphRetrievalFailurePolicy.typedFailure(failure))
                .isInstanceOfSatisfying(RetrievalUnavailableException.class, typed -> {
                    assertThat(typed.dependency())
                            .isEqualTo(RetrievalUnavailableException.Dependency.GRAPH);
                    assertThat(typed.getCause()).isSameAs(failure);
                });
    }

    @Test
    void controlPlaneInfrastructureFailureDegradesAsUnavailable() {
        GraphRetrievalFailurePolicy.NormalizedFailure normalized =
                GraphRetrievalFailurePolicy.normalize(
                        new org.jooq.exception.DataAccessException("control plane down"));

        assertThat(normalized.verdict()).isEqualTo(GraphRetrievalFailurePolicy.Verdict.DEGRADE);
        assertThat(normalized.outcome()).isEqualTo(ModalityOutcome.UNAVAILABLE);
        assertThat(normalized.detail()).isEqualTo("graph infrastructure failure");
    }

    @Test
    void unrecognizedRuntimeFaultsPropagateAndAreNeverSwallowed() {
        IllegalStateException defect = new IllegalStateException("programming defect");
        NullPointerException nullDefect = new NullPointerException("unexpected");

        assertThat(GraphRetrievalFailurePolicy.normalize(defect).verdict())
                .isEqualTo(GraphRetrievalFailurePolicy.Verdict.PROPAGATE);
        assertThat(GraphRetrievalFailurePolicy.normalize(nullDefect).verdict())
                .isEqualTo(GraphRetrievalFailurePolicy.Verdict.PROPAGATE);
    }
}
