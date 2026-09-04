package org.km.llmwiki.graph;

import java.util.regex.Pattern;

/** Safe public failure data that never contains raw backend or canonical content. */
public record GraphProjectionFailure(GraphProjectionFailureType type, String diagnostic) {

    public static final int MAX_DIAGNOSTIC_LENGTH = 256;
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(api[-_ ]?key|authorization|token|secret|password)\\s*[:=]\\s*(?:bearer\\s+)?[^\\s,;]+"
    );
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)\\bbearer\\s+[^\\s,;]+");
    private static final Pattern SECRET_KEY = Pattern.compile("\\bsk-[A-Za-z0-9_-]{8,}\\b");
    private static final Pattern UNSAFE_INTERNAL = Pattern.compile(
            "(?i)(?:jdbc:|bolt:|cypher|\\bgql\\b|sql-pgq|arcadedb|neo4j|ryugraph|bigquery|spanner|"
                    + "(?:/Users/|/home/|/var/|/tmp/)|[A-Za-z]:\\\\|"
                    + "\\bMATCH\\s*\\(|\\bRETURN\\s+\\w|\\bSELECT\\s+.+\\bFROM\\b|"
                    + "\\b(?:MERGE|CREATE|DELETE|INSERT|UPDATE|UPSERT|TRAVERSE)\\s+[(:])"
    );

    public GraphProjectionFailure {
        if (type == null) {
            throw new IllegalArgumentException("Graph projection failure type is required");
        }
        diagnostic = sanitize(diagnostic);
    }

    public static GraphProjectionFailure of(GraphProjectionFailureType type) {
        return new GraphProjectionFailure(type, type.publicCode());
    }

    public String publicCode() {
        return type.publicCode();
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "graph projection operation failed";
        }
        String sanitized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        sanitized = SECRET_ASSIGNMENT.matcher(sanitized).replaceAll("$1=[REDACTED]");
        sanitized = BEARER_TOKEN.matcher(sanitized).replaceAll("Bearer [REDACTED]");
        sanitized = SECRET_KEY.matcher(sanitized).replaceAll("[REDACTED]");
        if (UNSAFE_INTERNAL.matcher(sanitized).find()) {
            return "graph projection operation failed";
        }
        if (sanitized.length() > MAX_DIAGNOSTIC_LENGTH) {
            sanitized = sanitized.substring(0, MAX_DIAGNOSTIC_LENGTH);
        }
        return sanitized.isBlank() ? "graph projection operation failed" : sanitized;
    }
}
