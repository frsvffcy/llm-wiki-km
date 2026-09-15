package org.km.llmwiki.ai;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class AnalysisFailureCodeTest {

    @Test
    void preservesTypedPromptSubtypesInsteadOfFlatteningToTheUmbrella() {
        assertThat(AnalysisFailureCode.fromPromptError(PromptLoadErrorCode.PROMPT_TEMPLATE_NOT_FOUND))
                .isEqualTo(AnalysisFailureCode.PROMPT_TEMPLATE_NOT_FOUND);
        assertThat(AnalysisFailureCode.fromPromptError(PromptLoadErrorCode.PROMPT_TEMPLATE_INVALID))
                .isEqualTo(AnalysisFailureCode.PROMPT_TEMPLATE_INVALID);
        assertThat(AnalysisFailureCode.fromPromptError(PromptLoadErrorCode.PROMPT_VARIABLE_MISSING))
                .isEqualTo(AnalysisFailureCode.PROMPT_VARIABLE_MISSING);
        assertThat(AnalysisFailureCode.fromPromptError(PromptLoadErrorCode.ANALYSIS_SETTING_INVALID))
                .isEqualTo(AnalysisFailureCode.ANALYSIS_SETTING_INVALID);
    }

    @Test
    void fallsBackToTheUmbrellaCodeOnlyForUnknownPromptFailures() {
        assertThat(AnalysisFailureCode.fromPromptError(null))
                .isEqualTo(AnalysisFailureCode.PROMPT_CONFIGURATION_FAILED);
    }

    @Test
    void typedPromptFailuresAreNeverRetryEligible() {
        assertThat(AnalysisFailureCode.PROMPT_TEMPLATE_NOT_FOUND.retryEligible()).isFalse();
        assertThat(AnalysisFailureCode.PROMPT_TEMPLATE_INVALID.retryEligible()).isFalse();
        assertThat(AnalysisFailureCode.PROMPT_VARIABLE_MISSING.retryEligible()).isFalse();
        assertThat(AnalysisFailureCode.ANALYSIS_SETTING_INVALID.retryEligible()).isFalse();
        assertThat(AnalysisFailureCode.PROMPT_CONFIGURATION_FAILED.retryEligible()).isFalse();
    }
}
