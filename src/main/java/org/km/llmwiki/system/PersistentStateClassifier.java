package org.km.llmwiki.system;

import java.util.Map;
import java.util.Set;

/**
 * Executable persistent-state classification (#418 §F).
 *
 * <p>Authoritative state must be restored to reopen the system; rebuildable
 * state is a derived projection that can be deleted and rebuilt and must never
 * be mistaken for backup authority. FTS5, vector, and graph projections are
 * always rebuildable: a backup that contains only derived state is never
 * complete, and a restore must rebuild them before serving instead of letting
 * stale projections fake readiness. Secrets have no logical name here on
 * purpose: secret material must never enter an ordinary backup artifact.
 */
public final class PersistentStateClassifier {

    public enum StateClass {
        AUTHORITATIVE,
        REBUILDABLE
    }

    public record Classification(StateClass stateClass, boolean backupRequired, String note) {
    }

    public static final String VAULT = "vault";
    public static final String ARCHIVE = "archive";
    public static final String KNOWLEDGE_DB = "knowledge.db";
    public static final String WORKSPACE_STATE = "workspace-state";
    public static final String DEPLOYMENT_CONFIG = "deployment-config";
    public static final String FTS5_PROJECTION = "fts5-projection";
    public static final String VECTOR_PROJECTION = "sqlite-vec-projection";
    public static final String GRAPH_PROJECTION = "arcadedb-graph-projection";
    public static final String DERIVED_CACHE = "derived-index-cache";
    public static final String LOGS = "logs";
    public static final String TEMP = "temp";

    private static final Set<String> AUTHORITATIVE = Set.of(
            VAULT, ARCHIVE, KNOWLEDGE_DB, WORKSPACE_STATE, DEPLOYMENT_CONFIG);

    private static final Map<String, String> NOTES = Map.ofEntries(
            Map.entry(VAULT, "Canonical Markdown with YAML frontmatter; the only irreplaceable asset."),
            Map.entry(ARCHIVE, "Canonical source archive; required alongside vault."),
            Map.entry(KNOWLEDGE_DB, "SQLite canonical and operational state incl. workspace, proposal,"
                    + " draft, publish, and migration records."),
            Map.entry(WORKSPACE_STATE, "Active-workspace, layout, and governance state derived from"
                    + " the database and workspace directories."),
            Map.entry(DEPLOYMENT_CONFIG, "Required deployment configuration without secret material."),
            Map.entry(FTS5_PROJECTION, "Derived lexical projection; recreate on restore, never a backup authority."),
            Map.entry(VECTOR_PROJECTION, "Derived embedding projection; rebuild from canonical content."),
            Map.entry(GRAPH_PROJECTION, "Derived graph projection; rebuild from canonical input."),
            Map.entry(DERIVED_CACHE, "Derived indexes and caches; safe to delete and rebuild."),
            Map.entry(LOGS, "Disposable operator logs; rotation only, never backup authority."),
            Map.entry(TEMP, "Disposable scratch space; never backup authority."));

    private PersistentStateClassifier() {
    }

    /**
     * Classifies one logical state name. Unknown names fail closed so a new
     * persistent kind can never silently inherit the wrong backup semantics.
     */
    public static Classification classify(String logicalName) {
        if (logicalName == null || !NOTES.containsKey(logicalName)) {
            throw new IllegalArgumentException(
                    "Unknown persistent state; classify it explicitly before backup or restore");
        }
        if (AUTHORITATIVE.contains(logicalName)) {
            return new Classification(StateClass.AUTHORITATIVE, true, NOTES.get(logicalName));
        }
        return new Classification(StateClass.REBUILDABLE, false, NOTES.get(logicalName));
    }

    public static boolean isBackupRequired(String logicalName) {
        return classify(logicalName).backupRequired();
    }
}
