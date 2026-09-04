package org.km.llmwiki.graph;

/** Rebuild port for deriving a complete projection from canonical, prevalidated input. */
public interface GraphProjectionRebuilder {

    /**
     * Rebuilds one workspace projection and returns its generation marker.
     *
     * @throws GraphProjectionException when capability, readiness, input, or adapter execution
     *                                  prevents a rebuild; an unavailable adapter must not return
     *                                  a false empty result
     */
    GraphProjectionSnapshot rebuild(GraphProjectionInput input);
}
