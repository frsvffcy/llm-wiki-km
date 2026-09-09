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
        // Issue #283: the FTS rebuild admission contract never trades atomicity for waiting;
        // this timeout only bounds SQLite's lock wait and is never a race fix.
        if (busyTimeout <= 0) {
            throw new IllegalArgumentException("SQLite busy timeout must be greater than zero");
        }
        this.busyTimeout = busyTimeout;
    }
}
