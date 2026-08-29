package org.km.llmwiki.wiki;

/** Stable result taxonomy shared by CREATE, MERGE, and the publish attempt audit. */
public enum WikiPublishResultType {
    PUBLISHED,
    CONFLICT,
    FAILED,
    NO_OP
}
