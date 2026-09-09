package org.km.llmwiki.web;

import java.util.regex.Pattern;

/**
 * The single application-owned diagnostic redaction policy for anything that crosses a
 * persistence or REST boundary: operator-safe persisted diagnostics, public REST error
 * messages, and safe failure projections on read-only health surfaces.
 *
 * <p>The policy is bounded, deterministic, and locale-independent. Exception class names,
 * nested cause chains, stack details, filesystem paths, secret-like assignments, bearer
 * tokens, ArcadeDB-style record identities, and SQL/backend markers never leave a
 * {@link #sanitize} call: secret-like and identity-like content is replaced by a
 * {@code [REDACTED]} token, and a message that still carries an unsafe internal marker
 * collapses entirely to the caller-provided fallback. Raw exceptions and their cause chains
 * stay server-side in the normal application log boundary.
 */
public final class DiagnosticRedaction {

    /** Hard upper bound for any diagnostic that leaves a persistence or REST boundary. */
    public static final int MAX_LENGTH = 256;

    private static final String REDACTED = "[REDACTED]";

    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\r\\n\\t]+");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(api[-_ ]?key|authorization|token|secret|password)\\s*[:=]\\s*(?:bearer\\s+)?[^\\s,;]+"
    );
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)\\bbearer\\s+[^\\s,;]+");
    private static final Pattern SECRET_KEY = Pattern.compile("\\bsk-[A-Za-z0-9_-]{8,}\\b");
    private static final Pattern BACKEND_IDENTITY_TOKEN = Pattern.compile(
            "(?i)(?:\\bRID\\s*#?\\s*\\d+:\\d+)|#\\d+:\\d+"
    );
    private static final Pattern BEARER_JWT = Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{4,}\\b"
    );
    private static final Pattern URL_ENCODED_PATH = Pattern.compile(
            "%2F[A-Za-z0-9._@%-]+%2F[A-Za-z0-9._@%-]*"
    );
    private static final Pattern POSIX_ABSOLUTE_PATH = Pattern.compile(
            "(^|[\\s:=(\"'])/(?:[A-Za-z0-9._@-]+/)+[A-Za-z0-9._@-]*"
    );
    private static final Pattern WELL_KNOWN_PATH_ROOT = Pattern.compile(
            "(^|[\\s:=(\"'])/(?:Users|home|var|tmp|private|opt|usr|etc|srv)(?=$|[\\s/.,:;)\"])"
    );
    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile(
            "[A-Za-z]:\\\\[^\s\"',;]*"
    );
    private static final Pattern UNSAFE_INTERNAL = Pattern.compile(
            "(?i)(?:jdbc:|bolt:|sqlite|\\bgql\\b|sql-pgq|arcadedb|neo4j|ryugraph|bigquery|spanner|"
                    + "\\bMATCH\\s*\\(|\\bRETURN\\s+\\w|\\bSELECT\\s+.+\\bFROM\\b|"
                    + "\\bINSERT\\s+INTO\\b|\\bDELETE\\s+FROM\\b|\\bUPDATE\\s+\\w+\\s+SET\\b|"
                    + "\\bCREATE\\s+TRIGGER\\b|\\bDROP\\s+(?:TABLE|TRIGGER|INDEX)\\b|"
                    + "\\b(?:MERGE|DELETE|INSERT|UPDATE|UPSERT|TRAVERSE)\\s+[(:]|"
                    + "\\bat\\s+[\\w.$]+\\(|"
                    + "\\b[a-z][a-z0-9_]*(?:\\.[a-z0-9_]+)+\\.[A-Z][\\w$]*Exception\\b|"
                    + "\\{\\\"(?:error|type|code|message|status)\\\"|"
                    + "\\bexception\\b|stack\\s*trace|caused\\s+by\\b)"
    );

    private DiagnosticRedaction() {
    }

    /** Public REST message projection with the default length bound. */
    public static String publicMessage(String raw, String fallback) {
        return sanitize(raw, fallback, MAX_LENGTH);
    }

    /**
     * Operator-safe persisted failure summary: a stable application-owned reason followed by
     * the sanitized top-level message only. The exception class name and the nested cause
     * chain are deliberately never included; the full cause stays server-side in the log.
     */
    public static String persistedFailure(String stableReason, Throwable failure,
                                          String fallback) {
        String message = failure == null ? null : failure.getMessage();
        return stableReason + ": " + sanitize(message, fallback, MAX_LENGTH);
    }

    /** Redacts, bounds, and — when an unsafe internal marker survives — falls back entirely. */
    public static String sanitize(String raw, String fallback, int maxLength) {
        if (maxLength < 1) {
            throw new IllegalArgumentException("Diagnostic length bound must be positive");
        }
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String sanitized = CONTROL_CHARACTERS.matcher(raw).replaceAll(" ").trim();
        sanitized = SECRET_ASSIGNMENT.matcher(sanitized).replaceAll("$1=" + REDACTED);
        sanitized = BEARER_TOKEN.matcher(sanitized).replaceAll("Bearer " + REDACTED);
        sanitized = BEARER_JWT.matcher(sanitized).replaceAll(REDACTED);
        sanitized = SECRET_KEY.matcher(sanitized).replaceAll(REDACTED);
        sanitized = BACKEND_IDENTITY_TOKEN.matcher(sanitized).replaceAll(REDACTED);
        sanitized = URL_ENCODED_PATH.matcher(sanitized).replaceAll(REDACTED);
        sanitized = POSIX_ABSOLUTE_PATH.matcher(sanitized).replaceAll("$1" + REDACTED);
        sanitized = WELL_KNOWN_PATH_ROOT.matcher(sanitized).replaceAll("$1" + REDACTED);
        sanitized = WINDOWS_ABSOLUTE_PATH.matcher(sanitized).replaceAll(REDACTED);
        if (UNSAFE_INTERNAL.matcher(sanitized).find()) {
            return fallback;
        }
        if (sanitized.length() > maxLength) {
            sanitized = sanitized.substring(0, maxLength);
        }
        return sanitized.isBlank() ? fallback : sanitized;
    }
}
