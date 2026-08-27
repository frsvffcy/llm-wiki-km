package org.km.llmwiki.ai;

/** Stable failure codes for prompt loading before an LLM provider is contacted. */
public enum PromptLoadErrorCode {
    PROMPT_TEMPLATE_NOT_FOUND,
    PROMPT_TEMPLATE_INVALID,
    PROMPT_VARIABLE_MISSING,
    ANALYSIS_SETTING_INVALID
}
