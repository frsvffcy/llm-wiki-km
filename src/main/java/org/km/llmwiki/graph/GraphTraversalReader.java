package org.km.llmwiki.graph;

/** Provider-neutral read port for bounded graph traversal. */
public interface GraphTraversalReader {

    GraphTraversalResult traverse(GraphTraversalQuery query);
}
