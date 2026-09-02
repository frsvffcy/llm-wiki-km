package org.km.llmwiki.search.vector.sqlite;

import org.km.llmwiki.search.vector.VectorExtensionLoadStatus;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** The only class that knows the Xerial/SQLite native extension loading SQL. */
final class SqliteNativeExtensionLoader implements VectorExtensionLoader {

    @Override
    public LoadResult load(Connection connection, Path extensionPath) {
        String escapedPath = extensionPath.toString().replace("'", "''");
        try (Statement statement = connection.createStatement()) {
            statement.execute("SELECT load_extension('" + escapedPath + "')");
            return new LoadResult(VectorExtensionLoadStatus.LOADED, "Native extension loaded");
        } catch (SQLException exception) {
            return new LoadResult(VectorExtensionLoadStatus.FAILED,
                    "Native extension could not be loaded");
        }
    }
}
