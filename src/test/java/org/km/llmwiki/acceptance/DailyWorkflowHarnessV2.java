package org.km.llmwiki.acceptance;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Privacy-safe daily-workflow validation harness v2 (Refs #467 §B).
 *
 * <p>User actions use {@link ProductAcceptanceHttpClient} only (public
 * {@code /api/v1}) plus one explicit user-file-edit simulation: overwriting a
 * single inbox file under the caller-owned temp workspace root, followed by
 * the public {@code POST /api/v1/inbox/rescan}. The harness never issues
 * direct DB inserts, never writes canonical vault or archive
 * content, and never calls internal services as a flow shortcut.
 *
 * <p>Async waits use bounded status polling with a deadline (no sleep-luck).
 * Every journey records a typed {@link DailyWorkflowFindingReport} finding —
 * never free text alone — with synthetic fixture ids and bounded counters
 * only.
 */
public final class DailyWorkflowHarnessV2 {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);
    private static final Duration JOB_DEADLINE = Duration.ofSeconds(60);

    private final ProductAcceptanceHttpClient http;
    private final DailyWorkflowFindingReport findings;
    private final Path workspaceRoot;

    private long workspaceId;
    private final List<Long> documentIds = new ArrayList<>();
    private long revisionDocIdV1 = -1;
    private String revisionStoredFileName = "";
    private long revisionOldChunkId = -1;
    private long revisionDocIdV2 = -1;
    private JsonNode askResponse;
    private long proposalId = -1;

    public DailyWorkflowHarnessV2(ProductAcceptanceHttpClient http,
                                  DailyWorkflowFindingReport findings,
                                  Path workspaceRoot) {
        this.http = http;
        this.findings = findings;
        this.workspaceRoot = workspaceRoot;
    }

    /** Full daily-workflow v2 run: baseline → governance → revision → no-answer → quality. */
    public void runAll() {
        if (!cleanStartup()) {
            return;
        }
        if (!workspace()) {
            return;
        }
        if (!ingestExtract()) {
            return;
        }
        // Structure + retrieval are observable without a provider.
        structureObservable();
        baselineRetrieval();
        noAnswerProbe();
        // Governed mutation needs the deterministic provider profile.
        if (!governedAskAndPublish()) {
            return;
        }
        sourceRevision();
        qualityBoundary();
    }

    // Clean startup ----------------------------------------------------------

    boolean cleanStartup() {
        var response = http.get("/api/v1/system/status");
        if (response.status() != 200) {
            findings.add("fresh-bootstrap", DailyWorkflowCorpusV2.VERSION,
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "system status 200 + NOT_INITIALIZED on clean root",
                    "system status HTTP " + response.status(),
                    "system/status", "clean temp instance", "http=" + response.status(),
                    "none");
            return false;
        }
        String status = response.json(http.mapper()).path("data").path("status").asText("");
        if (!"NOT_INITIALIZED".equals(status)) {
            findings.add("fresh-bootstrap", DailyWorkflowCorpusV2.VERSION,
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "NOT_INITIALIZED on clean root",
                    "got status=" + status + " (possible developer-data reuse)",
                    "system/status", "clean temp instance", "status=" + status,
                    "challenge-case-1");
            return false;
        }
        findings.add("fresh-bootstrap", DailyWorkflowCorpusV2.VERSION,
                DailyWorkflowFindingReport.Category.OPERABILITY, false, true,
                "NOT_INITIALIZED on clean root",
                "system status=NOT_INITIALIZED on clean root",
                "system/status", "clean temp instance", "http=200",
                "none");
        return true;
    }

    // Workspace --------------------------------------------------------------

    boolean workspace() {
        String body = "{\"name\":\"daily-workflow-workspace\",\"rootPath\":"
                + jsonString(workspaceRoot.toAbsolutePath().toString()) + "}";
        var created = http.postJson("/api/v1/workspaces", body);
        if (created.status() != 201) {
            findings.add("workspace-bootstrap", DailyWorkflowCorpusV2.VERSION,
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "workspace create 201 + active match",
                    "workspace create HTTP " + created.status(),
                    "workspaces", "temp workspace", "http=" + created.status(),
                    "none");
            return false;
        }
        workspaceId = created.json(http.mapper()).path("data").path("id").asLong(-1);
        var current = http.get("/api/v1/workspaces/current");
        if (current.status() != 200
                || current.json(http.mapper()).path("data").path("workspace").path("id").asLong(-1)
                != workspaceId) {
            findings.add("workspace-bootstrap", DailyWorkflowCorpusV2.VERSION,
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "active workspace matches created id",
                    "active workspace mismatch after create",
                    "workspaces", "temp workspace", "id=" + workspaceId,
                    "none");
            return false;
        }
        String rootPath = current.json(http.mapper()).path("data").path("workspace")
                .path("rootPath").asText("");
        if (!rootPath.equals(workspaceRoot.toAbsolutePath().toString())) {
            findings.add("workspace-bootstrap", DailyWorkflowCorpusV2.VERSION,
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "active root is the temp acceptance root",
                    "workspace root is not the temp acceptance root",
                    "workspaces", "temp workspace", "mismatch",
                    "challenge-case-1");
            return false;
        }
        findings.add("workspace-bootstrap", DailyWorkflowCorpusV2.VERSION,
                DailyWorkflowFindingReport.Category.OPERABILITY, false, true,
                "workspace created and active on temp root",
                "workspace created and active id=" + workspaceId,
                "workspaces", "temp workspace", "id=" + workspaceId,
                "none");
        return true;
    }

    // Ingest / extraction ----------------------------------------------------

    boolean ingestExtract() {
        for (var fixture : DailyWorkflowCorpusV2.fixtures()) {
            var uploaded = http.uploadFile(fixture.fileName(), fixture.contentType(),
                    fixture.content().getBytes(StandardCharsets.UTF_8));
            if (uploaded.status() != 201) {
                findings.add("ingest-extract", fixture.fileName(),
                        DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                        "upload 201 + extract PROCESSED + chunks current",
                        "upload HTTP " + uploaded.status(),
                        "inbox/files", "synthetic fixture", "http=" + uploaded.status(),
                        "none");
                return false;
            }
            long documentId = uploaded.json(http.mapper()).path("data").path("documentId")
                    .asLong(-1);
            if (documentId <= 0) {
                findings.add("ingest-extract", fixture.fileName(),
                        DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                        "upload returns documentId",
                        "upload missing documentId",
                        "inbox/files", "synthetic fixture", "documentId=" + documentId,
                        "none");
                return false;
            }
            documentIds.add(documentId);
            if ("daily-revision.md".equals(fixture.fileName())) {
                revisionDocIdV1 = documentId;
                revisionStoredFileName = uploaded.json(http.mapper()).path("data")
                        .path("fileName").asText(fixture.fileName());
            }
        }
        for (long documentId : documentIds) {
            var extracted = http.postJson("/api/v1/documents/" + documentId + "/extract", "{}");
            if (extracted.status() != 200) {
                findings.add("ingest-extract", DailyWorkflowCorpusV2.VERSION,
                        DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                        "extract 200 PROCESSED for every fixture",
                        "extract document " + documentId + " HTTP " + extracted.status(),
                        "documents/extract", "synthetic fixture", "doc=" + documentId,
                        "none");
                return false;
            }
            String parseStatus = extracted.json(http.mapper()).path("data").path("parseStatus")
                    .asText("");
            if (!"PROCESSED".equals(parseStatus)) {
                findings.add("ingest-extract", DailyWorkflowCorpusV2.VERSION,
                        DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                        "extract PROCESSED for every fixture",
                        "extract document " + documentId + " parseStatus=" + parseStatus,
                        "documents/extract", "synthetic fixture", "doc=" + documentId,
                        "none");
                return false;
            }
            var chunks = http.get("/api/v1/documents/" + documentId + "/chunks");
            if (chunks.status() != 200
                    || chunks.json(http.mapper()).path("data").size() == 0) {
                findings.add("ingest-extract", DailyWorkflowCorpusV2.VERSION,
                        DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                        "every extracted document has >=1 chunk",
                        "document " + documentId + " has no chunks",
                        "documents/chunks", "synthetic fixture", "doc=" + documentId,
                        "none");
                return false;
            }
            for (JsonNode chunk : chunks.json(http.mapper()).path("data")) {
                if (!"chunk-policy-v1-current".equals(chunk.path("chunkPolicyVersion").asText(""))) {
                    findings.add("ingest-extract", DailyWorkflowCorpusV2.VERSION,
                            DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                            "chunk policy version chunk-policy-v1-current",
                            "chunk policy version drift on document " + documentId,
                            "documents/chunks", "synthetic fixture", "doc=" + documentId,
                            "none");
                    return false;
                }
            }
            var preview = http.get("/api/v1/documents/" + documentId + "/extracted-content?page=0&size=5");
            if (preview.status() != 200) {
                findings.add("ingest-extract", DailyWorkflowCorpusV2.VERSION,
                        DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                        "extracted-content preview 200",
                        "extracted-content preview HTTP " + preview.status(),
                        "documents/extracted-content", "synthetic fixture", "doc=" + documentId,
                        "none");
                return false;
            }
            if (leaksPrivateMaterial(preview.body()) || leaksPrivateMaterial(chunks.body())) {
                findings.add("ingest-extract", DailyWorkflowCorpusV2.VERSION,
                        DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                        "no path/secret/exception material in extraction responses",
                        "extraction response leaks path or exception material",
                        "documents/extract", "synthetic fixture", "doc=" + documentId,
                        "challenge-case-2");
                return false;
            }
        }
        // Current-chunks proof: every chunk of the revision v1 doc resolves CURRENT.
        var revisionChunks = http.get("/api/v1/documents/" + revisionDocIdV1 + "/chunks");
        for (JsonNode chunk : revisionChunks.json(http.mapper()).path("data")) {
            long chunkId = chunk.path("id").asLong(-1);
            var locator = http.get("/api/v1/source-chunks/" + chunkId + "/locator");
            if (locator.status() != 200
                    || !"CURRENT".equals(locator.json(http.mapper()).path("data")
                    .path("currentness").asText(""))) {
                findings.add("ingest-extract", "daily-revision.md",
                        DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                        "revision v1 chunks resolve CURRENT before revision",
                        "locator HTTP " + locator.status() + " for chunk " + chunkId,
                        "source-chunks/locator", "synthetic revision v1", "chunk=" + chunkId,
                        "none");
                return false;
            }
            if (revisionOldChunkId < 0) {
                revisionOldChunkId = chunkId;
            }
        }
        findings.add("ingest-extract", DailyWorkflowCorpusV2.VERSION,
                DailyWorkflowFindingReport.Category.KNOWLEDGE_MAINTENANCE, false, true,
                "5 fixtures extracted PROCESSED with current chunks + locator",
                documentIds.size() + " documents extracted, revision v1 current",
                "inbox/documents/chunks/locator", "synthetic corpus v2",
                "docs=" + documentIds.size(),
                "none");
        return true;
    }

    // Structure observable ---------------------------------------------------

    void structureObservable() {
        // The guide carries markdown headings + a table; the v1 chunker must keep
        // heading context instead of returning blank section/headingPath.
        boolean observed = false;
        int checked = 0;
        for (long documentId : documentIds) {
            var chunks = http.get("/api/v1/documents/" + documentId + "/chunks");
            if (chunks.status() != 200) {
                continue;
            }
            for (JsonNode chunk : chunks.json(http.mapper()).path("data")) {
                checked++;
                String headingPath = chunk.path("headingPath").asText("");
                String section = chunk.path("section").asText("");
                if (!headingPath.isBlank() || !section.isBlank()) {
                    observed = true;
                }
            }
        }
        if (observed) {
            findings.add("structure-observable", "daily-guide.md",
                    DailyWorkflowFindingReport.Category.RETRIEVAL_QUALITY, false, true,
                    "heading/table context observable via chunk section/headingPath",
                    "heading context observed across " + checked + " chunks",
                    "documents/chunks", "synthetic markdown", "chunks=" + checked,
                    "structure-aware-chunking");
        } else {
            findings.add("structure-observable", "daily-guide.md",
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "heading/table context observable via chunk section/headingPath",
                    "no chunk carried section/headingPath across " + checked + " chunks",
                    "documents/chunks", "synthetic markdown", "chunks=" + checked,
                    "structure-aware-chunking");
        }
    }

    // Baseline retrieval -----------------------------------------------------

    void baselineRetrieval() {
        if (!inspectorFinds(DailyWorkflowCorpusV2.QUESTION, true)) {
            return;
        }
        if (!inspectorFinds(DailyWorkflowCorpusV2.DAILY_ANCHOR, true)) {
            return;
        }
        if (!inspectorFinds(DailyWorkflowCorpusV2.PROPERTY_LINE, true)) {
            return;
        }
        if (!inspectorFinds(DailyWorkflowCorpusV2.CODE_ANCHOR, true)) {
            return;
        }
        findings.add("baseline-retrieval", DailyWorkflowCorpusV2.VERSION,
                DailyWorkflowFindingReport.Category.RETRIEVAL_QUALITY, false, true,
                "CJK + exact-token + property + code anchors retrievable via HYBRID_FTS",
                "daily anchor, property line and code anchor all retrievable",
                "retrieval/inspect", "synthetic corpus v2", "probes=4",
                "none");
    }

    private boolean inspectorFinds(String question, boolean expectHits) {
        var inspect = http.get("/api/v1/retrieval/inspect?question="
                + ProductAcceptanceHttpClient.encode(question) + "&mode=HYBRID_FTS");
        if (inspect.status() != 200) {
            findings.add("baseline-retrieval", DailyWorkflowCorpusV2.VERSION,
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "inspector 200 with candidate evidence",
                    "inspector HTTP " + inspect.status(),
                    "retrieval/inspect", "synthetic corpus v2", "http=" + inspect.status(),
                    "none");
            return false;
        }
        int candidates = inspect.json(http.mapper()).path("data").path("searchedCandidateCount")
                .asInt(-1);
        int fused = inspect.json(http.mapper()).path("data").path("fusedOrder").size();
        int evidence = inspect.json(http.mapper()).path("data").path("finalEvidence").size();
        boolean hasHits = candidates > 0 || fused > 0 || evidence > 0;
        if (expectHits && !hasHits) {
            findings.add("baseline-retrieval", DailyWorkflowCorpusV2.VERSION,
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "inspector finds candidates for daily anchor probe",
                    "inspector found no candidates",
                    "retrieval/inspect", "synthetic corpus v2", "hits=0",
                    "none");
            return false;
        }
        if (!expectHits && hasHits) {
            findings.add("no-answer-probe", DailyWorkflowCorpusV2.VERSION,
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "no-evidence probe returns zero candidates",
                    "inspector returned hits for the no-evidence token",
                    "retrieval/inspect", "synthetic corpus v2",
                    "candidates=" + candidates + " fused=" + fused,
                    "none");
            return false;
        }
        if (leaksPrivateMaterial(inspect.body())) {
            findings.add("baseline-retrieval", DailyWorkflowCorpusV2.VERSION,
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "no path/secret/exception material in inspector response",
                    "inspector response leaks material",
                    "retrieval/inspect", "synthetic corpus v2", "leak",
                    "challenge-case-2");
            return false;
        }
        return true;
    }

    // No-answer / insufficient-evidence ---------------------------------------

    void noAnswerProbe() {
        if (!inspectorFinds(DailyWorkflowCorpusV2.NO_ANSWER_QUESTION, false)) {
            return;
        }
        var ask = http.postJson("/api/v1/ask",
                "{\"question\":" + jsonString(DailyWorkflowCorpusV2.NO_ANSWER_QUESTION)
                        + ",\"retrievalMode\":\"HYBRID_FTS\"}");
        if (ask.status() != 200) {
            findings.add("no-answer-probe", DailyWorkflowCorpusV2.VERSION,
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "no-evidence Ask 200 INSUFFICIENT_EVIDENCE",
                    "Ask HTTP " + ask.status(),
                    "ask", "synthetic no-evidence probe", "http=" + ask.status(),
                    "none");
            return;
        }
        String status = ask.json(http.mapper()).path("data").path("status").asText("");
        if (!"INSUFFICIENT_EVIDENCE".equals(status)) {
            findings.add("no-answer-probe", DailyWorkflowCorpusV2.VERSION,
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "no-evidence Ask returns INSUFFICIENT_EVIDENCE without fabrication",
                    "Ask status=" + status + " (must not fabricate grounded answer)",
                    "ask", "synthetic no-evidence probe", "status=" + status,
                    "none");
            return;
        }
        if (ask.json(http.mapper()).path("data").path("citations").size() != 0) {
            findings.add("no-answer-probe", DailyWorkflowCorpusV2.VERSION,
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "insufficient-evidence carries zero citations",
                    "insufficient-evidence carried citations",
                    "ask", "synthetic no-evidence probe", "citations>0",
                    "none");
            return;
        }
        findings.add("no-answer-probe", DailyWorkflowCorpusV2.VERSION,
                DailyWorkflowFindingReport.Category.RETRIEVAL_QUALITY, false, true,
                "no-evidence probe: inspector zero hits + Ask INSUFFICIENT_EVIDENCE",
                "inspector zero hits, Ask INSUFFICIENT_EVIDENCE with zero citations",
                "retrieval/inspect+ask", "synthetic no-evidence probe", "hits=0",
                "none");
    }

    // Governed Ask → Proposal → Draft → Review → Publish -----------------------

    boolean governedAskAndPublish() {
        int before = proposalCount();
        if (before != 0) {
            findings.add("governed-mutation", DailyWorkflowCorpusV2.VERSION,
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "fresh workspace starts with zero proposals",
                    "proposal count=" + before + " before explicit ingress",
                    "proposals", "temp workspace", "count=" + before,
                    "challenge-case-5");
            return false;
        }
        var ask = http.postJson("/api/v1/ask",
                "{\"question\":" + jsonString(DailyWorkflowCorpusV2.QUESTION)
                        + ",\"retrievalMode\":\"HYBRID_FTS\"}");
        if (ask.status() != 200) {
            findings.add("governed-mutation", DailyWorkflowCorpusV2.VERSION,
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "grounded Ask 200 ANSWERED with citations",
                    "grounded Ask HTTP " + ask.status(),
                    "ask", "synthetic corpus v2", "http=" + ask.status(),
                    "none");
            return false;
        }
        JsonNode data = ask.json(http.mapper()).path("data");
        if (!"ANSWERED".equals(data.path("status").asText(""))) {
            findings.add("governed-mutation", DailyWorkflowCorpusV2.VERSION,
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "grounded Ask ANSWERED with citations",
                    "grounded Ask status=" + data.path("status").asText(""),
                    "ask", "synthetic corpus v2", "status=" + data.path("status").asText(""),
                    "none");
            return false;
        }
        if (data.path("citations").size() == 0) {
            findings.add("governed-mutation", DailyWorkflowCorpusV2.VERSION,
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "grounded Ask carries citations",
                    "grounded Ask returned no citations",
                    "ask", "synthetic corpus v2", "citations=0",
                    "none");
            return false;
        }
        if (ask.body().contains("127.0.0.1:") || ask.body().toLowerCase().contains("apikey")) {
            findings.add("governed-mutation", DailyWorkflowCorpusV2.VERSION,
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "Ask response carries no transport/credential material",
                    "Ask response leaks transport or credential material",
                    "ask", "synthetic corpus v2", "leak",
                    "challenge-case-2");
            return false;
        }
        askResponse = data;
        JsonNode first = data.path("citations").get(0);
        long chunkId = first.path("provenance").path("sourceChunkId").asLong(-1);
        var locator = http.get("/api/v1/source-chunks/" + chunkId + "/locator");
        if (locator.status() != 200
                || !"CURRENT".equals(locator.json(http.mapper()).path("data")
                .path("currentness").asText(""))) {
            findings.add("governed-mutation", DailyWorkflowCorpusV2.VERSION,
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "first citation locator CURRENT",
                    "citation locator HTTP " + locator.status(),
                    "source-chunks/locator", "synthetic citation", "chunk=" + chunkId,
                    "none");
            return false;
        }
        if (!createAskProposal(chunkId)) {
            return false;
        }
        if (!rejectInvalidCitation()) {
            return false;
        }
        if (!approveWithoutPublish()) {
            return false;
        }
        if (!draftPublishRead()) {
            return false;
        }
        findings.add("governed-mutation", DailyWorkflowCorpusV2.VERSION,
                DailyWorkflowFindingReport.Category.KNOWLEDGE_MAINTENANCE, false, true,
                "Ask→Proposal→Draft→Review→Publish with no auto-publish",
                "governed publish complete; proposal=" + proposalId,
                "ask/proposals/drafts/wiki", "synthetic corpus v2",
                "proposal=" + proposalId,
                "challenge-case-5+6");
        return true;
    }

    private boolean createAskProposal(long chunkId) {
        String body = "{\"question\":" + jsonString(DailyWorkflowCorpusV2.QUESTION)
                + ",\"answerText\":" + jsonString(askResponse.path("answer").asText(""))
                + ",\"provider\":\"openai-compatible\",\"model\":"
                + jsonString(DeterministicAcceptanceProviderStub.MODEL)
                + ",\"citations\":[{\"evidenceId\":\"E1\",\"kind\":\"SOURCE\",\"sourceChunkId\":"
                + chunkId + "}]}";
        var created = http.postJson("/api/v1/ask/proposals", body);
        if (created.status() != 201 && created.status() != 200) {
            findings.add("governed-mutation", DailyWorkflowCorpusV2.VERSION,
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "explicit Ask→Proposal ingress 200/201",
                    "Ask proposal HTTP " + created.status(),
                    "ask/proposals", "synthetic citation", "http=" + created.status(),
                    "challenge-case-5");
            return false;
        }
        proposalId = created.json(http.mapper()).path("data").path("proposal").path("id")
                .asLong(-1);
        if (proposalId <= 0 || proposalCount() != 1) {
            findings.add("governed-mutation", DailyWorkflowCorpusV2.VERSION,
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "proposal count 0→1 after explicit ingress",
                    "proposal id=" + proposalId + " count=" + proposalCount(),
                    "ask/proposals", "synthetic citation", "proposal=" + proposalId,
                    "challenge-case-5");
            return false;
        }
        return true;
    }

    private boolean rejectInvalidCitation() {
        String body = "{\"question\":\"q\",\"answerText\":\"a\",\"provider\":\"p\",\"model\":\"m\","
                + "\"citations\":[{\"evidenceId\":\"E1\",\"kind\":\"SOURCE\",\"sourceChunkId\":987654321}]}";
        var rejected = http.postJson("/api/v1/ask/proposals", body);
        if (rejected.status() != 422 || proposalCount() != 1) {
            findings.add("governed-mutation", DailyWorkflowCorpusV2.VERSION,
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "invalid citation typed 422 without persisting",
                    "invalid citation HTTP " + rejected.status() + " count=" + proposalCount(),
                    "ask/proposals", "synthetic citation", "http=" + rejected.status(),
                    "none");
            return false;
        }
        return true;
    }

    private boolean approveWithoutPublish() {
        var approved = http.patchJson("/api/v1/proposals/" + proposalId + "/status",
                "{\"status\":\"APPROVED\"}");
        if (approved.status() != 200) {
            findings.add("governed-mutation", DailyWorkflowCorpusV2.VERSION,
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "proposal approve 200 without auto-publish",
                    "proposal approve HTTP " + approved.status(),
                    "proposals/status", "synthetic proposal", "http=" + approved.status(),
                    "challenge-case-6");
            return false;
        }
        var wiki = http.get("/api/v1/wiki?page=0&size=5");
        // The v2 run may already hold zero wiki pages: approve must not create one.
        // A pre-existing page from an earlier step would already have failed the
        // zero-proposal precondition, so any page here means auto-publish.
        if (wiki.status() == 200 && wiki.json(http.mapper()).path("page").path("totalElements")
                .asInt(0) != 0) {
            findings.add("governed-mutation", DailyWorkflowCorpusV2.VERSION,
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "approve does not auto-publish wiki content",
                    "wiki pages exist before explicit publish",
                    "proposals/wiki", "synthetic proposal", "auto-publish",
                    "challenge-case-6");
            return false;
        }
        return true;
    }

    private boolean draftPublishRead() {
        var draft = http.postJson("/api/v1/wiki-drafts", "{\"proposalId\":" + proposalId + "}");
        if (draft.status() != 201 && draft.status() != 200) {
            findings.add("governed-mutation", DailyWorkflowCorpusV2.VERSION,
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "draft create 200/201 + preview/diff + publish + read",
                    "draft create HTTP " + draft.status(),
                    "wiki-drafts", "synthetic proposal", "http=" + draft.status(),
                    "none");
            return false;
        }
        long draftId = draft.json(http.mapper()).path("data").path("id").asLong(-1);
        if (draftId <= 0
                || http.get("/api/v1/wiki-drafts/" + draftId + "/preview").status() != 200
                || http.get("/api/v1/wiki-drafts/" + draftId + "/diff").status() != 200) {
            findings.add("governed-mutation", DailyWorkflowCorpusV2.VERSION,
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "draft preview/diff available",
                    "draft preview/diff unavailable draft=" + draftId,
                    "wiki-drafts", "synthetic proposal", "draft=" + draftId,
                    "none");
            return false;
        }
        var published = http.postJson("/api/v1/wiki-drafts/" + draftId + "/publish", "{}");
        if (published.status() != 201 && published.status() != 200) {
            findings.add("governed-mutation", DailyWorkflowCorpusV2.VERSION,
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "explicit draft publish 200/201",
                    "draft publish HTTP " + published.status(),
                    "wiki-drafts/publish", "synthetic proposal", "http=" + published.status(),
                    "challenge-case-6");
            return false;
        }
        var list = http.get("/api/v1/wiki?page=0&size=20");
        if (list.status() != 200
                || list.json(http.mapper()).path("page").path("totalElements").asInt(0) < 1) {
            findings.add("governed-mutation", DailyWorkflowCorpusV2.VERSION,
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "published wiki readable after explicit publish",
                    "published wiki not readable after explicit publish",
                    "wiki", "synthetic proposal", "unreadable",
                    "none");
            return false;
        }
        JsonNode first = list.json(http.mapper()).path("data").get(0);
        if (first.path("knowledgeId").asText("").isBlank()
                || first.path("contentHash").asText("").isBlank()
                || http.get("/api/v1/wiki/" + first.path("knowledgeId").asText("")).status() != 200) {
            findings.add("governed-mutation", DailyWorkflowCorpusV2.VERSION,
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "published wiki read with knowledgeId/contentHash",
                    "published wiki missing identity or unreadable",
                    "wiki", "synthetic proposal", "identity-missing",
                    "none");
            return false;
        }
        return true;
    }

    // Source revision / re-extraction / stale-currentness ----------------------

    void sourceRevision() {
        if (revisionDocIdV1 <= 0 || revisionOldChunkId <= 0
                || revisionStoredFileName.isBlank()) {
            findings.add("source-revision", "daily-revision.md",
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "revision v1 identity captured during ingest",
                    "revision v1 identity missing",
                    "inbox/documents", "synthetic revision v1", "missing-identity",
                    "none");
            return;
        }
        // Positive control: V1 anchor retrievable before the user edit.
        if (!inspectorFinds(DailyWorkflowCorpusV2.REVISION_ANCHOR_V1, true)) {
            findings.add("source-revision", "daily-revision.md",
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "V1 anchor retrievable before revision",
                    "V1 anchor not retrievable before revision",
                    "retrieval/inspect", "synthetic revision v1", "v1-missing",
                    "none");
            return;
        }
        // User file edit (inbox-scoped) + public rescan: the only supported
        // replacement path. No DB insert, no canonical vault write, no archive write.
        try {
            Path inboxFile = workspaceRoot.resolve("inbox").resolve(revisionStoredFileName);
            Files.writeString(inboxFile, DailyWorkflowCorpusV2.revisionV2(),
                    StandardCharsets.UTF_8);
        } catch (Exception failure) {
            findings.add("source-revision", "daily-revision.md",
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "user revision file edit applicable",
                    "cannot overwrite inbox revision file",
                    "inbox (user edit)", "synthetic revision v2", "io-failure",
                    "none");
            return;
        }
        var rescanned = http.postJson("/api/v1/inbox/rescan", "{}");
        if (rescanned.status() != 200) {
            findings.add("source-revision", "daily-revision.md",
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "rescan 200 registers the revised version",
                    "rescan HTTP " + rescanned.status(),
                    "inbox/rescan", "synthetic revision v2", "http=" + rescanned.status(),
                    "none");
            return;
        }
        revisionDocIdV2 = discoverRevisionV2();
        if (revisionDocIdV2 <= 0) {
            findings.add("source-revision", "daily-revision.md",
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "revised document discoverable via public inbox list",
                    "revised document not discoverable after rescan",
                    "inbox", "synthetic revision v2", "undiscoverable",
                    "none");
            return;
        }
        var extracted = http.postJson("/api/v1/documents/" + revisionDocIdV2 + "/extract", "{}");
        if (extracted.status() != 200
                || !"PROCESSED".equals(extracted.json(http.mapper()).path("data")
                .path("parseStatus").asText(""))) {
            findings.add("source-revision", "daily-revision.md",
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "revised document extracts PROCESSED",
                    "re-extract HTTP " + extracted.status(),
                    "documents/extract", "synthetic revision v2", "doc=" + revisionDocIdV2,
                    "none");
            return;
        }
        // Negative control 1: the old chunk must never pose as CURRENT after
        // supersede. Production contract (SourceChunkLocatorIntegrationTest):
        // superseded/deleted chunks share safe 404 not-found, ineligible but
        // present chunks are NOT_CURRENT with a reason and no preview. Both are
        // safe; only CURRENT with content would be fake-current (challenge 3).
        var oldLocator = http.get("/api/v1/source-chunks/" + revisionOldChunkId + "/locator");
        if (oldLocator.status() == 404) {
            // Safe not-found for superseded revision: no content leak, no CURRENT.
        } else if (oldLocator.status() == 200) {
            JsonNode oldData = oldLocator.json(http.mapper()).path("data");
            if ("CURRENT".equals(oldData.path("currentness").asText(""))) {
                findings.add("source-revision", "daily-revision.md",
                        DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                        "old locator never CURRENT after supersede (404 or NOT_CURRENT)",
                        "old locator still CURRENT after supersede",
                        "source-chunks/locator", "superseded revision v1",
                        "chunk=" + revisionOldChunkId,
                        "challenge-case-3");
                return;
            }
            if (!"NOT_CURRENT".equals(oldData.path("currentness").asText(""))
                    || oldData.path("notCurrentReason").asText("").isBlank()
                    || !oldData.path("preview").isNull()) {
                findings.add("source-revision", "daily-revision.md",
                        DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                        "old locator NOT_CURRENT + reason + no preview after supersede",
                        "old locator currentness=" + oldData.path("currentness").asText(""),
                        "source-chunks/locator", "superseded revision v1",
                        "chunk=" + revisionOldChunkId,
                        "challenge-case-3");
                return;
            }
        } else {
            findings.add("source-revision", "daily-revision.md",
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "old locator safe 404 or NOT_CURRENT after supersede",
                    "old locator HTTP " + oldLocator.status(),
                    "source-chunks/locator", "superseded revision v1",
                    "chunk=" + revisionOldChunkId,
                    "challenge-case-3");
            return;
        }
        // Negative control 2: V1 anchor must no longer be served; V2 must be.
        if (!inspectorAbsent(DailyWorkflowCorpusV2.REVISION_ANCHOR_V1)) {
            return;
        }
        if (!inspectorFinds(DailyWorkflowCorpusV2.REVISION_ANCHOR_V2, true)) {
            findings.add("source-revision", "daily-revision.md",
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "V2 anchor retrievable after re-extraction",
                    "V2 anchor not retrievable after re-extraction",
                    "retrieval/inspect", "synthetic revision v2", "v2-missing",
                    "none");
            return;
        }
        // Negative control 3: stale citation must fail closed via Ask→Proposal.
        String staleBody = "{\"question\":\"q\",\"answerText\":\"a\",\"provider\":\"p\",\"model\":\"m\","
                + "\"citations\":[{\"evidenceId\":\"E1\",\"kind\":\"SOURCE\",\"sourceChunkId\":"
                + revisionOldChunkId + "}]}";
        var staleProposal = http.postJson("/api/v1/ask/proposals", staleBody);
        if (staleProposal.status() != 422) {
            findings.add("source-revision", "daily-revision.md",
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "stale citation typed 422 without persisting",
                    "stale citation HTTP " + staleProposal.status(),
                    "ask/proposals", "superseded revision v1", "http=" + staleProposal.status(),
                    "challenge-case-3");
            return;
        }
        findings.add("source-revision", "daily-revision.md",
                DailyWorkflowFindingReport.Category.KNOWLEDGE_MAINTENANCE, false, true,
                "revision v1→v2: old never CURRENT (404/NOT_CURRENT), V1 unserved, V2 served, stale 422",
                "old chunk safe 404/NOT_CURRENT, V1 unserved, V2 served, stale citation 422",
                "inbox/rescan+extract/locator/inspect/proposals",
                "synthetic revision v1→v2",
                "oldChunk=" + revisionOldChunkId + " newDoc=" + revisionDocIdV2,
                "none");
    }

    private long discoverRevisionV2() {
        var list = http.get("/api/v1/inbox?page=0&size=200");
        if (list.status() != 200) {
            return -1;
        }
        long best = -1;
        for (JsonNode row : list.json(http.mapper()).path("data")) {
            String fileName = row.path("fileName").asText("");
            long documentId = row.path("documentId").asLong(-1);
            if (revisionStoredFileName.equals(fileName) && documentId != revisionDocIdV1) {
                best = Math.max(best, documentId);
            }
        }
        return best;
    }

    private boolean inspectorAbsent(String token) {
        var inspect = http.get("/api/v1/retrieval/inspect?question="
                + ProductAcceptanceHttpClient.encode(token) + "&mode=HYBRID_FTS");
        if (inspect.status() != 200) {
            findings.add("source-revision", "daily-revision.md",
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "post-revision inspector 200",
                    "inspector HTTP " + inspect.status(),
                    "retrieval/inspect", "synthetic revision v2", "http=" + inspect.status(),
                    "none");
            return false;
        }
        int candidates = inspect.json(http.mapper()).path("data").path("searchedCandidateCount")
                .asInt(0);
        int fused = inspect.json(http.mapper()).path("data").path("fusedOrder").size();
        int evidence = inspect.json(http.mapper()).path("data").path("finalEvidence").size();
        if (candidates > 0 || fused > 0 || evidence > 0) {
            findings.add("source-revision", "daily-revision.md",
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "superseded V1 anchor no longer served",
                    "V1 anchor still served candidates=" + candidates,
                    "retrieval/inspect", "superseded revision v1", "stale-served",
                    "challenge-case-3");
            return false;
        }
        return true;
    }

    // Quality boundary (read-only) --------------------------------------------

    void qualityBoundary() {
        var found = http.get("/api/v1/vault-lint/findings");
        if (found.status() != 200) {
            findings.add("quality-boundary", DailyWorkflowCorpusV2.VERSION,
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "vault-lint findings 200 read-only",
                    "vault-lint findings HTTP " + found.status(),
                    "vault-lint/findings", "published wiki", "http=" + found.status(),
                    "none");
            return;
        }
        if (leaksPrivateMaterial(found.body())) {
            findings.add("quality-boundary", DailyWorkflowCorpusV2.VERSION,
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "no path/secret/exception material in lint findings",
                    "vault-lint leaks path or exception material",
                    "vault-lint/findings", "published wiki", "leak",
                    "challenge-case-2");
            return;
        }
        int before = found.json(http.mapper()).path("data").path("findings").size();
        var reread = http.get("/api/v1/vault-lint/findings");
        int after = reread.json(http.mapper()).path("data").path("findings").size();
        if (before != after) {
            findings.add("quality-boundary", DailyWorkflowCorpusV2.VERSION,
                    DailyWorkflowFindingReport.Category.PRODUCT_BUG, true, true,
                    "vault-lint read does not mutate finding state",
                    "vault-lint read mutated finding state",
                    "vault-lint/findings", "published wiki", "before=" + before,
                    "none");
            return;
        }
        findings.add("quality-boundary", DailyWorkflowCorpusV2.VERSION,
                DailyWorkflowFindingReport.Category.OPERABILITY, false, true,
                "vault-lint read-only with stable finding set",
                "vault-lint read-only, findings=" + before,
                "vault-lint/findings", "published wiki", "findings=" + before,
                "none");
    }

    // Helpers ------------------------------------------------------------------

    private int proposalCount() {
        var list = http.get("/api/v1/proposals?page=0&size=1");
        if (list.status() != 200) {
            return -1;
        }
        return list.json(http.mapper()).path("page").path("totalElements").asInt(-1);
    }

    private static boolean leaksPrivateMaterial(String body) {
        return body != null && (body.contains("/Users/") || body.contains("/home/")
                || body.contains("/tmp/") || body.contains("Exception"));
    }

    static <T> T pollWithDeadline(Supplier<T> probe, java.util.function.Predicate<T> done,
                                  Duration deadline) {
        Instant end = Instant.now().plus(deadline);
        T last = null;
        while (Instant.now().isBefore(end)) {
            last = probe.get();
            if (done.test(last)) {
                return last;
            }
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while polling", interrupted);
            }
        }
        return last;
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
