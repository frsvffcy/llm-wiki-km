package org.km.llmwiki.ai.answer;

import java.util.regex.Pattern;

/** Fail-closed validation error that never carries a raw provider response. */
public final class GroundedAnswerValidationException extends IllegalArgumentException {

    public static final int MAX_DIAGNOSTIC_LENGTH = 160;
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(api[-_ ]?key|authorization|token|secret|password)\\s*[:=]\\s*"
                    + "(?:bearer\\s+)?[^\\s,;]+"
    );
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)\\bbearer\\s+[^\\s,;]+");

    private final GroundedAnswerValidationErrorCode errorCode;

    public GroundedAnswerValidationException(GroundedAnswerValidationErrorCode errorCode,
                                              String diagnostic) {
        super(sanitize(errorCode, diagnostic));
        this.errorCode = errorCode;
    }

    public GroundedAnswerValidationErrorCode errorCode() {
        return errorCode;
    }

    public AnswerFailureType failureType() {
        return AnswerFailureType.INVALID_PROVIDER_RESPONSE;
    }

    public String publicCode() {
        return failureType().publicCode();
    }

    private static String sanitize(GroundedAnswerValidationErrorCode code, String diagnostic) {
        String value = diagnostic == null ? "" : diagnostic.replaceAll("[\\r\\n\\t]+", " ").trim();
        value = SECRET_ASSIGNMENT.matcher(value).replaceAll("$1=[REDACTED]");
        value = BEARER_TOKEN.matcher(value).replaceAll("Bearer [REDACTED]");
        if (value.length() > MAX_DIAGNOSTIC_LENGTH) {
            value = value.substring(0, MAX_DIAGNOSTIC_LENGTH);
        }
        return "Grounded answer response rejected [" + code + "]: " + value;
    }
}
