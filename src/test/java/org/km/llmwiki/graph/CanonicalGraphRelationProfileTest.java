package org.km.llmwiki.graph;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("contract")
class CanonicalGraphRelationProfileTest {

    @Test
    void vocabularyDoesNotImplyProductionAdmission() {
        assertThat(GraphProjectionVersion.current().value()).isEqualTo("graph-projection-v2");
        assertThat(GraphProjectionVersion.legacyV1()).isNotEqualTo(GraphProjectionVersion.current());
        assertThat(GraphProjectionVersion.current()
                .permitsMigrationFrom(GraphProjectionVersion.legacyV1())).isTrue();
        assertThat(GraphProjectionVersion.legacyV1()
                .permitsMigrationFrom(GraphProjectionVersion.current())).isFalse();
        assertThat(new GraphProjectionVersion("graph-projection-v3")
                .permitsMigrationFrom(GraphProjectionVersion.current())).isFalse();
        assertThat(GraphRelationType.values()).contains(GraphRelationType.MENTIONS, GraphRelationType.RELATED_TO);
        assertThat(GraphRelationType.values()).allSatisfy(type ->
                assertThat(CanonicalGraphRelationProfile.decision(type)).isNotNull());
        assertThat(GraphRelationType.values()).filteredOn(CanonicalGraphRelationProfile::admits)
                .containsExactlyInAnyOrder(GraphRelationType.CONTAINS, GraphRelationType.LINKS_TO,
                        GraphRelationType.TAGGED_WITH, GraphRelationType.DERIVED_FROM);
        assertThat(CanonicalGraphRelationProfile.decision(GraphRelationType.MENTIONS))
                .isEqualTo(CanonicalGraphRelationProfile.Decision.NO_GO);
        assertThat(CanonicalGraphRelationProfile.decision(GraphRelationType.RELATED_TO))
                .isEqualTo(CanonicalGraphRelationProfile.Decision.DEFER);
    }
}
