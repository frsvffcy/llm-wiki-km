package com.frsvffcy.llmwiki.config;

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
        if (busyTimeout < 0) {
            throw new IllegalArgumentException("SQLite busy timeout must not be negative");
        }
        this.busyTimeout = busyTimeout;
    }
}
