package org.km.llmwiki.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties("app.persistence.sqlite")
public class SQLiteProperties {

    private Path path = Path.of("data/knowledge.db");
    private int busyTimeout = 5000;

    public Path getPath() {
        return path;
    }

    public void setPath(Path path) {
        this.path = path;
    }

    public int getBusyTimeout() {
        return busyTimeout;
    }

    public void setBusyTimeout(int busyTimeout) {
        // Issue #283: the FTS rebuild admission contract never trades atomicity for waiting,
        // so a zero (or negative) busy timeout must not silently disable SQLite's lock wait.
        // Full configuration validation (non-zero invariant and startup evidence) is tracked
        // as a follow-up in Issue #288; raising the timeout is never a race fix.
        if (busyTimeout < 0) {
            throw new IllegalArgumentException("SQLite busy timeout must not be negative");
        }
        this.busyTimeout = busyTimeout;
    }
}
