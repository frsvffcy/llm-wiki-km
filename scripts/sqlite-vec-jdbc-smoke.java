import org.sqlite.SQLiteConfig;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Minimal Java/Xerial/sqlite-vec loading and nearest-neighbour smoke.
 * Compile/run through sqlite-vec-jdbc-smoke.sh; this source is not application code.
 */
class SqliteVecJdbcSmoke {

    private static final String EXPECTED_VERSION = "v0.1.9";

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Usage: SqliteVecJdbcSmoke <path-to-vec0.so-or-dylib>");
        }
        Path extensionPath = Path.of(arguments[0]).toAbsolutePath().normalize();
        String escapedPath = extensionPath.toString().replace("'", "''");

        SQLiteConfig config = new SQLiteConfig();
        config.enableLoadExtension(true);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:",
                config.toProperties());
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT load_extension('" + escapedPath + "')");
            String version = queryString(statement, "SELECT vec_version()");
            if (!EXPECTED_VERSION.equals(version)) {
                throw new IllegalStateException("Unexpected sqlite-vec version: " + version);
            }
            if (queryInt(statement,
                    "SELECT EXISTS (SELECT 1 FROM pragma_module_list WHERE name = 'vec0')") != 1) {
                throw new IllegalStateException("vec0 module was not registered");
            }
            String sqliteVersion = queryString(statement, "SELECT sqlite_version()");

            statement.execute("CREATE VIRTUAL TABLE vec_items USING vec0(embedding float[3])");
            statement.execute("INSERT INTO vec_items(rowid, embedding) "
                    + "VALUES (1, '[1.0, 0.0, 0.0]')");
            try (ResultSet nearest = statement.executeQuery("SELECT rowid, distance FROM vec_items "
                    + "WHERE embedding MATCH '[1.0, 0.0, 0.0]' AND k = 1")) {
                if (!nearest.next() || nearest.getLong("rowid") != 1L
                        || Double.compare(nearest.getDouble("distance"), 0.0d) != 0) {
                    throw new IllegalStateException("Unexpected nearest-neighbour result");
                }
                System.out.printf("sqlite-vec smoke: java=%s xerial=%s sqlite=%s version=%s "
                                + "module=vec0 nearestRowId=%d distance=%s%n",
                        Runtime.version().feature(), connection.getMetaData().getDriverVersion(),
                        sqliteVersion, version,
                        nearest.getLong("rowid"), nearest.getDouble("distance"));
            }
        }
    }

    private static String queryString(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                throw new IllegalStateException("Probe returned no value: " + sql);
            }
            return result.getString(1);
        }
    }

    private static int queryInt(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                throw new IllegalStateException("Probe returned no value: " + sql);
            }
            return result.getInt(1);
        }
    }
}
