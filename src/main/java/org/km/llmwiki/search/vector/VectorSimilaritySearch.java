package org.km.llmwiki.search.vector;

import java.util.List;

/** Extension-neutral boundary for storage-level bounded KNN by normalized similarity. */
public interface VectorSimilaritySearch {

    List<VectorSimilarityMatch> findNearest(VectorSimilarityQuery query);
}
