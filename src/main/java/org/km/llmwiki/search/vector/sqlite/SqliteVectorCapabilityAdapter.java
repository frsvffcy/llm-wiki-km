package org.km.llmwiki.search.vector.sqlite;

import org.km.llmwiki.config.VectorCapabilityProperties;
import org.km.llmwiki.search.vector.VectorAvailability;
import org.km.llmwiki.search.vector.VectorCapability;
import org.km.llmwiki.search.vector.VectorCapabilityFailure;
import org.km.llmwiki.search.vector.VectorCapabilityReport;
import org.km.llmwiki.search.vector.VectorCapabilityRequest;
import org.km.llmwiki.search.vector.VectorEncoding;
import org.km.llmwiki.search.vector.VectorExtensionLoadStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

/**
 * SQLite/Xerial adapter for the provider-neutral capability contract.
 *
 * <p>Native path handling, load SQL, module probing, and extension version checks are deliberately
 * kept here. They are not part of the application/domain contract and are not exposed by a web
 * endpoint in this story.
 */
@Service
public class SqliteVectorCapabilityAdapter implements VectorCapability {

    private static final Set<VectorEncoding> SUPPORTED_ENCODINGS = Set.of(VectorEncoding.FLOAT32);
    private static final String MODULE_NAME = "vec0";

    private final DataSource dataSource;
    private final VectorCapabilityProperties properties;
    private final VectorExtensionLoader extensionLoader;

    @Autowired
    public SqliteVectorCapabilityAdapter(DataSource dataSource,
                                         VectorCapabilityProperties properties) {
        this(dataSource, properties, new SqliteNativeExtensionLoader());
    }

    SqliteVectorCapabilityAdapter(DataSource dataSource,
                                  VectorCapabilityProperties properties,
                                  VectorExtensionLoader extensionLoader) {
        this.dataSource = dataSource;
        this.properties = properties;
        this.extensionLoader = extensionLoader;
    }

    @Override
    public VectorCapabilityReport inspect(VectorCapabilityRequest request) {
        if (!properties.isEnabled()) {
            return unavailable(request, VectorCapabilityFailure.DISABLED,
                    VectorExtensionLoadStatus.NOT_ATTEMPTED, "Vector capability is disabled", null);
        }
        if (request.dimension() <= 0) {
            return unavailable(request, VectorCapabilityFailure.INVALID_DIMENSION,
                    VectorExtensionLoadStatus.NOT_ATTEMPTED,
                    "Vector dimension must be positive", null);
        }
        if (!SUPPORTED_ENCODINGS.contains(request.encoding())) {
            return unavailable(request, VectorCapabilityFailure.UNSUPPORTED_ENCODING,
                    VectorExtensionLoadStatus.NOT_ATTEMPTED,
                    "Requested vector encoding is not supported", null);
        }
        Path extensionPath = properties.getExtensionPath();
        if (extensionPath == null) {
            return unavailable(request, VectorCapabilityFailure.EXTENSION_PATH_MISSING,
                    VectorExtensionLoadStatus.NOT_ATTEMPTED,
                    "Vector extension path is not configured", null);
        }
        extensionPath = extensionPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(extensionPath) || !Files.isReadable(extensionPath)) {
            return unavailable(request, VectorCapabilityFailure.EXTENSION_PATH_INVALID,
                    VectorExtensionLoadStatus.NOT_ATTEMPTED,
                    "Configured vector extension path is not readable", null);
        }

        try (Connection connection = dataSource.getConnection()) {
            VectorExtensionLoader.LoadResult load = extensionLoader.load(connection, extensionPath);
            if (load.status() != VectorExtensionLoadStatus.LOADED) {
                return unavailable(request, VectorCapabilityFailure.EXTENSION_LOAD_FAILED,
                        load.status(), "Vector extension load failed", null);
            }
            String runtimeVersion = queryString(connection, "SELECT vec_version()");
            if (!properties.getRequiredExtensionVersion().equals(runtimeVersion)) {
                return unavailable(request, VectorCapabilityFailure.INCOMPATIBLE_RUNTIME,
                        load.status(), "Vector extension version is incompatible", runtimeVersion);
            }
            if (!moduleIsLoaded(connection)) {
                return unavailable(request, VectorCapabilityFailure.CAPABILITY_CHECK_FAILED,
                        load.status(), "Vector module probe failed", runtimeVersion);
            }
            return VectorCapabilityReport.available(request, SUPPORTED_ENCODINGS, runtimeVersion);
        } catch (SQLException exception) {
            return unavailable(request, VectorCapabilityFailure.CAPABILITY_CHECK_FAILED,
                    VectorExtensionLoadStatus.FAILED, "Vector capability probe failed", null);
        }
    }

    private static String queryString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next()) {
                throw new SQLException("Capability probe returned no value");
            }
            return resultSet.getString(1);
        }
    }

    private static boolean moduleIsLoaded(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT EXISTS (SELECT 1 FROM pragma_module_list WHERE name = '" + MODULE_NAME
                             + "')")) {
            return resultSet.next() && resultSet.getInt(1) == 1;
        }
    }

    private static VectorCapabilityReport unavailable(VectorCapabilityRequest request,
                                                      VectorCapabilityFailure failure,
                                                      VectorExtensionLoadStatus loadStatus,
                                                      String detail,
                                                      String runtimeVersion) {
        return VectorCapabilityReport.unavailable(request, failure, loadStatus, detail,
                runtimeVersion);
    }
}
