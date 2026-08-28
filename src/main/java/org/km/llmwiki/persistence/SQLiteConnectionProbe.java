package org.km.llmwiki.persistence;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class SQLiteConnectionProbe {

    private final DSLContext dsl;

    public SQLiteConnectionProbe(DSLContext dsl) {
        this.dsl = dsl;
    }

    public boolean isReachable() {
        Integer result = dsl.selectOne().fetchOne(0, Integer.class);
        return result != null && result == 1;
    }
}
