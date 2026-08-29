package org.km.llmwiki.wiki;

/** Common REST result contract for explicit CREATE and MERGE publish actions. */
public sealed interface WikiPublishResult permits WikiCreatePublishResponse, WikiMergePublishResponse {
    WikiPublishOutcome outcome();
}
