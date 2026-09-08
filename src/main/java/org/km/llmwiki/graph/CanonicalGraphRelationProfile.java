package org.km.llmwiki.graph;

import java.util.EnumMap;
import java.util.Map;

/** Production canonical profile v2 的明確 relation admission policy。 */
public final class CanonicalGraphRelationProfile {

    public enum Decision { GO, NO_GO, DEFER }

    private static final Map<GraphRelationType, Decision> DECISIONS;

    static {
        var decisions = new EnumMap<GraphRelationType, Decision>(GraphRelationType.class);
        decisions.put(GraphRelationType.CONTAINS, Decision.GO);
        decisions.put(GraphRelationType.LINKS_TO, Decision.GO);
        decisions.put(GraphRelationType.TAGGED_WITH, Decision.GO);
        decisions.put(GraphRelationType.DERIVED_FROM, Decision.GO);
        decisions.put(GraphRelationType.MENTIONS, Decision.NO_GO);
        decisions.put(GraphRelationType.RELATED_TO, Decision.DEFER);
        DECISIONS = Map.copyOf(decisions);
    }

    private CanonicalGraphRelationProfile() { }

    public static Decision decision(GraphRelationType type) {
        if (type == null) throw new IllegalArgumentException("Graph relation type is required");
        return DECISIONS.get(type);
    }

    public static boolean admits(GraphRelationType type) {
        return decision(type) == Decision.GO;
    }
}
