package org.km.llmwiki.persistence.jooq;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.CoreMigrationType;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.Configuration;
import org.jooq.meta.jaxb.Database;
import org.jooq.meta.jaxb.Generate;
import org.jooq.meta.jaxb.Generator;
import org.jooq.meta.jaxb.Jdbc;
import org.jooq.meta.jaxb.Target;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Utility to run Flyway migrations on a temporary SQLite DB and trigger jOOQ code generation.
 *
 * <p>This ensures clean, reproducible code generation from the exact set of published SQL and
 * Java migrations without relying on any developer's local runtime database.
 */
public final class JooqCodeGenerator {

    private static final String DEFAULT_TARGET_DIR = "target/generated-sources/jooq";
    private static final String TARGET_PACKAGE = "org.km.llmwiki.persistence.jooq.generated";

    public static void main(String[] args) throws Exception {
        String targetDir = args.length > 0 ? args[0] : DEFAULT_TARGET_DIR;
        generate(Path.of(targetDir));
    }

    public static void generate(Path targetDir) throws Exception {
        Path tempDir = Path.of("target/jooq-codegen");
        Files.createDirectories(tempDir);
        Path tempDb = tempDir.resolve("schema.db").toAbsolutePath();
        Files.deleteIfExists(tempDb);

        String jdbcUrl = "jdbc:sqlite:" + tempDb;

        // 1. Run all published Flyway migrations (SQL + Java) on the fresh SQLite DB
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, "", "")
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
        requireJavaMigrationV3(flyway);

        // 2. Run jOOQ GenerationTool
        Configuration configuration = new Configuration()
                .withJdbc(new Jdbc()
                        .withDriver("org.sqlite.JDBC")
                        .withUrl(jdbcUrl))
                .withGenerator(new Generator()
                        .withName("org.jooq.codegen.JavaGenerator")
                        .withDatabase(new Database()
                                .withName("org.jooq.meta.sqlite.SQLiteDatabase")
                                .withIncludes(".*")
                                .withExcludes("flyway_schema_history|sqlite_sequence"))
                        .withGenerate(new Generate()
                                .withRecords(true)
                                .withPojos(false)
                                .withDaos(false)
                                .withImplicitJoinPathsToOne(false))
                        .withTarget(new Target()
                                .withPackageName(TARGET_PACKAGE)
                                .withDirectory(targetDir.toAbsolutePath().toString())
                                .withClean(true)));

        GenerationTool.generate(configuration);
    }

    private static void requireJavaMigrationV3(Flyway flyway) {
        for (MigrationInfo migration : flyway.info().applied()) {
            if (migration.getVersion() != null
                    && "3".equals(migration.getVersion().getVersion())
                    && CoreMigrationType.JDBC.equals(migration.getType())
                    && MigrationState.SUCCESS.equals(migration.getState())) {
                return;
            }
        }
        throw new IllegalStateException("Flyway Java migration V3 was not applied successfully");
    }
}
