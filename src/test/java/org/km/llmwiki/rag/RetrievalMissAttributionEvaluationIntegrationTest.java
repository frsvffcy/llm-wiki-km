package org.km.llmwiki.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.ai.answer.AnswerContextCompactionPolicyRegistry;
import org.km.llmwiki.ai.query.QueryTransformationPolicyRegistry;
import org.km.llmwiki.search.FtsSearchIndexRepository;
import org.km.llmwiki.search.KnowledgeSearchDocument;
import org.km.llmwiki.search.SearchCandidate;
import org.km.llmwiki.search.SearchCorpus;
import org.km.llmwiki.search.SearchQuery;
import org.km.llmwiki.search.SearchService;
import org.km.llmwiki.search.SearchServingConsistencyGate;
import org.km.llmwiki.search.SourceChunkIndexingService;
import org.km.llmwiki.search.SourceSearchAuthorityRepository;
import org.km.llmwiki.search.SourceSearchIndexSyncRepository;
import org.km.llmwiki.search.SourceSearchIndexSyncStatus;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.km.llmwiki.wiki.PublishedWikiContentReader;
import org.km.llmwiki.wiki.PublishedWikiRepository;
import org.km.llmwiki.wiki.WikiContentHash;
import org.km.llmwiki.workspace.CreateWorkspaceRequest;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executable issue #575 evidence.  The evaluator uses real FTS, serving-readiness gates and the
 * Ask-facing inspector; classification is derived from observable production signals rather
 * than from expected labels alone.
 */
class RetrievalMissAttributionEvaluationIntegrationTest extends IsolatedIntegrationTest {

    private static final Path REPORT_DIR = Path.of("target", "quality-reports");

    @TempDir Path temp;
    @Autowired WorkspaceService workspaces;
    @Autowired SearchService searchService;
    @Autowired FtsSearchIndexRepository ftsRepository;
    @Autowired SourceChunkIndexingService sourceChunkIndexingService;
    @Autowired SourceSearchIndexSyncRepository sourceSync;
    @Autowired SourceSearchAuthorityRepository sourceAuthority;
    @Autowired SearchServingConsistencyGate sourceServingGate;
    @Autowired PublishedWikiRepository publishedWikiRepository;
    @Autowired PublishedWikiContentReader publishedWikiContentReader;
    @Autowired QueryTransformationPolicyRegistry queryTransformation;
    @Autowired SecondStageRerankPolicyRegistry rerank;
    @Autowired AnswerContextCompactionPolicyRegistry contextPolicy;
    @Autowired FusionRankingPolicyProvider fusionPolicy;
    @Autowired ObjectMapper objectMapper;

    @Test
    void classifiesFiveMissCausesAndWritesMeasuredReports() throws Exception {
        WorkspaceFixture ws = workspace("miss-attribution-v1");
        RetrievalInspectorService inspector = inspector();

        Map<String, String> coverageIdentities = new LinkedHashMap<>();
        for (RetrievalMissEvaluationCorpusV1.CoverageCase fixture
                : RetrievalMissEvaluationCorpusV1.COVERAGE) {
            long id = source(ws, fixture.id() + ".pdf", fixture.content());
            coverageIdentities.put(fixture.id(), "SOURCE_CHUNK:" + id);
        }

        List<Observation> observations = new ArrayList<>();
        for (RetrievalMissEvaluationCorpusV1.CoverageCase fixture
                : RetrievalMissEvaluationCorpusV1.COVERAGE) {
            observations.add(observe(fixture.id(), fixture.query(),
                    coverageIdentities.get(fixture.id()), true, MissCause.NONE,
                    ReadinessState.FRESH, inspector, List.of()));
        }

        String propertyIdentity = coverageIdentities.get("property-token");
        observations.add(observe("keyword-with-filler",
                RetrievalMissEvaluationCorpusV1.HUMAN_LIKE_QUERY, propertyIdentity, true,
                MissCause.QUERY_PROJECTION, ReadinessState.FRESH, inspector,
                RetrievalMissEvaluationCorpusV1.BOUNDED_LEVERS));
        observations.add(observe("multi-token-missing-term",
                RetrievalMissEvaluationCorpusV1.MISSING_TERM_QUERY, propertyIdentity, true,
                MissCause.QUERY_PROJECTION, ReadinessState.FRESH, inspector,
                RetrievalMissEvaluationCorpusV1.BOUNDED_LEVERS));

        long freshId = source(ws, "readiness-fresh.pdf", "freshneedle authority");
        ReadinessState freshReadiness = sourceReadiness(ws, freshId);
        assertThat(freshReadiness).isEqualTo(ReadinessState.FRESH);
        observations.add(observe("readiness-fresh", "freshneedle", "SOURCE_CHUNK:" + freshId,
                true, MissCause.NONE, freshReadiness, inspector, List.of()));

        long pendingId = source(ws, "readiness-pending.pdf", "pendingneedle authority");
        long pendingDocumentId = documentId(pendingId);
        var pendingLedger = sourceSync.find(ws.id(), pendingDocumentId).orElseThrow();
        sourceSync.markPending(ws.id(), pendingDocumentId, pendingLedger.eligibleChunkCount(),
                pendingLedger.canonicalFingerprint(), "benchmark pending fixture");
        assertThat(sourceAuthority.findDocumentByChunk(ws.id(), pendingId)).isPresent();
        ReadinessState pendingReadiness = sourceReadiness(ws, pendingId);
        assertThat(pendingReadiness).isEqualTo(ReadinessState.INDEX_PENDING);
        observations.add(observe("readiness-pending", "pendingneedle",
                "SOURCE_CHUNK:" + pendingId, true, MissCause.INDEX_READINESS,
                pendingReadiness, inspector, List.of()));

        long staleId = source(ws, "readiness-stale.pdf", "staleneedle authority");
        db().sql("UPDATE source_chunk SET normalized_content = :content WHERE id = :id")
                .param("content", "canonical content changed after projection")
                .param("id", staleId).update();
        ReadinessState staleReadiness = sourceReadiness(ws, staleId);
        assertThat(staleReadiness).isEqualTo(ReadinessState.STALE);
        observations.add(observe("readiness-stale", "staleneedle", "SOURCE_CHUNK:" + staleId,
                true, MissCause.INDEX_READINESS, staleReadiness, inspector, List.of()));

        wiki(ws, "rank-a", "Rank A", "windowneedle shared evidence");
        wiki(ws, "rank-z", "Rank Z", "windowneedle shared evidence");
        observations.add(observeWithRequest("ranking-window", "windowneedle", "WIKI:rank-z",
                true, MissCause.RANKING_WINDOW, ReadinessState.FRESH, inspector,
                new RetrievalRequest("windowneedle", RetrievalMode.HYBRID_FTS, 1, 20_000)));

        wiki(ws, "authority-a-keep", "Authority A Keep", "authorityneedle shared evidence");
        wiki(ws, "authority-z-stale", "Authority Z Stale", "authorityneedle shared evidence");
        mutateVault(ws, "authority-z-stale");
        observations.add(observe("authority-reject", "authorityneedle",
                "WIKI:authority-z-stale", true, MissCause.AUTHORITY_REJECT,
                ReadinessState.FRESH, inspector, List.of()));

        observations.add(observe("corpus-mismatch",
                RetrievalMissEvaluationCorpusV1.CORPUS_MISMATCH_QUERY, null, false,
                MissCause.CORPUS_MISMATCH, ReadinessState.NOT_APPLICABLE, inspector, List.of()));

        WorkspaceFixture foreign = workspace("miss-attribution-foreign");
        long foreignId = source(foreign, "foreign.pdf", "workspaceisolationtoken authority");
        workspaces.open(ws.id());
        String foreignIdentity = "SOURCE_CHUNK:" + foreignId;
        List<String> isolationDirect = directIdentities("workspaceisolationtoken");
        List<String> isolationAsk = inspector.inspect(RetrievalRequest.defaults(
                        "workspaceisolationtoken", RetrievalMode.HYBRID_FTS))
                .finalEvidence().stream()
                .map(RetrievalInspectionReport.FinalEvidence::identity).toList();
        assertThat(isolationDirect).doesNotContain(foreignIdentity);
        assertThat(isolationAsk).doesNotContain(foreignIdentity);

        assertThat(observations).extracting(Observation::classification)
                .contains(MissCause.INDEX_READINESS, MissCause.QUERY_PROJECTION,
                        MissCause.RANKING_WINDOW, MissCause.AUTHORITY_REJECT,
                        MissCause.CORPUS_MISMATCH);
        assertThat(observations.stream().filter(Observation::truthPresent)
                .filter(result -> result.classification() == MissCause.NONE))
                .allMatch(result -> result.candidateRecall() == 1.0d
                        && result.evidenceRecall() == 1.0d);

        ProductionDefaults defaults = new ProductionDefaults(queryTransformation.activeVersion(),
                rerank.activeVersion(), fusionPolicy.policy().version(), contextPolicy.activeVersion());
        assertThat(defaults).isEqualTo(new ProductionDefaults("query-transform-disabled-v1",
                "rerank-policy-v1-exact-anchor", "fusion-rrf-v2-graph-damped",
                "context-policy-v1-current"));

        EvaluationReport report = new EvaluationReport(
                RetrievalMissEvaluationCorpusV1.VERSION, RetrievalMissEvaluationCorpusV1.K,
                "GO", defaults, List.copyOf(observations), safetyChecks(observations,
                        foreignIdentity, isolationDirect, isolationAsk), gateMatrix());
        writeReports(report);
    }

    private Observation observe(String id, String query, String expectedIdentity,
                                boolean truthPresent, MissCause expected,
                                ReadinessState readiness, RetrievalInspectorService inspector,
                                List<RetrievalMissEvaluationCorpusV1.BoundedLever> boundedLevers) {
        return observeWithRequest(id, query, expectedIdentity, truthPresent, expected, readiness,
                inspector, RetrievalRequest.defaults(query, RetrievalMode.HYBRID_FTS),
                boundedLevers);
    }

    private Observation observeWithRequest(String id, String query, String expectedIdentity,
                                           boolean truthPresent, MissCause expected,
                                           ReadinessState readiness,
                                           RetrievalInspectorService inspector,
                                           RetrievalRequest request) {
        return observeWithRequest(id, query, expectedIdentity, truthPresent, expected, readiness,
                inspector, request, List.of());
    }

    private Observation observeWithRequest(String id, String query, String expectedIdentity,
                                           boolean truthPresent, MissCause expected,
                                           ReadinessState readiness,
                                           RetrievalInspectorService inspector,
                                           RetrievalRequest request,
                                           List<RetrievalMissEvaluationCorpusV1.BoundedLever>
                                                   boundedLevers) {
        List<String> direct = directIdentities(query);
        RetrievalInspectionReport ask = inspector.inspect(request);
        List<String> candidates = lexicalCandidates(ask);
        List<ModalityObservation> modalities = modalityObservations(ask);
        ModalityDiagnosticsObservation modalityDiagnostics = new ModalityDiagnosticsObservation(
                ask.modalityDiagnostics().lexical().name(),
                ask.modalityDiagnostics().vector().name(),
                ask.modalityDiagnostics().graph().name());
        List<String> finals = ask.finalEvidence().stream()
                .map(RetrievalInspectionReport.FinalEvidence::identity).toList();
        RetrievalInspectionTrace.SelectionTrace selection = ask.selection().stream()
                .filter(item -> item.identity().equals(expectedIdentity)).reduce((a, b) -> b)
                .orElse(null);
        List<LeverObservation> levers = boundedLevers.stream()
                .map(lever -> evaluateLever(lever, expectedIdentity, request, inspector))
                .toList();

        MissCause actual = classify(truthPresent, readiness, expectedIdentity, candidates,
                finals, selection);
        // Actual resolved windows/budgets (#580): direct limit is the corpus K probe window;
        // candidateLimit/maxItems/maxCharacters come from the resolved Ask request; used
        // item/character counts come from the Inspector-reported evidence budget.
        int directLimit = RetrievalMissEvaluationCorpusV1.K;
        RetrievalBudgetPolicy.ResolvedBudget resolved = RetrievalBudgetPolicy.resolve(request);
        double candidateRecall = truthPresent && expectedIdentity != null
                && candidates.contains(expectedIdentity) ? 1.0d : 0.0d;
        double evidenceRecall = truthPresent && expectedIdentity != null
                && finals.contains(expectedIdentity) ? 1.0d : 0.0d;
        Observation result = new Observation(id, query, truthPresent, readiness, expectedIdentity,
                direct, candidates, modalities, modalityDiagnostics, finals, actual,
                expectedIdentity != null && direct.contains(expectedIdentity),
                expectedIdentity != null && candidates.contains(expectedIdentity),
                expectedIdentity != null && finals.contains(expectedIdentity),
                candidateRecall, evidenceRecall,
                directLimit, resolved.candidateLimit(), resolved.maxItems(),
                resolved.maxCharacters(), ask.budget().usedItems(),
                ask.budget().usedCharacters(),
                selection == null ? null : selection.disposition().name(),
                selection == null ? null : selection.reasonCode(), levers);
        assertThat(actual).as(id).isEqualTo(expected);
        if (expected == MissCause.QUERY_PROJECTION) {
            assertThat(result.candidateRecall()).as(id + " baseline candidate miss").isZero();
            assertThat(result.evidenceRecall()).as(id + " baseline evidence miss").isZero();
            assertThat(result.candidatePresent()).as(id + " baseline miss").isFalse();
            assertThat(levers).as(id + " bounded levers").hasSize(2)
                    .allSatisfy(lever -> {
                        assertThat(lever.directCandidates()).contains(expectedIdentity);
                        assertThat(lever.askCandidates()).contains(expectedIdentity);
                        assertThat(lever.finalEvidence()).contains(expectedIdentity);
                        assertThat(lever.candidateRecall()).isEqualTo(1.0d);
                        assertThat(lever.evidenceRecall()).isEqualTo(1.0d);
                        assertThat(lever.selectionDisposition()).isEqualTo("SELECTED");
                        // Before/after parity: same corpus window and evidence budget.
                        assertThat(lever.directLimit()).as(id + " lever direct window")
                                .isEqualTo(result.directLimit());
                        assertThat(lever.candidateLimit()).as(id + " lever candidate window")
                                .isEqualTo(result.candidateLimit());
                        assertThat(lever.maxItems()).as(id + " lever maxItems")
                                .isEqualTo(result.maxItems());
                        assertThat(lever.maxCharacters()).as(id + " lever maxCharacters")
                                .isEqualTo(result.maxCharacters());
                    });
        }
        if (expected == MissCause.INDEX_READINESS) {
            assertThat(result.directCandidatePresent()).as(id + " direct fail-closed").isFalse();
            assertThat(result.candidatePresent()).as(id + " Ask candidate fail-closed")
                    .isFalse();
            assertThat(result.finalEvidencePresent()).as(id + " final evidence fail-closed")
                    .isFalse();
            assertThat(result.candidateRecall()).as(id + " readiness candidate miss").isZero();
            assertThat(result.evidenceRecall()).as(id + " readiness evidence miss").isZero();
        }
        if (expected == MissCause.RANKING_WINDOW) {
            // Candidate hit but evidence-budget excluded: the two recalls must diverge.
            assertThat(result.candidateRecall()).as(id + " ranking-window candidate hit")
                    .isEqualTo(1.0d);
            assertThat(result.evidenceRecall()).as(id + " ranking-window evidence miss")
                    .isZero();
        }
        if (expected == MissCause.RANKING_WINDOW) {
            assertThat(result.selectionDisposition()).as(id + " stable disposition")
                    .isEqualTo("BUDGET_EXCLUDED");
        }
        if (expected == MissCause.AUTHORITY_REJECT) {
            assertThat(result.selectionDisposition()).as(id + " stable disposition")
                    .isEqualTo("REJECTED");
            assertThat(result.rejectionReason()).as(id + " stable reason")
                    .isEqualTo("INELIGIBLE");
        }
        if (expected == MissCause.NONE && truthPresent) {
            assertThat(result.directCandidatePresent()).as(id + " direct Search").isTrue();
            assertThat(result.candidatePresent()).as(id + " Ask lexical channel").isTrue();
        }
        return result;
    }

    private LeverObservation evaluateLever(RetrievalMissEvaluationCorpusV1.BoundedLever lever,
                                           String expectedIdentity, RetrievalRequest baseline,
                                           RetrievalInspectorService inspector) {
        RetrievalRequest request = RetrievalRequest.of(lever.query(), baseline.mode(),
                baseline.strategy(), baseline.maxItems(), baseline.maxCharacters());
        List<String> direct = directIdentities(lever.query());
        RetrievalInspectionReport report = inspector.inspect(request);
        List<String> candidates = lexicalCandidates(report);
        List<String> finalEvidence = report.finalEvidence().stream()
                .map(RetrievalInspectionReport.FinalEvidence::identity).toList();
        RetrievalInspectionTrace.SelectionTrace selection = report.selection().stream()
                .filter(item -> item.identity().equals(expectedIdentity)).reduce((a, b) -> b)
                .orElse(null);
        RetrievalBudgetPolicy.ResolvedBudget resolved = RetrievalBudgetPolicy.resolve(request);
        return new LeverObservation(lever.id(), lever.query(), direct, candidates, finalEvidence,
                expectedIdentity != null && candidates.contains(expectedIdentity) ? 1.0d : 0.0d,
                expectedIdentity != null && finalEvidence.contains(expectedIdentity) ? 1.0d : 0.0d,
                RetrievalMissEvaluationCorpusV1.K, resolved.candidateLimit(),
                resolved.maxItems(), resolved.maxCharacters(),
                report.budget().usedItems(), report.budget().usedCharacters(),
                selection == null ? null : selection.disposition().name());
    }

    private static MissCause classify(boolean truthPresent, ReadinessState readiness,
                                      String expectedIdentity, List<String> candidates,
                                      List<String> finals,
                                      RetrievalInspectionTrace.SelectionTrace selection) {
        if (!truthPresent) return MissCause.CORPUS_MISMATCH;
        if (readiness == ReadinessState.INDEX_PENDING || readiness == ReadinessState.STALE) {
            return MissCause.INDEX_READINESS;
        }
        if (!candidates.contains(expectedIdentity)) return MissCause.QUERY_PROJECTION;
        if (finals.contains(expectedIdentity)) return MissCause.NONE;
        if (selection != null
                && selection.disposition() == RetrievalInspectionTrace.Disposition.REJECTED) {
            return MissCause.AUTHORITY_REJECT;
        }
        if (selection != null
                && selection.disposition() == RetrievalInspectionTrace.Disposition.BUDGET_EXCLUDED) {
            return MissCause.RANKING_WINDOW;
        }
        throw new AssertionError("Unclassified retrieval miss for " + expectedIdentity);
    }

    private List<String> directIdentities(String query) {
        return searchService.findCandidates(new SearchQuery(query, SearchCorpus.ALL, null, null,
                        0, RetrievalMissEvaluationCorpusV1.K)).items().stream()
                .map(RetrievalMissAttributionEvaluationIntegrationTest::identity).toList();
    }

    private static String identity(SearchCandidate candidate) {
        return candidate.kind().name() + ":" + candidate.stableId();
    }

    private static List<String> lexicalCandidates(RetrievalInspectionReport report) {
        return report.modalities().stream()
                .filter(section -> section.modality() == CandidateSignal.LEXICAL)
                .flatMap(section -> section.candidates().stream())
                .map(RetrievalInspectionTrace.CandidateTrace::identity).toList();
    }

    private static List<ModalityObservation> modalityObservations(
            RetrievalInspectionReport report) {
        return report.modalities().stream()
                .map(section -> new ModalityObservation(section.modality().name(),
                        section.outcome().name(), section.candidates().stream()
                                .map(RetrievalInspectionTrace.CandidateTrace::identity).toList(),
                        section.rejected().stream().map(rejected -> new RejectionObservation(
                                rejected.identity(), rejected.reasonCode())).toList()))
                .toList();
    }

    private RetrievalInspectorService inspector() {
        RetrievalService retrieval = new RetrievalService(workspaces, searchService,
                publishedWikiRepository, publishedWikiContentReader, sourceAuthority,
                null, new ReciprocalRankFusion(), null);
        return new RetrievalInspectorService(retrieval, fusionPolicy);
    }

    private WorkspaceFixture workspace(String name) {
        Path root = temp.resolve(name);
        return new WorkspaceFixture(workspaces.create(
                new CreateWorkspaceRequest(name, root.toString())).id(), root);
    }

    private long source(WorkspaceFixture ws, String name, String content) {
        KeyHolder documentKey = new GeneratedKeyHolder();
        db().sql("""
                        INSERT INTO document (workspace_id, file_name, original_file_name, source_path,
                            sha256, status, parse_status, created_at, updated_at)
                        VALUES (:workspace, :name, :name, :path, :hash, 'PENDING', 'PROCESSED',
                                '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z')
                        """)
                .param("workspace", ws.id()).param("name", name)
                .param("path", "archive/" + name).param("hash", WikiContentHash.sha256(
                        name.getBytes(StandardCharsets.UTF_8))).update(documentKey);
        long documentId = documentKey.getKey().longValue();
        String hash = WikiContentHash.sha256(content.getBytes(StandardCharsets.UTF_8));
        db().sql("""
                        INSERT INTO source_chunk (document_id, chunk_no, content, normalized_content,
                            content_hash, created_at, updated_at)
                        VALUES (:document, 1, :content, :content, :hash,
                                '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z')
                        """).param("document", documentId).param("content", content)
                .param("hash", hash).update();
        sourceChunkIndexingService.reindexDocument(ws.id(), documentId);
        return db().sql("SELECT id FROM source_chunk WHERE document_id = :document")
                .param("document", documentId).query(Long.class).single();
    }

    private long documentId(long chunkId) {
        return db().sql("SELECT document_id FROM source_chunk WHERE id = :id")
                .param("id", chunkId).query(Long.class).single();
    }

    private ReadinessState sourceReadiness(WorkspaceFixture workspace, long chunkId) {
        long document = documentId(chunkId);
        var ledger = sourceSync.find(workspace.id(), document).orElseThrow();
        if (ledger.status() == SourceSearchIndexSyncStatus.INDEX_PENDING) {
            return ReadinessState.INDEX_PENDING;
        }
        return sourceServingGate.isDocumentFresh(workspace.id(), document)
                ? ReadinessState.FRESH : ReadinessState.STALE;
    }

    private void wiki(WorkspaceFixture ws, String knowledgeId, String title, String body)
            throws Exception {
        String markdown = "---\nid: \"%s\"\ntitle: \"%s\"\ntype: \"CONCEPT\"\nstatus: \"PUBLISHED\"\n---\n\n# %s\n\n%s\n"
                .formatted(knowledgeId, title, title, body);
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
        String hash = WikiContentHash.sha256(bytes);
        String logicalPath = "vault/concepts/" + knowledgeId + ".md";
        Path target = ws.root().resolve(logicalPath);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
        KeyHolder pageKey = new GeneratedKeyHolder();
        db().sql("""
                        INSERT INTO knowledge_page (workspace_id, knowledge_id, title, normalized_title,
                            type, markdown_path, status, content_hash, revision, created_at, updated_at,
                            published_at)
                        VALUES (:workspace, :knowledgeId, :title, :normalizedTitle, 'CONCEPT', :path,
                            'PUBLISHED', :hash, 3, '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z',
                            '2026-09-01T00:00:00Z')
                        """).param("workspace", ws.id()).param("knowledgeId", knowledgeId)
                .param("title", title).param("normalizedTitle", title.toLowerCase())
                .param("path", logicalPath).param("hash", hash).update(pageKey);
        ftsRepository.upsertKnowledge(new KnowledgeSearchDocument(ws.id(), knowledgeId, title,
                title.toLowerCase(), body, logicalPath, "CONCEPT", "PUBLISHED", hash));
        db().sql("""
                        INSERT INTO knowledge_search_index_sync
                            (workspace_id, knowledge_page_id, knowledge_id, status, content_hash,
                             indexed_content_hash, indexed_revision, failure_detail, updated_at)
                        VALUES (:workspace, :pageId, :knowledgeId, 'SYNCED', :hash, :hash, 3,
                                NULL, '2026-09-01T00:00:00Z')
                        """).param("workspace", ws.id())
                .param("pageId", pageKey.getKey().longValue()).param("knowledgeId", knowledgeId)
                .param("hash", hash).update();
    }

    private void mutateVault(WorkspaceFixture ws, String knowledgeId) throws Exception {
        String path = db().sql("SELECT markdown_path FROM knowledge_page "
                        + "WHERE workspace_id = :workspace AND knowledge_id = :knowledgeId")
                .param("workspace", ws.id()).param("knowledgeId", knowledgeId)
                .query(String.class).single();
        Files.writeString(ws.root().resolve(path), "manual vault drift", StandardCharsets.UTF_8);
    }

    private void writeReports(EvaluationReport report) throws Exception {
        Files.createDirectories(REPORT_DIR);
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(REPORT_DIR.resolve("retrieval-miss-attribution-v1.json").toFile(), report);
        StringBuilder markdown = new StringBuilder("# Retrieval miss attribution v1\n\n")
                .append("Decision: **").append(report.decision()).append("**  \n")
                .append("Corpus: `").append(report.corpusVersion()).append("`  \n")
                .append("Corpus k (direct probe window): ").append(report.k())
                .append("; per-case candidateLimit/maxItems/maxCharacters/used budget are listed below\n\n")
                .append("| Case | Truth | Readiness | Direct | Ask candidate | Final | CandRecall | EvRecall | CandWin | EvBudget | Classification | Lever hit |\n")
                .append("|---|---:|---|---:|---:|---:|---:|---:|---:|---|---:|---|\n");
        for (Observation item : report.observations()) {
            markdown.append("| ").append(item.id()).append(" | ").append(item.truthPresent())
                    .append(" | ").append(item.readiness()).append(" | ")
                    .append(item.directCandidatePresent()).append(" | ")
                    .append(item.candidatePresent()).append(" | ")
                    .append(item.finalEvidencePresent()).append(" | ")
                    .append(item.candidateRecall()).append(" | ").append(item.evidenceRecall())
                    .append(" | ").append(item.directLimit()).append("/").append(item.candidateLimit())
                    .append(" | ").append(item.maxItems()).append("/").append(item.maxCharacters())
                    .append("/used ").append(item.usedItems()).append("/").append(item.usedCharacters())
                    .append(" | ").append(item.classification())
                    .append(" | ").append(leverSummary(item))
                    .append(" |\n");
        }
        markdown.append("\n## Observed modality traces\n\n");
        for (Observation item : report.observations()) {
            markdown.append("- **").append(item.id()).append("**: ")
                    .append("diagnostics=").append(item.modalityDiagnostics()).append("; traces=")
                    .append(modalitySummary(item.modalities())).append('\n');
        }
        markdown.append("\n## Production defaults\n\n`").append(report.productionDefaults())
                .append("`\n\n## Blocking safety checks\n\n");
        report.safetyChecks().forEach((gate, passed) -> markdown.append("- **")
                .append(gate).append("**: ").append(passed).append('\n'));
        markdown.append("\n## Quality-gate ownership\n\n");
        report.gateMatrix().forEach((gate, ownership) -> markdown.append("- **")
                .append(gate).append("**: ").append(ownership).append('\n'));
        Files.writeString(REPORT_DIR.resolve("retrieval-miss-attribution-v1.md"), markdown,
                StandardCharsets.UTF_8);
    }

    private static String leverSummary(Observation observation) {
        if (observation.boundedLevers().isEmpty()) {
            return "N/A";
        }
        return observation.boundedLevers().stream()
                .map(lever -> lever.id() + "=direct:" + lever.directCandidates().contains(
                        observation.expectedIdentity()) + ",ask:" + lever.askCandidates().contains(
                        observation.expectedIdentity()) + ",final:" + lever.finalEvidence().contains(
                        observation.expectedIdentity()) + ",candRecall=" + lever.candidateRecall()
                        + ",evRecall=" + lever.evidenceRecall())
                .reduce((left, right) -> left + "; " + right).orElse("N/A");
    }

    private static String modalitySummary(List<ModalityObservation> modalities) {
        if (modalities.isEmpty()) {
            return "no modality trace";
        }
        return modalities.stream().map(modality -> modality.modality() + "="
                        + modality.outcome() + " candidates=" + modality.candidates()
                        + " rejected=" + modality.rejected())
                .reduce((left, right) -> left + "; " + right).orElse("no modality trace");
    }

    private static Map<String, Boolean> safetyChecks(List<Observation> observations,
                                                      String foreignIdentity,
                                                      List<String> isolationDirect,
                                                      List<String> isolationAsk) {
        Map<String, Boolean> checks = new LinkedHashMap<>();
        checks.put("exact-technical-token", passedPositive(observations, "exact-latin")
                && passedPositive(observations, "property-token"));
        checks.put("cjk-projection", passedPositive(observations, "single-cjk")
                && passedPositive(observations, "long-cjk-phrase"));
        checks.put("freshness-currentness", observations.stream()
                .filter(item -> item.id().startsWith("readiness-"))
                .allMatch(item -> item.classification() == (item.readiness() == ReadinessState.FRESH
                        ? MissCause.NONE : MissCause.INDEX_READINESS)));
        checks.put("workspace-isolation", !isolationDirect.contains(foreignIdentity)
                && !isolationAsk.contains(foreignIdentity));
        checks.put("canonical-identity", observations.stream()
                .filter(Observation::truthPresent)
                .filter(item -> item.classification() == MissCause.NONE)
                .allMatch(item -> item.expectedIdentity() != null
                        && item.finalEvidence().contains(item.expectedIdentity())));
        assertThat(checks).allSatisfy((gate, passed) -> assertThat(passed).as(gate).isTrue());
        return Collections.unmodifiableMap(checks);
    }

    private static boolean passedPositive(List<Observation> observations, String id) {
        return observations.stream().filter(item -> item.id().equals(id))
                .anyMatch(item -> item.candidateRecall() == 1.0d && item.evidenceRecall() == 1.0d
                        && item.directCandidatePresent() && item.candidatePresent());
    }

    private static Map<String, String> gateMatrix() {
        Map<String, String> gates = new LinkedHashMap<>();
        gates.put("#272", "REQUIRED: base retrieval quality regression");
        gates.put("#280", "N/A: no graph policy or projection change");
        gates.put("#316", "CONDITIONAL: required only when candidate ordering/window changes");
        gates.put("#390", "REQUIRED: query-projection lever and deterministic human-like miss");
        gates.put("#551", "N/A: benchmark-only; no Answer final-context adoption");
        return Collections.unmodifiableMap(gates);
    }

    enum MissCause { NONE, INDEX_READINESS, QUERY_PROJECTION, RANKING_WINDOW,
        AUTHORITY_REJECT, CORPUS_MISMATCH }
    enum ReadinessState { FRESH, INDEX_PENDING, STALE, NOT_APPLICABLE }

    record Observation(String id, String query, boolean truthPresent,
                       ReadinessState readiness, String expectedIdentity,
                       List<String> directSearchCandidates, List<String> askCandidates,
                       List<ModalityObservation> modalities,
                       ModalityDiagnosticsObservation modalityDiagnostics,
                       List<String> finalEvidence,
                       MissCause classification,
                       boolean directCandidatePresent, boolean candidatePresent,
                       boolean finalEvidencePresent, double candidateRecall,
                       double evidenceRecall,
                       int directLimit, int candidateLimit, int maxItems, int maxCharacters,
                       int usedItems, int usedCharacters,
                       String selectionDisposition, String rejectionReason,
                       List<LeverObservation> boundedLevers) { }
    record ModalityObservation(String modality, String outcome, List<String> candidates,
                               List<RejectionObservation> rejected) { }
    record ModalityDiagnosticsObservation(String lexical, String vector, String graph) { }
    record RejectionObservation(String identity, String reason) { }
    record LeverObservation(String id, String query, List<String> directCandidates,
                            List<String> askCandidates, List<String> finalEvidence,
                            double candidateRecall, double evidenceRecall,
                            int directLimit, int candidateLimit, int maxItems,
                            int maxCharacters, int usedItems, int usedCharacters,
                            String selectionDisposition) { }
    record ProductionDefaults(String queryTransformation, String rerank, String fusion,
                              String context) { }
    record EvaluationReport(String corpusVersion, int k, String decision,
                            ProductionDefaults productionDefaults,
                            List<Observation> observations, Map<String, Boolean> safetyChecks,
                            Map<String, String> gateMatrix) { }
    record WorkspaceFixture(long id, Path root) { }
}
