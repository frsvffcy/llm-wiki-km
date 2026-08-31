package org.km.llmwiki.persistence;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.Database;
import org.jooq.meta.jaxb.Generate;
import org.jooq.meta.jaxb.Generator;
import org.jooq.meta.jaxb.Jdbc;
import org.jooq.meta.jaxb.Target;
import org.km.llmwiki.config.SQLiteProperties;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.jooq.impl.DSL.val;

@Import(JooqSQLitePersistenceSpikeTest.TransactionTestConfiguration.class)
class JooqSQLitePersistenceSpikeTest extends IsolatedIntegrationTest {

    private static final Table<Record> ITEM = table(name("jooq_spike_item"));
    private static final Field<Long> ITEM_ID = field(name("id"), Long.class);
    private static final Field<String> ITEM_KEY = field(name("item_key"), String.class);
    private static final Field<String> ITEM_VALUE = field(name("item_value"), String.class);

    @Autowired
    private DSLContext dsl;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private TransactionalProbe transactionalProbe;

    @Autowired
    private SQLiteProperties sqliteProperties;

    @BeforeEach
    void resetSpikeTables() {
        dsl.execute("""
                CREATE TABLE IF NOT EXISTS jooq_spike_item (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    item_key TEXT NOT NULL UNIQUE,
                    item_value TEXT NOT NULL
                )
                """);
        dsl.execute("""
                CREATE VIRTUAL TABLE IF NOT EXISTS jooq_spike_fts USING fts5(
                    title,
                    content
                )
                """);
        dsl.deleteFrom(ITEM).execute();
        dsl.execute("DELETE FROM jooq_spike_fts");
    }

    @Test
    void supportsCrudGeneratedKeyAndReturning() {
        Long id = dsl.insertInto(ITEM)
                .columns(ITEM_KEY, ITEM_VALUE)
                .values("alpha", "first")
                .returningResult(ITEM_ID)
                .fetchOne(ITEM_ID);

        assertThat(id).isPositive();
        assertThat(dsl.select(ITEM_VALUE).from(ITEM).where(ITEM_ID.eq(id)).fetchOne(ITEM_VALUE))
                .isEqualTo("first");

        assertThat(dsl.update(ITEM).set(ITEM_VALUE, "updated").where(ITEM_ID.eq(id)).execute())
                .isEqualTo(1);
        assertThat(dsl.deleteFrom(ITEM).where(ITEM_ID.eq(id)).execute()).isEqualTo(1);
        assertThat(dsl.fetchCount(ITEM)).isZero();
    }

    @Test
    void participatesInSpringManagedTransaction() {
        assertThatThrownBy(transactionalProbe::writeWithJooqAndFail)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("rollback probe");

        assertThat(dsl.fetchCount(ITEM)).isZero();
    }

    @Test
    void supportsSqliteUpsertWithDsl() {
        dsl.insertInto(ITEM)
                .columns(ITEM_KEY, ITEM_VALUE)
                .values("same-key", "before")
                .execute();

        dsl.insertInto(ITEM)
                .columns(ITEM_KEY, ITEM_VALUE)
                .values("same-key", "after")
                .onConflict(ITEM_KEY)
                .doUpdate()
                .set(ITEM_VALUE, excluded(ITEM_VALUE))
                .execute();

        assertThat(dsl.select(ITEM_VALUE).from(ITEM).where(ITEM_KEY.eq("same-key")).fetchOne(ITEM_VALUE))
                .isEqualTo("after");
    }

    @Test
    void supportsCteAndRecursiveCte() {
        var seed = name("seed").fields("value").as(
                dsl.select(inline(21).as("value")));
        Integer doubled = dsl.with(seed)
                .select(field(name("seed", "value"), Integer.class).mul(2))
                .from(seed)
                .fetchOne(0, Integer.class);

        Integer recursiveSum = dsl.resultQuery("""
                        WITH RECURSIVE sequence(value) AS (
                            SELECT {0}
                            UNION ALL
                            SELECT value + {1} FROM sequence WHERE value < {2}
                        )
                        SELECT SUM(value) FROM sequence
                        """, inline(1), inline(1), val(5))
                .fetchOne(0, Integer.class);

        assertThat(doubled).isEqualTo(42);
        assertThat(recursiveSum).isEqualTo(15);
    }

    @Test
    void supportsSqliteJsonFunctionThroughPlainSqlTemplate() {
        String title = dsl.select(field("json_extract({0}, {1})", String.class,
                        val("{\"title\":\"SQLite\"}"), val("$.title")))
                .fetchOne(0, String.class);

        assertThat(title).isEqualTo("SQLite");
    }

    @Test
    void supportsFts5MatchRankingBindingAndResultMappingThroughFallback() {
        dsl.execute("INSERT INTO jooq_spike_fts(title, content) VALUES ({0}, {1})",
                val("jOOQ persistence"), val("SQLite persistence with jOOQ and Spring transactions"));
        dsl.execute("INSERT INTO jooq_spike_fts(title, content) VALUES ({0}, {1})",
                val("JdbcClient baseline"), val("Readable SQL without generated sources"));

        var results = dsl.resultQuery("""
                        SELECT title, bm25(jooq_spike_fts) AS rank
                        FROM jooq_spike_fts
                        WHERE jooq_spike_fts MATCH {0}
                        ORDER BY rank
                        """, val("persistence"))
                .fetch(record -> new RankedTitle(record.get("title", String.class),
                        record.get("rank", Double.class)));

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().title()).isEqualTo("jOOQ persistence");
        assertThat(results.getFirst().rank()).isNegative();
    }

    @Test
    void generatesTypedSourcesFromFlywayReconstructedSqliteSchema() throws Exception {
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM flyway_schema_history
                        WHERE version = '3' AND type = 'JDBC' AND success = 1
                        """)
                .query(Integer.class).single()).isEqualTo(1);

        Path outputDirectory = sqliteProperties.getPath().getParent().resolve("generated-sources");
        GenerationTool.generate(new org.jooq.meta.jaxb.Configuration()
                .withJdbc(new Jdbc()
                        .withDriver("org.sqlite.JDBC")
                        .withUrl("jdbc:sqlite:" + sqliteProperties.getPath().toAbsolutePath()))
                .withGenerator(new Generator()
                        .withName("org.jooq.codegen.JavaGenerator")
                        .withDatabase(new Database()
                                .withName("org.jooq.meta.sqlite.SQLiteDatabase")
                                .withIncludes("workspace|document")
                                .withExcludes("flyway_schema_history|sqlite_sequence|jooq_spike_.*"))
                        .withGenerate(new Generate()
                                .withRecords(true)
                                .withPojos(false)
                                .withDaos(false))
                        .withTarget(new Target()
                                .withPackageName("org.km.llmwiki.persistence.jooq.generated")
                                .withDirectory(outputDirectory.toString()))));

        Path generatedDocument = outputDirectory.resolve(
                "org/km/llmwiki/persistence/jooq/generated/tables/Document.java");
        assertThat(generatedDocument).isRegularFile();
        assertThat(Files.readString(generatedDocument))
                .contains("class Document")
                .contains("ORIGINAL_FILE_NAME")
                .contains("EXTENSION");
    }

    private record RankedTitle(String title, double rank) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TransactionTestConfiguration {

        @Bean
        TransactionalProbe transactionalProbe(DSLContext dsl, JdbcClient jdbcClient) {
            return new TransactionalProbe(dsl, jdbcClient);
        }
    }

    static class TransactionalProbe {

        private final DSLContext dsl;
        private final JdbcClient jdbcClient;

        TransactionalProbe(DSLContext dsl, JdbcClient jdbcClient) {
            this.dsl = dsl;
            this.jdbcClient = jdbcClient;
        }

        @Transactional
        void writeWithJooqAndFail() {
            dsl.insertInto(ITEM)
                    .columns(ITEM_KEY, ITEM_VALUE)
                    .values("rolled-back", "same datasource transaction")
                    .execute();
            assertThat(jdbcClient.sql("SELECT COUNT(*) FROM jooq_spike_item")
                    .query(Integer.class).single()).isEqualTo(1);
            throw new IllegalStateException("rollback probe");
        }
    }
}
