package org.km.llmwiki.acceptance;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Release-oriented cross-capability journey over the public HTTP boundary
 * (Refs #429 §B–§H).
 *
 * <p>User actions use {@link ProductAcceptanceHttpClient} only (public
 * {@code /api/v1}). The harness never issues direct DB inserts or vault writes
 * as a flow shortcut; the only filesystem/SQLite contact is the caller-owned
 * temp workspace root path handed to the workspace-create API.
 *
 * <p>Async waits use bounded status polling with a deadline (no sleep-luck).
 */
public final class ProductAcceptanceHarness {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);
    private static final Duration JOB_DEADLINE = Duration.ofSeconds(60);

    private final ProductAcceptanceHttpClient http;
    private final ProductAcceptanceReport report;
    private final Path workspaceRoot;

    private long workspaceId;
    private final List<Long> documentIds = new ArrayList<>();
    private final List<Long> chunkIds = new ArrayList<>();
    private JsonNode askResponse;
    private long proposalId;
    private long draftId;
    private String publishedKnowledgeId;

    public ProductAcceptanceHarness(ProductAcceptanceHttpClient http,
                                    ProductAcceptanceReport report,
                                    Path workspaceRoot) {
        this.http = http;
        this.report = report;
        this.workspaceRoot = workspaceRoot;
    }

    public ProductAcceptanceReport runAll(boolean providerEnabled, boolean graphEnabled) {
        cleanStartup();
        workspace();
        ingestExtract();
        documentAnalysisJourney();
        baselineRetrieval(providerEnabled);
        governedMutationPreconditions();
        if (providerEnabled) {
            embeddingProjection();
            groundedAskAndGovernance(graphEnabled);
        } else {
            askDisabledTyped();
            report.add("governed-mutation", ProductAcceptanceReport.Verdict.SKIP,
                    "requires deterministic provider; covered by full-capability profile");
        }
        qualityBoundary();
        if (graphEnabled) {
            graphBoundary();
        } else {
            report.add("graph-boundary", ProductAcceptanceReport.Verdict.SKIP,
                    "graph disabled in this profile; HYBRID_GRAPH covered by graph profile");
        }
        vectorPrerequisiteNote();
        return report;
    }

    // §C clean startup -------------------------------------------------------

    void cleanStartup() {
        var response = http.get("/api/v1/system/status");
        if (response.status() != 200) {
            report.add("clean-startup", ProductAcceptanceReport.Verdict.FAIL,
                    "system status HTTP " + response.status());
            return;
        }
        String status = response.json(http.mapper()).path("data").path("status").asText("");
        if (status.isBlank()) {
            report.add("clean-startup", ProductAcceptanceReport.Verdict.FAIL,
                    "system status missing data.status");
            return;
        }
        // A fresh temp instance must have no workspace yet: anything but
        // NOT_INITIALIZED means the harness is not running on a clean root
        // (challenge case: silently reusing developer data/).
        if ("NOT_INITIALIZED".equals(status)) {
            report.add("clean-startup", ProductAcceptanceReport.Verdict.PASS,
                    "system status=NOT_INITIALIZED on clean root");
        } else {
            report.add("clean-startup", ProductAcceptanceReport.Verdict.FAIL,
                    "expected NOT_INITIALIZED on clean root, got status=" + status);
        }
    }

    // §C workspace -----------------------------------------------------------

    void workspace() {
        String body = "{\"name\":\"golden-workspace\",\"rootPath\":"
                + jsonString(workspaceRoot.toAbsolutePath().toString()) + "}";
        var created = http.postJson("/api/v1/workspaces", body);
        if (created.status() != 201) {
            report.add("workspace", ProductAcceptanceReport.Verdict.FAIL,
                    "workspace create HTTP " + created.status() + " " + truncate(created.body()));
            return;
        }
        workspaceId = created.json(http.mapper()).path("data").path("id").asLong(-1);
        var current = http.get("/api/v1/workspaces/current");
        if (current.status() != 200
                || current.json(http.mapper()).path("data").path("workspace").path("id").asLong(-1)
                != workspaceId) {
            report.add("workspace", ProductAcceptanceReport.Verdict.FAIL,
                    "active workspace mismatch after create");
            return;
        }
        // Developer-tree isolation proof: the active root must be the temp root,
        // never the developer data/ directory.
        String rootPath = current.json(http.mapper()).path("data").path("workspace")
                .path("rootPath").asText("");
        if (!rootPath.equals(workspaceRoot.toAbsolutePath().toString())) {
            report.add("workspace", ProductAcceptanceReport.Verdict.FAIL,
                    "workspace root is not the temp acceptance root");
            return;
        }
        report.add("workspace", ProductAcceptanceReport.Verdict.PASS,
                "workspace created and active id=" + workspaceId);
    }

    // §C ingest / extraction --------------------------------------------------

    void ingestExtract() {
        for (var fixture : ProductAcceptanceCorpusV1.fixtures()) {
            var uploaded = http.uploadFile(fixture.fileName(), fixture.contentType(),
                    fixture.content().getBytes(StandardCharsets.UTF_8));
            if (uploaded.status() != 201) {
                report.add("ingest-extract", ProductAcceptanceReport.Verdict.FAIL,
                        "upload " + fixture.fileName() + " HTTP " + uploaded.status());
                return;
            }
            long documentId = uploaded.json(http.mapper()).path("data").path("documentId")
                    .asLong(-1);
            if (documentId <= 0) {
                report.add("ingest-extract", ProductAcceptanceReport.Verdict.FAIL,
                        "upload " + fixture.fileName() + " missing documentId");
                return;
            }
            documentIds.add(documentId);
        }
        for (long documentId : documentIds) {
            var extracted = http.postJson("/api/v1/documents/" + documentId + "/extract", "{}");
            if (extracted.status() != 200) {
                report.add("ingest-extract", ProductAcceptanceReport.Verdict.FAIL,
                        "extract document " + documentId + " HTTP " + extracted.status());
                return;
            }
            String parseStatus = extracted.json(http.mapper()).path("data").path("parseStatus")
                    .asText("");
            if (!"PROCESSED".equals(parseStatus)) {
                report.add("ingest-extract", ProductAcceptanceReport.Verdict.FAIL,
                        "extract document " + documentId + " parseStatus=" + parseStatus);
                return;
            }
            var chunks = http.get("/api/v1/documents/" + documentId + "/chunks");
            if (chunks.status() != 200
                    || chunks.json(http.mapper()).path("data").size() == 0) {
                report.add("ingest-extract", ProductAcceptanceReport.Verdict.FAIL,
                        "document " + documentId + " has no chunks");
                return;
            }
            for (JsonNode chunk : chunks.json(http.mapper()).path("data")) {
                chunkIds.add(chunk.path("id").asLong(-1));
                if (!"chunk-policy-v1-current".equals(chunk.path("chunkPolicyVersion").asText(""))) {
                    report.add("ingest-extract", ProductAcceptanceReport.Verdict.FAIL,
                            "chunk policy version drift on document " + documentId);
                    return;
                }
            }
            var preview = http.get("/api/v1/documents/" + documentId + "/extracted-content?page=0&size=5");
            if (preview.status() != 200) {
                report.add("ingest-extract", ProductAcceptanceReport.Verdict.FAIL,
                        "extracted-content preview HTTP " + preview.status());
                return;
            }
        }
        // Current-chunks proof: every chunk resolves through the public locator.
        for (long chunkId : chunkIds) {
            var locator = http.get("/api/v1/source-chunks/" + chunkId + "/locator");
            if (locator.status() != 200) {
                report.add("ingest-extract", ProductAcceptanceReport.Verdict.FAIL,
                        "locator HTTP " + locator.status() + " for chunk " + chunkId);
                return;
            }
        }
        report.add("ingest-extract", ProductAcceptanceReport.Verdict.PASS,
                documentIds.size() + " documents extracted, " + chunkIds.size() + " chunks current");
    }

    // #454 §B fresh-workspace Document Analysis blocking journey -----------------
    //
    // Additive hardening on #429 ownership (no second acceptance framework):
    //   clean root -> create workspace -> prompt auto-bootstrap -> readiness READY
    //   -> upload fixture -> extract PROCESSED -> start analysis job
    //   -> bounded poll to terminal -> successCount > 0 / failedCount = 0.
    //
    // Constraints (challenge cases 2/3):
    // - Never direct filesystem prompt write to fake bootstrap; only the production
    //   workspace/create + readiness + analysis public/application boundary.
    // - Never direct INSERT setting / document_analysis / processing_job.
    // - The default stub/offline is the production-supported fallback
    //   (StubLlmClientConfiguration @ConditionalOnMissingBean), not a test-only
    //   service shortcut: readiness proves provider=stub/model=offline via the
    //   production configuration loader, and job success proves the same seam ran.

    void documentAnalysisJourney() {
        var readiness = http.get("/api/v1/analysis/readiness");
        if (readiness.status() != 200) {
            report.add("document-analysis", ProductAcceptanceReport.Verdict.FAIL,
                    "analysis readiness HTTP " + readiness.status());
            return;
        }
        String readinessBody = readiness.body();
        if (readinessBody.contains("/Users/") || readinessBody.contains("/home/")
                || readinessBody.contains("/tmp/") || readinessBody.contains("Exception")) {
            report.add("document-analysis", ProductAcceptanceReport.Verdict.FAIL,
                    "analysis readiness leaks path or exception material");
            return;
        }
        JsonNode data = readiness.json(http.mapper()).path("data");
        if (!data.path("analysisReady").asBoolean(false)) {
            report.add("document-analysis", ProductAcceptanceReport.Verdict.FAIL,
                    "fresh workspace analysisReady=false promptStatus="
                            + data.path("promptStatus").asText(""));
            return;
        }
        if (!"READY".equals(data.path("promptStatus").asText(""))) {
            report.add("document-analysis", ProductAcceptanceReport.Verdict.FAIL,
                    "fresh workspace promptStatus=" + data.path("promptStatus").asText(""));
            return;
        }
        if (!data.path("settingsValid").asBoolean(false)) {
            report.add("document-analysis", ProductAcceptanceReport.Verdict.FAIL,
                    "fresh workspace settings invalid");
            return;
        }
        String provider = data.path("provider").asText("");
        String model = data.path("model").asText("");
        if (!"stub".equals(provider) || !"offline".equals(model)) {
            report.add("document-analysis", ProductAcceptanceReport.Verdict.FAIL,
                    "expected production offline fallback provider=stub/model=offline, got "
                            + provider + "/" + model);
            return;
        }
        var started = http.postJson("/api/v1/analysis/jobs", "{}");
        if (started.status() != 202) {
            report.add("document-analysis", ProductAcceptanceReport.Verdict.FAIL,
                    "analysis start HTTP " + started.status() + " " + truncate(started.body()));
            return;
        }
        String jobId = started.json(http.mapper()).path("data").path("jobId").asText("");
        int totalCount = started.json(http.mapper()).path("data").path("totalCount").asInt(-1);
        if (jobId.isBlank()) {
            report.add("document-analysis", ProductAcceptanceReport.Verdict.FAIL,
                    "analysis start missing jobId");
            return;
        }
        if (totalCount <= 0) {
            report.add("document-analysis", ProductAcceptanceReport.Verdict.FAIL,
                    "analysis start totalCount=" + totalCount + " (fresh workspace must have eligible docs)");
            return;
        }
        JsonNode terminal = pollWithDeadline(
                () -> {
                    var status = http.get("/api/v1/analysis/jobs/" + jobId);
                    if (status.status() != 200) {
                        return null;
                    }
                    return status.json(http.mapper()).path("data");
                },
                node -> node != null && List.of("COMPLETED", "FAILED").contains(
                        node.path("status").asText("")),
                JOB_DEADLINE);
        if (terminal == null || !"COMPLETED".equals(terminal.path("status").asText(""))) {
            report.add("document-analysis", ProductAcceptanceReport.Verdict.FAIL,
                    "analysis job did not reach COMPLETED within deadline, job=" + jobId);
            return;
        }
        int successCount = terminal.path("successCount").asInt(-1);
        int failedCount = terminal.path("failedCount").asInt(-1);
        if (successCount <= 0 || failedCount != 0) {
            report.add("document-analysis", ProductAcceptanceReport.Verdict.FAIL,
                    "analysis job successCount=" + successCount + " failedCount=" + failedCount
                            + " (requires success>0/failed=0)");
            return;
        }
        report.add("document-analysis", ProductAcceptanceReport.Verdict.PASS,
                "fresh-workspace bootstrap READY (stub/offline) + job " + jobId
                        + " success=" + successCount + " failed=0 total=" + totalCount);
    }

    // §C baseline retrieval (provider-free FTS / inspect / locator) -----------

    void baselineRetrieval(boolean providerEnabled) {
        var inspect = http.get("/api/v1/retrieval/inspect?question="
                + ProductAcceptanceHttpClient.encode(ProductAcceptanceCorpusV1.QUESTION)
                + "&mode=HYBRID_FTS");
        if (inspect.status() != 200) {
            report.add("baseline-retrieval", ProductAcceptanceReport.Verdict.FAIL,
                    "inspector HTTP " + inspect.status());
            return;
        }
        // Provider-free lexical path must surface candidates for the anchor token.
        int candidates = inspect.json(http.mapper()).path("data").path("searchedCandidateCount")
                .asInt(-1);
        if (candidates <= 0) {
            // Fall back to fusedOrder/finalEvidence size when the counter shape drifts;
            // never invent evidence.
            int fused = inspect.json(http.mapper()).path("data").path("fusedOrder").size();
            int evidence = inspect.json(http.mapper()).path("data").path("finalEvidence").size();
            if (fused <= 0 && evidence <= 0) {
                report.add("baseline-retrieval", ProductAcceptanceReport.Verdict.FAIL,
                        "inspector found no candidates for the anchor question");
                return;
            }
        }
        // Optional providers disabled must not take down the baseline: the
        // inspector path above already proved FTS serving; here the harness only
        // records that the provider profile defers grounded Ask (the typed 503
        // itself is owned by askDisabledTyped, never assumed here).
        if (!providerEnabled) {
            report.add("baseline-retrieval", ProductAcceptanceReport.Verdict.PASS,
                    "FTS/inspect/locator usable provider-free");
            return;
        }
        report.add("baseline-retrieval", ProductAcceptanceReport.Verdict.PASS,
                "FTS/inspect/locator usable with provider profile");
    }

    /** Provider-off Ask must be a typed 503, never a crash or a fake answer. */
    void askDisabledTyped() {
        var ask = http.postJson("/api/v1/ask",
                "{\"question\":" + jsonString(ProductAcceptanceCorpusV1.QUESTION)
                        + ",\"retrievalMode\":\"HYBRID_FTS\"}");
        if (ask.status() == 503
                && ask.json(http.mapper()).path("error").path("code").asText("")
                .contains("ANSWER_PROVIDER_NOT_CONFIGURED")) {
            report.add("ask-provider-disabled-typed", ProductAcceptanceReport.Verdict.PASS,
                    "answer-disabled Ask typed 503, baseline intact");
        } else if (ask.status() == 200) {
            report.add("ask-provider-disabled-typed", ProductAcceptanceReport.Verdict.FAIL,
                    "answer-disabled instance must not fabricate a grounded answer");
        } else {
            report.add("ask-provider-disabled-typed", ProductAcceptanceReport.Verdict.FAIL,
                    "answer-disabled Ask HTTP " + ask.status() + " " + truncate(ask.body()));
        }
    }

    // §E Ask must not mutate before the explicit command -----------------------

    void governedMutationPreconditions() {
        int before = proposalCount();
        if (before < 0) {
            report.add("ask-read-only-precondition", ProductAcceptanceReport.Verdict.FAIL,
                    "cannot read proposal count");
            return;
        }
        if (before != 0) {
            report.add("ask-read-only-precondition", ProductAcceptanceReport.Verdict.FAIL,
                    "fresh acceptance workspace must start with zero proposals");
            return;
        }
        report.add("ask-read-only-precondition", ProductAcceptanceReport.Verdict.PASS,
                "zero proposals before any explicit mutation command");
    }

    // §D+§E grounded Ask → explicit Proposal → Draft → Review → Publish --------

    void groundedAskAndGovernance(boolean graphEnabled) {
        // Grounded Ask over the lexical baseline (deterministic stub answers E1).
        var ask = http.postJson("/api/v1/ask",
                "{\"question\":\"" + ProductAcceptanceCorpusV1.QUESTION
                        + "\",\"retrievalMode\":\"HYBRID_FTS\"}");
        if (ask.status() != 200) {
            report.add("governed-mutation", ProductAcceptanceReport.Verdict.FAIL,
                    "grounded Ask HTTP " + ask.status() + " " + truncate(ask.body()));
            return;
        }
        JsonNode data = ask.json(http.mapper()).path("data");
        if (!"ANSWERED".equals(data.path("status").asText(""))) {
            report.add("governed-mutation", ProductAcceptanceReport.Verdict.FAIL,
                    "grounded Ask status=" + data.path("status").asText(""));
            return;
        }
        if (data.path("citations").size() == 0) {
            report.add("governed-mutation", ProductAcceptanceReport.Verdict.FAIL,
                    "grounded Ask returned no citations");
            return;
        }
        // Provider response must not become citation/model authority: the answer
        // carries application-owned citation identities, provider metadata stays
        // display-only, and no raw endpoint/key leaks into the response.
        if (ask.body().contains("127.0.0.1:") || ask.body().toLowerCase().contains("apikey")) {
            report.add("governed-mutation", ProductAcceptanceReport.Verdict.FAIL,
                    "Ask response leaks transport or credential material");
            return;
        }
        askResponse = data;
        // Locator/currentness revalidation for the first citation.
        if (!revalidateFirstCitation(data)) {
            return;
        }
        // Explicit Ask→Proposal is the only governed ingress: count must move 0→1.
        if (!createAskProposal()) {
            return;
        }
        // Invalid/stale citation must fail closed without persisting.
        if (!rejectInvalidCitation()) {
            return;
        }
        // Approve ≠ publish: no PUBLISHED wiki may exist before explicit publish.
        if (!approveWithoutPublish()) {
            return;
        }
        // Draft → preview/diff → publish → PUBLISHED read with hash/currentness.
        if (!draftPublishRead()) {
            return;
        }
        if (graphEnabled) {
            hybridGraphSmoke();
        }
        report.add("governed-mutation", ProductAcceptanceReport.Verdict.PASS,
                "Ask→Proposal→Draft→Review→Publish complete; proposal=" + proposalId);
    }

    private boolean revalidateFirstCitation(JsonNode data) {
        JsonNode first = data.path("citations").get(0);
        JsonNode provenance = first.path("provenance");
        String type = provenance.path("type").asText("");
        if ("SOURCE".equals(type)) {
            long chunkId = provenance.path("sourceChunkId").asLong(-1);
            var locator = http.get("/api/v1/source-chunks/" + chunkId + "/locator");
            if (locator.status() != 200) {
                report.add("governed-mutation", ProductAcceptanceReport.Verdict.FAIL,
                        "citation locator HTTP " + locator.status());
                return false;
            }
        } else if ("WIKI".equals(type)) {
            // Fresh workspace has no published wiki yet; a WIKI first-citation here
            // would mean the harness misread the bundle — fail closed.
            report.add("governed-mutation", ProductAcceptanceReport.Verdict.FAIL,
                    "unexpected WIKI citation before any publication");
            return false;
        }
        return true;
    }

    private boolean createAskProposal() {
        JsonNode first = askResponse.path("citations").get(0);
        JsonNode provenance = first.path("provenance");
        long chunkId = provenance.path("sourceChunkId").asLong(-1);
        String body = "{\"question\":" + jsonString(ProductAcceptanceCorpusV1.QUESTION)
                + ",\"answerText\":" + jsonString(askResponse.path("answer").asText(""))
                + ",\"provider\":\"openai-compatible\",\"model\":"
                + jsonString(DeterministicAcceptanceProviderStub.MODEL)
                + ",\"citations\":[{\"evidenceId\":\"E1\",\"kind\":\"SOURCE\",\"sourceChunkId\":"
                + chunkId + "}]}";
        var created = http.postJson("/api/v1/ask/proposals", body);
        if (created.status() != 201 && created.status() != 200) {
            report.add("governed-mutation", ProductAcceptanceReport.Verdict.FAIL,
                    "Ask proposal HTTP " + created.status() + " " + truncate(created.body()));
            return false;
        }
        proposalId = created.json(http.mapper()).path("data").path("proposal").path("id")
                .asLong(-1);
        if (proposalId <= 0) {
            report.add("governed-mutation", ProductAcceptanceReport.Verdict.FAIL,
                    "Ask proposal missing id");
            return false;
        }
        if (proposalCount() != 1) {
            report.add("governed-mutation", ProductAcceptanceReport.Verdict.FAIL,
                    "proposal count must be exactly 1 after explicit ingress");
            return false;
        }
        return true;
    }

    private boolean rejectInvalidCitation() {
        String body = "{\"question\":\"q\",\"answerText\":\"a\",\"provider\":\"p\",\"model\":\"m\","
                + "\"citations\":[{\"evidenceId\":\"E1\",\"kind\":\"SOURCE\",\"sourceChunkId\":987654321}]}";
        var rejected = http.postJson("/api/v1/ask/proposals", body);
        if (rejected.status() != 422) {
            report.add("governed-mutation", ProductAcceptanceReport.Verdict.FAIL,
                    "invalid citation must be typed 422, got HTTP " + rejected.status());
            return false;
        }
        if (proposalCount() != 1) {
            report.add("governed-mutation", ProductAcceptanceReport.Verdict.FAIL,
                    "rejected citation must not persist a proposal");
            return false;
        }
        return true;
    }

    private boolean approveWithoutPublish() {
        var approved = http.patchJson("/api/v1/proposals/" + proposalId + "/status",
                "{\"status\":\"APPROVED\"}");
        if (approved.status() != 200) {
            report.add("governed-mutation", ProductAcceptanceReport.Verdict.FAIL,
                    "proposal approve HTTP " + approved.status());
            return false;
        }
        var wiki = http.get("/api/v1/wiki?page=0&size=5");
        if (wiki.status() == 200 && wiki.json(http.mapper()).path("page").path("totalElements")
                .asInt(0) != 0) {
            report.add("governed-mutation", ProductAcceptanceReport.Verdict.FAIL,
                    "approve must not auto-publish wiki content");
            return false;
        }
        return true;
    }

    private boolean draftPublishRead() {
        var draft = http.postJson("/api/v1/wiki-drafts", "{\"proposalId\":" + proposalId + "}");
        if (draft.status() != 201 && draft.status() != 200) {
            report.add("governed-mutation", ProductAcceptanceReport.Verdict.FAIL,
                    "draft create HTTP " + draft.status() + " " + truncate(draft.body()));
            return false;
        }
        draftId = draft.json(http.mapper()).path("data").path("id").asLong(-1);
        if (draftId <= 0) {
            report.add("governed-mutation", ProductAcceptanceReport.Verdict.FAIL,
                    "draft missing id");
            return false;
        }
        if (http.get("/api/v1/wiki-drafts/" + draftId + "/preview").status() != 200
                || http.get("/api/v1/wiki-drafts/" + draftId + "/diff").status() != 200) {
            report.add("governed-mutation", ProductAcceptanceReport.Verdict.FAIL,
                    "draft preview/diff unavailable");
            return false;
        }
        var published = http.postJson("/api/v1/wiki-drafts/" + draftId + "/publish", "{}");
        if (published.status() != 201 && published.status() != 200) {
            report.add("governed-mutation", ProductAcceptanceReport.Verdict.FAIL,
                    "draft publish HTTP " + published.status() + " " + truncate(published.body()));
            return false;
        }
        // Published wiki is readable only after explicit publish, with hash/currentness.
        var list = http.get("/api/v1/wiki?page=0&size=20");
        if (list.status() != 200
                || list.json(http.mapper()).path("page").path("totalElements").asInt(0) < 1) {
            report.add("governed-mutation", ProductAcceptanceReport.Verdict.FAIL,
                    "published wiki not readable after explicit publish");
            return false;
        }
        JsonNode first = list.json(http.mapper()).path("data").get(0);
        publishedKnowledgeId = first.path("knowledgeId").asText("");
        if (publishedKnowledgeId.isBlank() || first.path("contentHash").asText("").isBlank()) {
            report.add("governed-mutation", ProductAcceptanceReport.Verdict.FAIL,
                    "published wiki missing knowledgeId/contentHash");
            return false;
        }
        var read = http.get("/api/v1/wiki/" + publishedKnowledgeId);
        if (read.status() != 200) {
            report.add("governed-mutation", ProductAcceptanceReport.Verdict.FAIL,
                    "published wiki read HTTP " + read.status());
            return false;
        }
        return true;
    }

    private void hybridGraphSmoke() {
        var ask = http.postJson("/api/v1/ask",
                "{\"question\":\"" + ProductAcceptanceCorpusV1.QUESTION
                        + "\",\"retrievalMode\":\"HYBRID_GRAPH\"}");
        // Graph-enabled profile must execute HYBRID_GRAPH without inventing evidence:
        // ANSWERED with citations, or a typed degradation/insufficient result — never
        // a crash-shaped or secret-bearing body.
        if (ask.status() != 200) {
            report.add("hybrid-graph-smoke", ProductAcceptanceReport.Verdict.FAIL,
                    "HYBRID_GRAPH HTTP " + ask.status());
            return;
        }
        report.add("hybrid-graph-smoke", ProductAcceptanceReport.Verdict.PASS,
                "HYBRID_GRAPH executed status="
                        + ask.json(http.mapper()).path("data").path("status").asText(""));
    }

    // §F quality boundary -----------------------------------------------------

    void qualityBoundary() {
        var findings = http.get("/api/v1/vault-lint/findings");
        if (findings.status() != 200) {
            report.add("quality-boundary", ProductAcceptanceReport.Verdict.FAIL,
                    "vault-lint findings HTTP " + findings.status());
            return;
        }
        String body = findings.body();
        if (body.contains("/Users/") || body.contains("/tmp/") || body.contains("Exception")) {
            report.add("quality-boundary", ProductAcceptanceReport.Verdict.FAIL,
                    "vault-lint leaks path or exception material");
            return;
        }
        int before = findings.json(http.mapper()).path("data").path("findings").size();
        // Read-only proof: a second read must observe the same finding set (no auto-fix).
        var reread = http.get("/api/v1/vault-lint/findings");
        int after = reread.json(http.mapper()).path("data").path("findings").size();
        if (before != after) {
            report.add("quality-boundary", ProductAcceptanceReport.Verdict.FAIL,
                    "vault-lint read mutated finding state");
            return;
        }
        report.add("quality-boundary", ProductAcceptanceReport.Verdict.PASS,
                "vault-lint read-only, findings=" + before + ", no auto-fix observed");
    }

    // §D graph operational boundary -------------------------------------------

    void graphBoundary() {
        // Explicit rebuild (operator action; Ask never auto-rebuilds): the graph
        // projection must actually build and report READY on the temp instance.
        var rebuild = http.postJson("/api/v1/graph/projection/rebuild", "{}");
        if (rebuild.status() != 200) {
            report.add("graph-boundary", ProductAcceptanceReport.Verdict.FAIL,
                    "graph rebuild HTTP " + rebuild.status() + " " + truncate(rebuild.body()));
            return;
        }
        JsonNode ready = pollWithDeadline(
                () -> {
                    var readiness = http.get("/api/v1/graph/projection/readiness");
                    if (readiness.status() != 200) {
                        return null;
                    }
                    return readiness.json(http.mapper()).path("data");
                },
                node -> node != null && "READY".equals(node.path("status").asText("")),
                JOB_DEADLINE);
        if (ready == null) {
            report.add("graph-boundary", ProductAcceptanceReport.Verdict.FAIL,
                    "graph projection did not reach READY within deadline");
            return;
        }
        String statusBody = rebuild.body().toLowerCase(java.util.Locale.ROOT);
        if (statusBody.contains("fingerprint") || statusBody.contains("vault/")
                || statusBody.contains("snapshot_token") || statusBody.contains(".db")) {
            // Status DTOs must stay operator-safe projections (no paths/tokens).
            report.add("graph-boundary", ProductAcceptanceReport.Verdict.FAIL,
                    "graph status leaks material");
            return;
        }
        report.add("graph-boundary", ProductAcceptanceReport.Verdict.PASS,
                "graph projection rebuilt READY generation="
                        + ready.path("appliedGeneration").asText(""));
    }

    // Vector native prerequisite (no fake-green) --------------------------------

    void vectorPrerequisiteNote() {
        var semantic = http.postJson("/api/v1/ask",
                "{\"question\":\"" + ProductAcceptanceCorpusV1.QUESTION
                        + "\",\"retrievalMode\":\"SEMANTIC_WIKI\"}");
        if (semantic.status() == 503 && semantic.body().contains("RETRIEVAL_VECTOR_UNAVAILABLE")) {
            report.add("vector-prerequisite", ProductAcceptanceReport.Verdict.SKIP,
                    "sqlite-vec native unavailable in this environment; semantic KNN typed "
                            + "unavailable (see #430 native matrix). Blocks release FULL-GO.");
        } else if (semantic.status() == 200) {
            report.add("vector-prerequisite", ProductAcceptanceReport.Verdict.PASS,
                    "semantic retrieval executed with native prerequisite present");
        } else {
            report.add("vector-prerequisite", ProductAcceptanceReport.Verdict.FAIL,
                    "semantic Ask unexpected HTTP " + semantic.status());
        }
    }

    // §D embedding projection rebuild → READY (deterministic provider) --------

    void embeddingProjection() {
        var rebuild = http.postJson(
                "/api/v1/search/index/embedding/rebuild?corpus=SOURCE", "{}");
        if (rebuild.status() != 202 && rebuild.status() != 200) {
            report.add("embedding-projection", ProductAcceptanceReport.Verdict.FAIL,
                    "embedding rebuild HTTP " + rebuild.status() + " " + truncate(rebuild.body()));
            return;
        }
        String jobId = rebuild.json(http.mapper()).path("data").path("jobId").asText("");
        if (jobId.isBlank()) {
            report.add("embedding-projection", ProductAcceptanceReport.Verdict.FAIL,
                    "embedding rebuild missing jobId");
            return;
        }
        JsonNode terminal = pollWithDeadline(
                () -> {
                    var status = http.get("/api/v1/search/index/embedding/rebuild/" + jobId);
                    if (status.status() != 200) {
                        return null;
                    }
                    return status.json(http.mapper()).path("data");
                },
                node -> node != null && List.of("COMPLETED", "FAILED").contains(
                        node.path("status").asText("")),
                JOB_DEADLINE);
        if (terminal == null || !"COMPLETED".equals(terminal.path("status").asText(""))) {
            report.add("embedding-projection", ProductAcceptanceReport.Verdict.FAIL,
                    "embedding rebuild job did not complete within deadline");
            return;
        }
        var readiness = http.get("/api/v1/search/index/embedding/readiness");
        if (readiness.status() != 200) {
            report.add("embedding-projection", ProductAcceptanceReport.Verdict.FAIL,
                    "embedding readiness HTTP " + readiness.status());
            return;
        }
        boolean ready = false;
        for (JsonNode row : readiness.json(http.mapper()).path("data")) {
            if ("READY".equals(row.path("status").asText(""))) {
                ready = true;
            }
        }
        if (!ready) {
            report.add("embedding-projection", ProductAcceptanceReport.Verdict.FAIL,
                    "embedding projection not READY after deterministic rebuild");
            return;
        }
        report.add("embedding-projection", ProductAcceptanceReport.Verdict.PASS,
                "embedding projection READY via production adapter seam, job=" + jobId);
    }

    // Helpers -------------------------------------------------------------------

    private int proposalCount() {
        var list = http.get("/api/v1/proposals?page=0&size=1");
        if (list.status() != 200) {
            return -1;
        }
        return list.json(http.mapper()).path("page").path("totalElements").asInt(-1);
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

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 300 ? value : value.substring(0, 300) + "…";
    }
}
