package org.km.llmwiki.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SQLiteConnectionProbe {

    private final JdbcClient jdbcClient;

    public SQLiteConnectionProbe(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public boolean isReachable() {
        Integer result = jdbcClient.sql("SELECT 1").query(Integer.class).single();
        return result == 1;
    }
}
