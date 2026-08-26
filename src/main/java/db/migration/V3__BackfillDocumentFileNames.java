package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class V3__BackfillDocumentFileNames extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();

        List<long[]> pendingIds = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT id FROM document
                     WHERE original_file_name IS NULL OR extension IS NULL
                     """)) {
            while (rows.next()) {
                pendingIds.add(new long[] {rows.getLong(1)});
            }
        }

        try (PreparedStatement select = connection.prepareStatement(
                     "SELECT file_name, original_file_name FROM document WHERE id = ?");
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE document SET original_file_name = ?, extension = ? WHERE id = ?")) {
            for (long[] idHolder : pendingIds) {
                long id = idHolder[0];
                String fileName = null;
                String storedOriginal = null;
                select.setLong(1, id);
                try (ResultSet row = select.executeQuery()) {
                    if (row.next()) {
                        fileName = row.getString("file_name");
                        storedOriginal = row.getString("original_file_name");
                    }
                }
                if (fileName == null) {
                    continue;
                }
                update.setString(1, storedOriginal != null ? storedOriginal : fileName);
                update.setString(2, normalizeExtension(fileName));
                update.setLong(3, id);
                update.executeUpdate();
            }
        }
    }

    /**
     * Migration-local, immutable copy of the extension normalization rules that were in effect
     * when this migration was published (strip leading dot position rules; lowercase; null when
     * the file name has no usable extension). Intentionally independent from domain utilities.
     */
    private static String normalizeExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }
}
