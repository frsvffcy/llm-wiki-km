package org.km.llmwiki.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FtsMatchQueryTest {

    @Test
    void acceptsTheProjectedTermBudgetAndRejectsTheNextTermDeterministically() {
        String withinBudget = "甲".repeat(FtsMatchQuery.MAX_PROJECTED_TERMS + 1);
        String overBudget = "甲".repeat(FtsMatchQuery.MAX_PROJECTED_TERMS + 2);

        assertThat(FtsMatchQuery.literalExpression(withinBudget).split(" AND "))
                .hasSize(FtsMatchQuery.MAX_PROJECTED_TERMS);
        assertThatThrownBy(() -> FtsMatchQuery.literalExpression(overBudget))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Search query must not contain more than "
                        + FtsMatchQuery.MAX_PROJECTED_TERMS
                        + " terms after projection (projected terms)");
    }

    @Test
    void enforcesRawUnicodeCodePointLimitBeforeProjectionExpansion() {
        String overLimit = "a".repeat(FtsMatchQuery.MAX_QUERY_CODE_POINTS + 1);

        assertThatThrownBy(() -> FtsMatchQuery.literalExpression(overLimit))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Search query must not exceed "
                        + FtsMatchQuery.MAX_QUERY_CODE_POINTS + " Unicode code points");
    }

    @Test
    void stripsAndNfcNormalizesBeforeLiteralProjection() {
        assertThat(FtsMatchQuery.literalExpression("  Cafe\u0301  "))
                .isEqualTo("\"café\"");
        assertThat(FtsMatchQuery.literalExpression("\" OR 1=1 --"))
                .isEqualTo("\"or\" AND \"1\" AND \"1\"");
    }
}
