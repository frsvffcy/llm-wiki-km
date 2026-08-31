package org.km.llmwiki.search;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Executable evidence for Issue #124. These tests use the project's pinned Xerial driver directly;
 * they do not change the production FTS schema or canonical Wiki/Source data.
 */
class CjkFtsSearchQualitySpikeTest {

    private static final int PERSONAL_WIKI_DOCUMENTS = 1_000;
    private static final List<FixtureDocument> CORPUS = loadCorpus();

    @Test
    void pinnedXerialRuntimeProvidesFts5Unicode61AndTrigram() throws SQLException {
        try (Connection connection = openMemoryDatabase()) {
            String sqliteVersion = queryString(connection, "SELECT sqlite_version()");
            String driverVersion = connection.getMetaData().getDriverVersion();
            List<String> compileOptions = queryStrings(connection, "PRAGMA compile_options");

            createTable(connection, "unicode_probe", "unicode61 remove_diacritics 2");
            createTable(connection, "trigram_probe", "trigram");

            assertThat(driverVersion).isEqualTo("3.50.3.0");
            assertThat(sqliteVersion).isEqualTo("3.50.3");
            assertThat(compileOptions).contains("ENABLE_FTS5");
            assertThat(tableExists(connection, "unicode_probe")).isTrue();
            assertThat(tableExists(connection, "trigram_probe")).isTrue();

            System.out.printf("Xerial=%s SQLite=%s FTS5=%s trigram=create-ok%n",
                    driverVersion, sqliteVersion, compileOptions.contains("ENABLE_FTS5"));
        }
    }

    @Test
    void unicode61BaselineOnlyMatchesWholeSeparatorDelimitedCjkTokens() throws SQLException {
        try (Connection connection = indexedMemoryDatabase(Strategy.UNICODE61)) {
            assertThat(ids(connection, Strategy.UNICODE61,
                    "這個系統使用SpringBoot與jOOQ建立全文搜尋索引"))
                    .containsExactly("natural-mixed");
            assertThat(ids(connection, Strategy.UNICODE61, "全文搜尋")).isEmpty();
            assertThat(ids(connection, Strategy.UNICODE61, "連線設定")).isEmpty();
            assertThat(ids(connection, Strategy.UNICODE61, "SpringBoot")).isEmpty();

            assertThat(ids(connection, Strategy.UNICODE61, "搜尋"))
                    .containsExactly("short-search");
            assertThat(ids(connection, Strategy.UNICODE61, "索引"))
                    .containsExactly("short-index");
            assertThat(ids(connection, Strategy.UNICODE61, "流程"))
                    .containsExactly("short-flow");
            assertThat(ids(connection, Strategy.UNICODE61, "SQLite FTS5"))
                    .containsExactlyInAnyOrder("markdown", "punctuation");
        }
    }

    @Test
    void trigramRecoversSubstringsButCannotMatchTwoCodePointTerms() throws SQLException {
        try (Connection connection = indexedMemoryDatabase(Strategy.TRIGRAM)) {
            assertThat(ids(connection, Strategy.TRIGRAM, "全文搜尋"))
                    .containsExactlyInAnyOrder("natural-mixed", "sqlite-mixed");
            assertThat(ids(connection, Strategy.TRIGRAM, "連線設定"))
                    .containsExactly("connection");
            assertThat(ids(connection, Strategy.TRIGRAM, "SpringBoot"))
                    .containsExactly("natural-mixed");
            assertThat(ids(connection, Strategy.TRIGRAM, "jOOQ"))
                    .containsExactlyInAnyOrder("natural-mixed", "spring-spaced");

            assertThat(ids(connection, Strategy.TRIGRAM, "搜尋")).isEmpty();
            assertThat(ids(connection, Strategy.TRIGRAM, "索引")).isEmpty();
            assertThat(ids(connection, Strategy.TRIGRAM, "流程")).isEmpty();

            // Trigram is substring search for Latin text too, so token-prefix noise is expected.
            assertThat(ids(connection, Strategy.TRIGRAM, "SQLite"))
                    .contains("latin-prefix-noise");
            assertThat(ids(connection, Strategy.TRIGRAM, "FTS5"))
                    .contains("latin-prefix-noise");

            String snippet = snippet(connection, Strategy.TRIGRAM, "連線設定", "connection");
            assertThat(snippet).contains("<mark>連線設定</mark>");
            assertThat(highlight(connection, Strategy.TRIGRAM, "SpringBoot", "natural-mixed"))
                    .contains("<mark>SpringBoot</mark>");
        }
    }

    @Test
    void deterministicBigramProjectionSupportsShortCjkAndExactTechnicalTokens() throws SQLException {
        assertThat(CjkBigramProjection.VERSION).isEqualTo("cjk-bigram-v1");
        assertThat(CjkBigramProjection.transform("資料庫連線設定 SQLite/FTS5"))
                .isEqualTo("資料 料庫 庫連 連線 線設 設定 sqlite fts5");
        assertThat(CjkBigramProjection.transform("搜尋／索引／流程"))
                .isEqualTo("搜尋 索引 流程");

        try (Connection connection = indexedMemoryDatabase(Strategy.BIGRAM)) {
            assertThat(ids(connection, Strategy.BIGRAM, "全文搜尋"))
                    .containsExactlyInAnyOrder("natural-mixed", "sqlite-mixed");
            assertThat(ids(connection, Strategy.BIGRAM, "連線設定"))
                    .containsExactly("connection");
            assertThat(ids(connection, Strategy.BIGRAM, "搜尋"))
                    .contains("natural-mixed", "sqlite-mixed", "markdown", "search-result",
                            "short-search");
            assertThat(ids(connection, Strategy.BIGRAM, "索引"))
                    .contains("natural-mixed", "markdown", "index-rebuild", "short-index");
            assertThat(ids(connection, Strategy.BIGRAM, "流程"))
                    .contains("connection", "markdown", "spring-spaced", "index-rebuild",
                            "separated-terms", "rag-llm", "short-flow");
            assertThat(ids(connection, Strategy.BIGRAM, "SpringBoot"))
                    .containsExactly("natural-mixed");
            assertThat(ids(connection, Strategy.BIGRAM, "SQLite FTS5"))
                    .containsExactlyInAnyOrder("sqlite-mixed", "markdown", "punctuation");
            assertThat(ids(connection, Strategy.BIGRAM, "SQLite"))
                    .doesNotContain("latin-prefix-noise");

            // Existing snippet() would expose projection tokens, not canonical text.
            assertThat(snippet(connection, Strategy.BIGRAM, "連線設定", "connection"))
                    .contains("連線", "線設", "設定")
                    .doesNotContain("資料庫連線設定");
        }
    }

    @Test
    void allStrategiesKeepMatchOperatorsLiteralAndBound() throws SQLException {
        for (Strategy strategy : Strategy.values()) {
            try (Connection connection = indexedMemoryDatabase(strategy)) {
                assertThat(ids(connection, strategy, "\" OR 1=1 --")).isEmpty();
                assertThat(ids(connection, strategy, "* OR SQLite")).isEmpty();
                assertThat(tableExists(connection, "documents_fts")).isTrue();
            }
        }
        assertThatThrownBy(() -> FtsMatchQuery.literalExpression("\u0000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control characters");

        String longCjkQuery = "這是一段超過既有詞數上限的繁體中文查詢用來驗證投影限制";
        assertThatThrownBy(() -> FtsMatchQuery.literalExpression(
                CjkBigramProjection.transform(longCjkQuery)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than 16 terms");
    }

    @Test
    void titleWeightingRemainsAvailableForProjectedColumns() throws SQLException {
        try (Connection connection = openMemoryDatabase()) {
            createTable(connection, "rank_fts", "unicode61 remove_diacritics 2");
            insert(connection, "rank_fts", "title-hit", CjkBigramProjection.transform("連線設定"),
                    CjkBigramProjection.transform("普通內容"));
            insert(connection, "rank_fts", "content-hit", CjkBigramProjection.transform("普通標題"),
                    CjkBigramProjection.transform("這裡說明資料庫連線設定"));

            String expression = FtsMatchQuery.literalExpression(
                    CjkBigramProjection.transform("連線設定"));
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id FROM rank_fts WHERE rank_fts MATCH ?
                    ORDER BY bm25(rank_fts, 0.0, 8.0, 1.0), id
                    """)) {
                statement.setString(1, expression);
                assertThat(resultStrings(statement)).containsExactly("title-hit", "content-hit");
            }
        }
    }

    @Test
    void recordsRecallAndBasicPrecisionAgainstExplicitRelevanceJudgments() throws SQLException {
        List<QualityJudgment> judgments = List.of(
                judgment("全文搜尋", "natural-mixed", "sqlite-mixed"),
                judgment("連線設定", "connection"),
                judgment("搜尋", "natural-mixed", "sqlite-mixed", "markdown", "search-result",
                        "short-search"),
                judgment("索引", "natural-mixed", "markdown", "index-rebuild", "short-index"),
                judgment("流程", "connection", "markdown", "spring-spaced", "index-rebuild",
                        "separated-terms", "rag-llm", "short-flow"),
                judgment("SpringBoot", "natural-mixed"),
                judgment("jOOQ", "natural-mixed", "spring-spaced"),
                judgment("SQLite", "sqlite-mixed", "markdown", "punctuation"),
                judgment("FTS5", "sqlite-mixed", "markdown", "punctuation"),
                judgment("SQLite FTS5", "sqlite-mixed", "markdown", "punctuation"),
                judgment("RAG LLM", "markdown", "rag-llm", "punctuation")
        );

        List<QualityResult> results = new ArrayList<>();
        for (Strategy strategy : Strategy.values()) {
            int relevant = 0;
            int retrieved = 0;
            int truePositives = 0;
            try (Connection connection = indexedMemoryDatabase(strategy)) {
                for (QualityJudgment judgment : judgments) {
                    Set<String> actual = new LinkedHashSet<>(
                            ids(connection, strategy, judgment.query()));
                    Set<String> intersection = new LinkedHashSet<>(actual);
                    intersection.retainAll(judgment.relevantIds());
                    relevant += judgment.relevantIds().size();
                    retrieved += actual.size();
                    truePositives += intersection.size();
                }
            }
            results.add(new QualityResult(strategy,
                    (double) truePositives / relevant,
                    (double) truePositives / retrieved));
        }

        System.out.println("strategy | micro_recall | basic_precision");
        results.forEach(result -> System.out.printf(Locale.ROOT, "%s | %.3f | %.3f%n",
                result.strategy().name().toLowerCase(Locale.ROOT), result.recall(),
                result.precision()));

        QualityResult unicode61 = resultFor(results, Strategy.UNICODE61);
        QualityResult trigram = resultFor(results, Strategy.TRIGRAM);
        QualityResult bigram = resultFor(results, Strategy.BIGRAM);
        assertThat(unicode61.recall()).isLessThan(0.5d);
        assertThat(trigram.recall()).isGreaterThan(unicode61.recall()).isLessThan(1.0d);
        assertThat(trigram.precision()).isLessThan(1.0d);
        assertThat(bigram.recall()).isEqualTo(1.0d);
        assertThat(bigram.precision()).isEqualTo(1.0d);
    }

    @Test
    void recordsReproduciblePersonalWikiScaleCost() throws Exception {
        Path output = Path.of("target", "cjk-fts-spike");
        Files.createDirectories(output);
        List<BenchmarkResult> results = new ArrayList<>();
        for (Strategy strategy : Strategy.values()) {
            results.add(benchmark(output.resolve(strategy.name().toLowerCase(Locale.ROOT) + ".db"),
                    strategy));
        }

        System.out.println("strategy | docs | db_bytes | rebuild_ms | median_query_us");
        results.forEach(result -> System.out.printf(Locale.ROOT, "%s | %d | %d | %.3f | %.3f%n",
                result.strategy(), result.documents(), result.databaseBytes(),
                result.rebuildMillis(), result.medianQueryMicros()));

        assertThat(results).allSatisfy(result -> {
            assertThat(result.documents()).isEqualTo(PERSONAL_WIKI_DOCUMENTS);
            assertThat(result.databaseBytes()).isPositive();
            assertThat(result.rebuildMillis()).isPositive();
            assertThat(result.medianQueryMicros()).isPositive();
        });
    }

    private static BenchmarkResult benchmark(Path database, Strategy strategy) throws Exception {
        Files.deleteIfExists(database);
        long rebuildStarted = System.nanoTime();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
            createTable(connection, "documents_fts", strategy.tokenizer());
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO documents_fts(id, title, content) VALUES (?, ?, ?)")) {
                for (int index = 0; index < PERSONAL_WIKI_DOCUMENTS; index++) {
                    FixtureDocument source = CORPUS.get(index % CORPUS.size());
                    statement.setString(1, source.id() + "-" + index);
                    statement.setString(2, strategy.project(source.title()));
                    statement.setString(3, strategy.project(source.content() + " 文件編號" + index));
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            connection.commit();
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO documents_fts(documents_fts) VALUES ('optimize')");
            }
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("VACUUM");
            }
        }
        double rebuildMillis = nanosToMillis(System.nanoTime() - rebuildStarted);
        long databaseBytes = Files.size(database);

        List<Long> queryNanos = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM documents_fts WHERE documents_fts MATCH ?")) {
            List<String> queries = List.of("全文搜尋", "連線設定", "搜尋", "索引", "流程",
                    "SpringBoot", "jOOQ", "SQLite", "SQLite FTS5", "RAG LLM");
            for (int iteration = 0; iteration < 40; iteration++) {
                for (String query : queries) {
                    statement.setString(1, strategy.expression(query));
                    long started = System.nanoTime();
                    try (ResultSet resultSet = statement.executeQuery()) {
                        resultSet.next();
                        resultSet.getLong(1);
                    }
                    if (iteration >= 10) {
                        queryNanos.add(System.nanoTime() - started);
                    }
                }
            }
        }
        queryNanos.sort(Comparator.naturalOrder());
        double medianQueryMicros = queryNanos.get(queryNanos.size() / 2) / 1_000.0d;
        return new BenchmarkResult(strategy.name().toLowerCase(Locale.ROOT),
                PERSONAL_WIKI_DOCUMENTS, databaseBytes, rebuildMillis, medianQueryMicros);
    }

    private static Connection indexedMemoryDatabase(Strategy strategy) throws SQLException {
        Connection connection = openMemoryDatabase();
        createTable(connection, "documents_fts", strategy.tokenizer());
        for (FixtureDocument document : CORPUS) {
            insert(connection, "documents_fts", document.id(), strategy.project(document.title()),
                    strategy.project(document.content()));
        }
        return connection;
    }

    private static Connection openMemoryDatabase() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite::memory:");
    }

    private static void createTable(Connection connection, String table, String tokenizer)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE VIRTUAL TABLE " + table + " USING fts5(" +
                    "id UNINDEXED, title, content, tokenize = '" + tokenizer + "')");
        }
    }

    private static void insert(Connection connection, String table, String id, String title,
                               String content) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + table + "(id, title, content) VALUES (?, ?, ?)")) {
            statement.setString(1, id);
            statement.setString(2, title);
            statement.setString(3, content);
            statement.executeUpdate();
        }
    }

    private static List<String> ids(Connection connection, Strategy strategy, String query)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id FROM documents_fts
                WHERE documents_fts MATCH ?
                ORDER BY id
                """)) {
            statement.setString(1, strategy.expression(query));
            return resultStrings(statement);
        }
    }

    private static String snippet(Connection connection, Strategy strategy, String query, String id)
            throws SQLException {
        return markedText(connection, strategy, query, id,
                "snippet(documents_fts, -1, '<mark>', '</mark>', '…', 24)");
    }

    private static String highlight(Connection connection, Strategy strategy, String query, String id)
            throws SQLException {
        return markedText(connection, strategy, query, id,
                "highlight(documents_fts, 2, '<mark>', '</mark>')");
    }

    private static String markedText(Connection connection, Strategy strategy, String query, String id,
                                     String function) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + function + " FROM documents_fts WHERE documents_fts MATCH ? AND id = ?")) {
            statement.setString(1, strategy.expression(query));
            statement.setString(2, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getString(1);
            }
        }
    }

    private static List<String> resultStrings(PreparedStatement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            List<String> values = new ArrayList<>();
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
            }
            return values;
        }
    }

    private static String queryString(Connection connection, String sql) throws SQLException {
        return queryStrings(connection, sql).getFirst();
    }

    private static List<String> queryStrings(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
            }
            return values;
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM sqlite_master WHERE name = ?")) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) == 1;
            }
        }
    }

    private static List<FixtureDocument> loadCorpus() {
        InputStream stream = CjkFtsSearchQualitySpikeTest.class.getResourceAsStream(
                "/search/cjk-search-quality-corpus.tsv");
        if (stream == null) {
            throw new IllegalStateException("CJK search corpus is missing");
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .map(line -> line.split("\\t", -1))
                    .map(columns -> {
                        if (columns.length != 3) {
                            throw new IllegalStateException("Invalid CJK fixture row");
                        }
                        return new FixtureDocument(columns[0], columns[1],
                                columns[2].replace("\\n", "\n"));
                    })
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load CJK search corpus", exception);
        }
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0d;
    }

    private static QualityJudgment judgment(String query, String... relevantIds) {
        return new QualityJudgment(query, Set.of(relevantIds));
    }

    private static QualityResult resultFor(List<QualityResult> results, Strategy strategy) {
        return results.stream()
                .filter(result -> result.strategy() == strategy)
                .findFirst()
                .orElseThrow();
    }

    private enum Strategy {
        UNICODE61("unicode61 remove_diacritics 2") {
            @Override
            String project(String value) {
                return Normalizer.normalize(value, Normalizer.Form.NFC);
            }
        },
        TRIGRAM("trigram") {
            @Override
            String project(String value) {
                return Normalizer.normalize(value, Normalizer.Form.NFC);
            }
        },
        BIGRAM("unicode61 remove_diacritics 2") {
            @Override
            String project(String value) {
                return CjkBigramProjection.transform(value);
            }
        };

        private final String tokenizer;

        Strategy(String tokenizer) {
            this.tokenizer = tokenizer;
        }

        String tokenizer() {
            return tokenizer;
        }

        abstract String project(String value);

        String expression(String query) {
            return FtsMatchQuery.literalExpression(project(query));
        }
    }

    private static final class CjkBigramProjection {

        private static final String VERSION = "cjk-bigram-v1";

        private CjkBigramProjection() {
        }

        static String transform(String input) {
            String normalized = Normalizer.normalize(input, Normalizer.Form.NFC);
            List<String> tokens = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            Character.UnicodeScript currentScript = null;
            for (int codePoint : normalized.codePoints().toArray()) {
                Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
                boolean searchable = Character.isLetterOrDigit(codePoint);
                Character.UnicodeScript group = script == Character.UnicodeScript.HAN
                        ? Character.UnicodeScript.HAN : Character.UnicodeScript.LATIN;
                if (!searchable) {
                    flush(tokens, current, currentScript);
                    currentScript = null;
                } else {
                    if (currentScript != null && currentScript != group) {
                        flush(tokens, current, currentScript);
                        currentScript = null;
                    }
                    current.appendCodePoint(codePoint);
                    currentScript = group;
                }
            }
            flush(tokens, current, currentScript);
            return String.join(" ", tokens);
        }

        private static void flush(List<String> tokens, StringBuilder current,
                                  Character.UnicodeScript script) {
            if (current.isEmpty()) {
                return;
            }
            if (script == Character.UnicodeScript.HAN) {
                int[] codePoints = current.codePoints().toArray();
                if (codePoints.length == 1) {
                    tokens.add(new String(codePoints, 0, 1));
                } else {
                    for (int index = 0; index < codePoints.length - 1; index++) {
                        tokens.add(new String(codePoints, index, 2));
                    }
                }
            } else {
                tokens.add(current.toString().toLowerCase(Locale.ROOT));
            }
            current.setLength(0);
        }
    }

    private record FixtureDocument(String id, String title, String content) {
    }

    private record BenchmarkResult(String strategy, int documents, long databaseBytes,
                                   double rebuildMillis, double medianQueryMicros) {
    }

    private record QualityJudgment(String query, Set<String> relevantIds) {
    }

    private record QualityResult(Strategy strategy, double recall, double precision) {
    }
}
