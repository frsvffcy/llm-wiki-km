package org.km.llmwiki.source;

import org.springframework.stereotype.Component;

/**
 * Baseline normalization policy (#412 rollback target).
 *
 * <p>Preserves the pre-#412 production semantics exactly: syntax-only cleanup
 * (line endings, NFC, ISO-Control removal, trailing whitespace, repeated edges,
 * blank-line collapse) with every Unicode format character left intact. This
 * version exists so a policy rollback is a version change only — historical
 * bytes are restored solely through explicit re-extraction, never by rewriting
 * stored rows in place.
 */
@Component
public class NormalizationPolicyV1Current implements NormalizationPolicy {

    public static final String VERSION = "normalization-policy-v1-current";

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public String apply(String content) {
        return content;
    }
}
