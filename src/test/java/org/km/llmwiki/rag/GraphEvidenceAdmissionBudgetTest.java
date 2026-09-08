package org.km.llmwiki.rag;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class GraphEvidenceAdmissionBudgetTest {

    @Test
    void defaultsStayInsideTheHardBounds() {
        assertThat(GraphEvidenceAdmissionBudget.defaults())
                .isEqualTo(new GraphEvidenceAdmissionBudget(
                        GraphEvidenceAdmissionBudget.DEFAULT_MAX_ITEMS,
                        GraphEvidenceAdmissionBudget.DEFAULT_MAX_CHARACTERS));
        assertThat(new GraphEvidenceAdmissionBudget(
                GraphEvidenceAdmissionBudget.HARD_MAX_ITEMS,
                GraphEvidenceAdmissionBudget.HARD_MAX_CHARACTERS)).isNotNull();
    }

    @Test
    void budgetsBeyondTheHardBoundsAreRejectedAtConstruction() {
        assertThatThrownBy(() -> new GraphEvidenceAdmissionBudget(0, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphEvidenceAdmissionBudget(
                GraphEvidenceAdmissionBudget.HARD_MAX_ITEMS + 1, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphEvidenceAdmissionBudget(1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphEvidenceAdmissionBudget(1,
                GraphEvidenceAdmissionBudget.HARD_MAX_CHARACTERS + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
