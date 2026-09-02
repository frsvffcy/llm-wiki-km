package org.km.llmwiki.search.vector;

import java.util.List;

/** Extension-neutral boundary for ranking bounded candidate vectors by normalized similarity. */
public interface VectorSimilaritySearch {

    List<VectorSimilarityMatch> findNearest(List<Double> queryVector,
                                            List<VectorSimilarityEntry> candidates,
                                            int limit);
}
