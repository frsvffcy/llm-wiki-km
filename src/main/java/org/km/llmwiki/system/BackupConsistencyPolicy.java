package org.km.llmwiki.system;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * WAL-safe backup and restore consistency contract (#418 §G/H).
 *
 * <p>A complete backup is the authoritative set only: {@code vault/},
 * {@code archive/}, the WAL-checkpointed {@code knowledge.db} single file, and
 * required deployment configuration without secrets. Copying the database file
 * alone, copying canonical files alone, or keeping only derived projections
 * never counts as complete. Restore fails closed on partial or corrupt sets,
 * and derived projections (FTS5, vector, graph) must be rebuilt before serving
 * instead of letting stale state fake readiness.
 *
 * <p>All reasons are fixed operator-safe strings: logical names only, never
 * absolute paths, secrets, or backend identities.
 */
public final class BackupConsistencyPolicy {

    public record BackupManifest(
            boolean vaultIncluded,
            boolean archiveIncluded,
            boolean databaseIncluded,
            boolean deploymentConfigIncluded,
            boolean secretsExcluded,
            boolean walCheckpointed,
            boolean derivedOnly) {
    }

    public record BackupValidation(boolean valid, String reason) {
    }

    private static final Set<String> REQUIRED_FILES = Set.of(
            PersistentStateClassifier.VAULT,
            PersistentStateClassifier.ARCHIVE,
            PersistentStateClassifier.KNOWLEDGE_DB,
            PersistentStateClassifier.DEPLOYMENT_CONFIG);

    private BackupConsistencyPolicy() {
    }

    /** Backup-before-write rule: an incomplete or unsafe set is never complete. */
    public static BackupValidation validateForBackup(BackupManifest manifest) {
        if (manifest == null) {
            return invalid("Backup set is missing its manifest.");
        }
        if (!manifest.secretsExcluded()) {
            return invalid("Backup artifact must not contain secret material.");
        }
        if (manifest.derivedOnly()) {
            return invalid("Derived projections are rebuildable and never a complete backup.");
        }
        if (!manifest.vaultIncluded() || !manifest.archiveIncluded()) {
            return invalid("Backup set is missing canonical vault or archive content.");
        }
        if (!manifest.databaseIncluded()) {
            return invalid("Backup set is missing the authoritative database.");
        }
        if (!manifest.deploymentConfigIncluded()) {
            return invalid("Backup set is missing required deployment configuration.");
        }
        if (!manifest.walCheckpointed()) {
            return invalid("SQLite WAL must be checkpointed before copying the database file.");
        }
        return new BackupValidation(true, "Backup set holds the complete authoritative set.");
    }

    /**
     * Restore rule: the manifest must describe a complete set and every
     * required file must be present with a non-empty size. Missing or empty
     * files fail closed; derived projections are rebuilt afterwards, never
     * trusted as restore authority.
     */
    public static BackupValidation validateForRestore(
            BackupManifest manifest, Map<String, Long> restoredFileSizes) {
        BackupValidation manifestCheck = validateForBackup(manifest);
        if (!manifestCheck.valid()) {
            return manifestCheck;
        }
        if (restoredFileSizes == null) {
            return invalid("Restore set is missing its file inventory.");
        }
        Set<String> missing = new TreeSet<>();
        for (String required : REQUIRED_FILES) {
            Long size = restoredFileSizes.get(required);
            if (size == null || size <= 0) {
                missing.add(required);
            }
        }
        if (!missing.isEmpty()) {
            return invalid("Restore set is partial or corrupt; required files are missing.");
        }
        return new BackupValidation(true, "Restore set is complete; rebuild derived projections"
                + " and re-check readiness before serving.");
    }

    private static BackupValidation invalid(String reason) {
        return new BackupValidation(false, reason);
    }
}
