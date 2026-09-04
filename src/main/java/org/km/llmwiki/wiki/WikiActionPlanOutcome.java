package org.km.llmwiki.wiki;

/** Whether an action plan may proceed toward a main Wiki content write. */
public enum WikiActionPlanOutcome {
    CREATE_MAIN_WIKI,
    MERGE_MAIN_WIKI,
    NO_MAIN_WIKI_WRITE
}
