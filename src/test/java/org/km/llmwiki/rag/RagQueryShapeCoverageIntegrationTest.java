package org.km.llmwiki.rag;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.graph.GraphCanonicalCurrentness;
import org.km.llmwiki.graph.GraphProjectionInputAssembler;
import org.km.llmwiki.graph.GraphProjectionLifecycleRepository;
import org.km.llmwiki.graph.GraphWorkspaceScope;
import org.km.llmwiki.processing.ProcessingJobRepository;
import org.km.llmwiki.search.FtsSearchIndexRepository;
import org.km.llmwiki.search.SearchService;
import org.km.llmwiki.search.SourceSearchAuthorityRepository;
import org.km.llmwiki.search.embedding.EmbeddingProjectionReadinessRepository;
import org.km.llmwiki.search.embedding.EmbeddingProjectionRepository;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.km.llmwiki.wiki.PublishedWikiContentReader;
import org.km.llmwiki.wiki.PublishedWikiRepository;
import org.km.llmwiki.wiki.WikiPathContract;
import org.km.llmwiki.workspace.WorkspaceService;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production-equivalent retrieval proof for the #546 conflict/completeness golden cases.
 *
 * <p>The test proves retrieval coverage only. It deliberately does not change Ask generation or
 * claim that a model can resolve a contradiction. The repository-owned truth is the required set
 * of canonical Evidence identities.
 */
@Tag("integration")
class RagQueryShapeCoverageIntegrationTest extends IsolatedIntegrationTest {

    private static final RagQueryShapeCoverageCorpusV1 CORPUS =
            new RagQueryShapeCoverageCorpusV1();

    @TempDir
    Path temp;

    @Autowired WorkspaceService workspaces;
    @Autowired SearchService searchService;
    @Autowired PublishedWikiRepository publishedWikiRepository;
    @Autowired PublishedWikiContentReader publishedWikiContentReader;
    @Autowired SourceSearchAuthorityRepository sourceAuthorityRepository;
    @Autowired FtsSearchIndexRepository ftsRepository;
    @Autowired EmbeddingProjectionRepository embeddingRepository;
    @Autowired EmbeddingProjectionReadinessRepository embeddingReadiness;
    @Autowired ProcessingJobRepository jobs;
    @Autowired GraphProjectionInputAssembler assembler;
    @Autowired GraphProjectionLifecycleRepository lifecycleRepository;
    @Autowired GraphCanonicalCurrentness currentness;
    @Autowired WikiPathContract paths;

    @Test
    void currentRetrievalCanSurfaceAllRequiredEvidenceForConflictAndCompletenessShapes()
            throws Exception {
        GraphRetrievalQualityFixture fixture = new GraphRetrievalQualityFixture(
                db(), workspaces, searchService, publishedWikiRepository,
                publishedWikiContentReader, sourceAuthorityRepository, ftsRepository,
                embeddingRepository, embeddingReadiness, jobs, assembler,
                lifecycleRepository, currentness, paths);
        GraphRetrievalQualityFixture.CorpusMaterialization materialization =
                fixture.materializeCorpus(temp, "query-shape-coverage", CORPUS.pages());
        GraphWorkspaceScope active = materialization.active();
        fixture.openWorkspace(active);

        List<EvidenceBundle> bundles = new ArrayList<>();

        try (GraphRetrievalQualityFixture.LifecycleBundle lifecycle =
                     fixture.lifecycle(temp.resolve("query-shape-coverage-graph"))) {
            FusedRetrievalOrchestrator orchestrator = fixture.orchestrator(
                    lifecycle.lifecycle(), lifecycle.factory(), FusionRankingPolicy.production());
            RetrievalService retrieval = fixture.retrievalService(orchestrator);

            for (RagQueryShapeCoverageCorpusV1.GoldenCase golden : CORPUS.cases()) {
                EvidenceBundle bundle = retrieval.retrieve(
                        RetrievalRequest.defaults(golden.text(), RetrievalMode.HYBRID_FTS));
                bundles.add(bundle);

                List<String> retrieved = bundle.items().stream()
                        .map(EvidenceItem::stableIdentity)
                        .toList();
                RagQueryShapeCoverageCorpusV1.CoverageAssessment coverage =
                        CORPUS.assess(golden, retrieved);

                assertThat(bundle.insufficientEvidence()).isFalse();
                assertThat(retrieved).doesNotHaveDuplicates();
                assertThat(retrieved).containsAll(golden.requiredEvidence());
                assertThat(coverage.complete())
                        .as(golden.id() + " must retrieve every required canonical identity")
                        .isTrue();
            }
        }

        String fingerprint = CORPUS.fingerprint();
        boolean lexicalTouched = bundles.stream()
                .allMatch(bundle -> bundle.diagnostics().lexicalSignalUsed());
        EvaluationTrustContract.Assessment trust = EvaluationTrustContract.assess(
                new EvaluationTrustContract.EnvironmentStamp(
                        RagQueryShapeCoverageCorpusV1.VERSION,
                        "production-lexical-retrieval",
                        List.of("LEXICAL"),
                        "N/A",
                        null,
                        "N/A",
                        "N/A",
                        "N/A",
                        true,
                        "integration",
                        fingerprint),
                fingerprint,
                List.of(new EvaluationTrustContract.ChannelObservation(
                        "LEXICAL",
                        true,
                        lexicalTouched,
                        lexicalTouched,
                        "both #546 golden cases execute the current lexical retrieval path")));

        assertThat(trust.findings()).isEmpty();
        assertThat(trust.evidenceStrength())
                .isEqualTo(EvaluationTrustContract.EvidenceStrength.MEASURED);
        assertThat(trust.environment().requiresAaFloor()).isFalse();
    }
}
