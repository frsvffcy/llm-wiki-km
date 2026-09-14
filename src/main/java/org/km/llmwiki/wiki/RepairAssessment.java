package org.km.llmwiki.wiki;

/**
 * Outcome of repair eligibility assessment (#384). Only {@link Eligible} carries a
 * {@link RepairPlan}; every other finding class resolves to a typed
 * {@link RepairRefusalReason} and can only be triaged, never repaired.
 */
public sealed interface RepairAssessment permits RepairAssessment.Eligible, RepairAssessment.Ineligible {

    record Eligible(RepairPlan plan) implements RepairAssessment {
    }

    record Ineligible(RepairRefusalReason reason) implements RepairAssessment {
    }
}
