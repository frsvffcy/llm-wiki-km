package org.km.llmwiki.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.km.llmwiki.graph.GraphProjectionOperations;
import org.km.llmwiki.graph.GraphProjectionVerificationStatus;
import org.km.llmwiki.search.SearchService;
import org.km.llmwiki.search.embedding.EmbeddingEvidenceKind;
import org.km.llmwiki.search.embedding.EmbeddingProjectionReadiness;
import org.km.llmwiki.search.embedding.EmbeddingProjectionReadinessRepository;
import org.km.llmwiki.search.embedding.EmbeddingProjectionReadinessStatus;
import org.km.llmwiki.source.DocumentRepository;
import org.km.llmwiki.source.SourceChunkRepository;
import org.km.llmwiki.wiki.PublishedWikiRepository;
import org.km.llmwiki.workspace.WorkspaceService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Application-level smoke over an upgraded historical installed state (#322): the fixture is a
 * real populated SQLite workspace materialized at Flyway V18 (the pre-CJK-projection era, the
 * highest-risk semantic boundary) and migrated to the latest schema by the real migration chain
 * before the latest application starts. Gates: the workspace opens and reads through latest
 * application contracts without manual SQL repair, startup Flyway is a no-op (idempotent), and
 * readiness/health surfaces never fake current or READY state for derived projections.
 */
@SpringBootTest(properties = {
        "app.persistence.sqlite.path=ignored-by-dynamic-property",
        "app.graph.projection.enabled=true"})
@AutoConfigureMockMvc
@Tag("integration")
class HistoricalUpgradeSmokeIntegrationTest {

    private static final long HISTORICAL_WORKSPACE_ID = 7L;
    private static final Path SMOKE_DIRECTORY = createSmokeDirectory();
    private static final Path FIXTURE_DATABASE = materializeUpgradedFixture();

    private static Path createSmokeDirectory() {
        try {
            return Files.createTempDirectory("historical-upgrade-smoke");
        } catch (IOException failure) {
            throw new IllegalStateException("smoke fixture directory", failure);
        }
    }

    /**
     * Materializes the populated V18 workspace and runs the real migration chain to the latest
     * schema before the application context starts, so Flyway startup is a no-op and the
     * application never sees a half-upgraded state.
     */
    private static Path materializeUpgradedFixture() {
        try {
            Path database = HistoricalUpgradeFixtures.materializeBaseline(SMOKE_DIRECTORY,
                    "smoke-v18", 18);
            Flyway.configure()
                    .dataSource(new org.springframework.jdbc.datasource.SimpleDriverDataSource(
                            new org.sqlite.JDBC(), "jdbc:sqlite:" + database))
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            return database;
        } catch (Exception failure) {
            throw new IllegalStateException("historical fixture materialization failed", failure);
        }
    }

    @DynamicPropertySource
    static void historicalDatabase(DynamicPropertyRegistry registry) {
        registry.add("app.persistence.sqlite.path",
                () -> FIXTURE_DATABASE.toString());
    }

    @Autowired
    private WorkspaceService workspaces;

    @Autowired
    private SearchService searchService;

    @Autowired
    private DocumentRepository documents;

    @Autowired
    private SourceChunkRepository sourceChunks;

    @Autowired
    private PublishedWikiRepository publishedWikiRepository;

    @Autowired
    private EmbeddingProjectionReadinessRepository embeddingReadiness;

    @Autowired
    private GraphProjectionOperations graphProjectionOperations;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void upgradedHistoricalWorkspaceOpensAndReadsThroughLatestContracts() {
        workspaces.open(HISTORICAL_WORKSPACE_ID);

        // Canonical document data survives the migration chain and is readable.
        assertThat(documents.findActiveByWorkspaceAndSha256(HISTORICAL_WORKSPACE_ID,
                HistoricalUpgradeFixtures.sha256("runbook-v1"))).isPresent();
        // Historical source chunks keep their content identity without re-extraction.
        assertThat(sourceChunks.findByDocumentId(1001L)).hasSize(2);
        assertThat(sourceChunks.findDocumentIdsWithStaleChunkPolicy(HISTORICAL_WORKSPACE_ID,
                "chunk-policy-v1-current")).isEmpty();

        // A foreign-workspace document never leaks into active-workspace reads.
        assertThat(documents.findActiveByWorkspaceAndSha256(HISTORICAL_WORKSPACE_ID,
                HistoricalUpgradeFixtures.sha256("foreign-v1"))).isEmpty();
        // Published wiki state is readable through the latest contract.
        assertThat(publishedWikiRepository.findPublishedByKnowledgeId(HISTORICAL_WORKSPACE_ID,
                "wiki-db-lock")).isPresent();
    }

    @Test
    void ftsRecreatedByUpgradeStaysQueryableAndHealthNeverFakesHealthy() throws Exception {
        // Open the active historical workspace explicitly so the health surface reads THIS
        // workspace's state (no cross-method shared ordering dependency).
        workspaces.open(HISTORICAL_WORKSPACE_ID);

        // The historical unicode61 FTS rows were dropped by V19; the empty projection must
        // stay queryable through the latest contract and must surface a deterministic
        // rebuild-required health, never a healthy claim over a missing projection.
        var results = searchService.search("資料庫", "WIKI", null, null, 1, 20);
        assertThat(results).isNotNull();

        // Corpus-pinned: the WIKI projection was recreated empty by the upgrade, so the
        // latest application must answer REBUILD_REQUIRED for the WIKI corpus itself.
        mockMvc.perform(get("/api/v1/search/index/health?corpus=WIKI"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertThat(body).contains("\"corpus\":\"WIKI\"");
                    assertThat(body).contains("\"status\":\"REBUILD_REQUIRED\"");
                });
    }

    @Test
    void embeddingReadinessIsNeverInventedByTheUpgrade() {
        // The V18 baseline predates the embedding projection era; after the real migration
        // chain no readiness row may be invented for a corpus that was never built. The
        // legacy-READY ledger semantics (no invented generation history) are owned by the
        // V24 boundary in HistoricalUpgradeMatrixIntegrationTest.
        assertThat(embeddingReadiness.find(HISTORICAL_WORKSPACE_ID, EmbeddingEvidenceKind.WIKI))
                .isEmpty();
    }

    @Test
    void graphReadinessNeverClaimsAProjectionThatWasNeverBuilt() {
        var verification = graphProjectionOperations.readiness(HISTORICAL_WORKSPACE_ID);

        // The migrated workspace has an empty graph lifecycle: the latest application must
        // answer NOT_READY (or typed disabled), never READY, until an explicit rebuild.
        assertThat(verification.status()).isEqualTo(GraphProjectionVerificationStatus.NOT_READY);
    }

    @Test
    void repeatedStartupMigrateIsIdempotent() throws Exception {
        // Flyway startup already ran as a no-op inside this application context; a second
        // explicit migrate on the same database must remain a no-op.
        org.flywaydb.core.api.output.MigrateResult repeat = Flyway.configure()
                .dataSource(new org.springframework.jdbc.datasource.SimpleDriverDataSource(
                        new org.sqlite.JDBC(), "jdbc:sqlite:"
                        + SMOKE_DIRECTORY.resolve("smoke-v18").resolve("knowledge.db")))
                .locations("classpath:db/migration")
                .load()
                .migrate();
        assertThat(repeat.migrationsExecuted).isZero();
    }
}
