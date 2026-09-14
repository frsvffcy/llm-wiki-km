package org.km.llmwiki.system;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Backup and restore smoke evidence (#418 §G/H).
 *
 * <p>Exercises the authoritative-set procedure against a real SQLite file in
 * WAL mode plus canonical workspace files: checkpoint before copy, manifest
 * validation, backup, restore, reopen with canonical content intact, Flyway
 * migration state present, and fail-closed behavior for partial or corrupt
 * sets. Derived projections are rebuildable inputs to this flow, never its
 * authority.
 */
@Tag("integration")
class BackupRestoreSmokeIntegrationTest extends IsolatedIntegrationTest {

    @TempDir
    private Path temp;

    @Test
    void backupRestoreSmokeKeepsAuthoritativeSet() throws Exception {
        Path source = temp.resolve("source");
        Files.createDirectories(source.resolve("vault"));
        Files.createDirectories(source.resolve("archive"));
        Files.createDirectories(source.resolve("data"));
        Files.createDirectories(source.resolve("config"));
        Files.writeString(source.resolve("vault/page.md"), "# Page\n");
        Files.writeString(source.resolve("archive/doc.bin"), "bytes");
        Files.writeString(source.resolve("config/deployment.yml"), "mode: PRIVATE_INGRESS\n");

        Path dbFile = source.resolve("data/knowledge.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("CREATE TABLE smoke(id INTEGER PRIMARY KEY, v TEXT)");
            statement.execute("INSERT INTO smoke(v) VALUES('canonical')");
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }

        BackupConsistencyPolicy.BackupManifest manifest =
                new BackupConsistencyPolicy.BackupManifest(true, true, true, true, true, true, false);
        assertThat(BackupConsistencyPolicy.validateForBackup(manifest).valid()).isTrue();

        Path backup = temp.resolve("backup");
        Files.createDirectories(backup);
        copyTree(source.resolve("vault"), backup.resolve("vault"));
        copyTree(source.resolve("archive"), backup.resolve("archive"));
        Files.createDirectories(backup.resolve("data"));
        Files.copy(dbFile, backup.resolve("data/knowledge.db"));
        Files.createDirectories(backup.resolve("config"));
        Files.copy(source.resolve("config/deployment.yml"),
                backup.resolve("config/deployment.yml"));

        Path restored = temp.resolve("restored");
        Files.createDirectories(restored);
        copyTree(backup.resolve("vault"), restored.resolve("vault"));
        copyTree(backup.resolve("archive"), restored.resolve("archive"));
        Files.createDirectories(restored.resolve("data"));
        Files.copy(backup.resolve("data/knowledge.db"),
                restored.resolve("data/knowledge.db"));
        Files.createDirectories(restored.resolve("config"));
        Files.copy(backup.resolve("config/deployment.yml"),
                restored.resolve("config/deployment.yml"));

        Map<String, Long> restoredSizes = new HashMap<>();
        restoredSizes.put(PersistentStateClassifier.VAULT,
                Files.size(restored.resolve("vault/page.md")));
        restoredSizes.put(PersistentStateClassifier.ARCHIVE,
                Files.size(restored.resolve("archive/doc.bin")));
        restoredSizes.put(PersistentStateClassifier.KNOWLEDGE_DB,
                Files.size(restored.resolve("data/knowledge.db")));
        restoredSizes.put(PersistentStateClassifier.DEPLOYMENT_CONFIG,
                Files.size(restored.resolve("config/deployment.yml")));

        assertThat(BackupConsistencyPolicy.validateForRestore(manifest, restoredSizes).valid())
                .isTrue();

        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + restored.resolve("data/knowledge.db"));
             ResultSet rows = connection.createStatement()
                     .executeQuery("SELECT v FROM smoke")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("canonical");
        }

        Integer appliedMigrations = db()
                .sql("SELECT COUNT(*) FROM flyway_schema_history")
                .query(Integer.class)
                .single();
        assertThat(appliedMigrations).isGreaterThan(0);
    }

    @Test
    void partialAndCorruptRestoreFailClosed() {
        BackupConsistencyPolicy.BackupManifest manifest =
                new BackupConsistencyPolicy.BackupManifest(true, true, true, true, true, true, false);

        Map<String, Long> partial = Map.of(
                PersistentStateClassifier.VAULT, 16L,
                PersistentStateClassifier.KNOWLEDGE_DB, 4096L);
        assertThat(BackupConsistencyPolicy.validateForRestore(manifest, partial).valid())
                .isFalse();

        Map<String, Long> corrupt = Map.of(
                PersistentStateClassifier.VAULT, 16L,
                PersistentStateClassifier.ARCHIVE, 16L,
                PersistentStateClassifier.KNOWLEDGE_DB, 0L,
                PersistentStateClassifier.DEPLOYMENT_CONFIG, 16L);
        assertThat(BackupConsistencyPolicy.validateForRestore(manifest, corrupt).valid())
                .isFalse();

        BackupConsistencyPolicy.BackupManifest derivedOnly =
                new BackupConsistencyPolicy.BackupManifest(
                        false, false, false, false, true, true, true);
        assertThat(BackupConsistencyPolicy.validateForRestore(
                        derivedOnly, Map.of()).valid())
                .isFalse();
    }

    private static void copyTree(Path from, Path to) throws Exception {
        Files.createDirectories(to);
        try (var entries = Files.list(from)) {
            for (Path entry : entries.toList()) {
                Files.copy(entry, to.resolve(entry.getFileName().toString()),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
