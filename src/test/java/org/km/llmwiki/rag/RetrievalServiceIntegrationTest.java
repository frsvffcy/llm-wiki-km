package org.km.llmwiki.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.km.llmwiki.search.FtsSearchIndexRepository;
import org.km.llmwiki.search.KnowledgeSearchDocument;
import org.km.llmwiki.search.SourceSearchDocument;
import org.km.llmwiki.testsupport.IsolatedIntegrationTest;
import org.km.llmwiki.wiki.WikiContentHash;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.main.web-application-type=none",
        "app.persistence.sqlite.path=target/test-data/retrieval-${random.uuid}/knowledge.db"
})
class RetrievalServiceIntegrationTest extends IsolatedIntegrationTest {

    @TempDir
    Path tempDir;

    @Autowired
    private RetrievalService retrievalService;

    @Autowired
    private FtsSearchIndexRepository ftsRepository;

    @Test
    void retrievesWikiSourceAndHybridFromSharedFtsWithAuthoritativeProvenance() throws Exception {
        WorkspaceFixture active = insertWorkspace("active", "ACTIVE");
        WikiFixture wiki = insertWiki(active, "wiki-retrieval", "Retrieval Wiki",
                "retrieval shared wiki authority");
        SourceFixture source = insertSource(active.id(), "retrieval.pdf", 2, 9,
                "Evidence", "Guide > Evidence", "retrieval shared source authority");

        EvidenceBundle wikiOnly = retrievalService.retrieve(
                RetrievalRequest.defaults("retrieval", RetrievalMode.WIKI_ONLY));
        assertThat(wikiOnly.items()).singleElement().satisfies(item -> {
            assertThat(item.kind()).isEqualTo(EvidenceKind.WIKI);
            assertThat(item.stableId()).isEqualTo("wiki-retrieval");
            assertThat(item.knowledgeId()).isEqualTo("wiki-retrieval");
            assertThat(item.title()).isEqualTo("Retrieval Wiki");
            assertThat(item.pageType()).isEqualTo("CONCEPT");
            assertThat(item.path()).isEqualTo("vault/concepts/retrieval-wiki.md");
            assertThat(item.revision()).isEqualTo(3);
            assertThat(item.contentHash()).isEqualTo(wiki.contentHash());
            assertThat(item.content()).contains("retrieval shared wiki authority");
            assertThat(item.workspace().id()).isEqualTo(active.id());
        });

        EvidenceBundle sourceOnly = retrievalService.retrieve(
                RetrievalRequest.defaults("retrieval", RetrievalMode.SOURCE_ONLY));
        assertThat(sourceOnly.items()).singleElement().satisfies(item -> {
            assertThat(item.kind()).isEqualTo(EvidenceKind.SOURCE_CHUNK);
            assertThat(item.stableId()).isEqualTo(Long.toString(source.chunkId()));
            assertThat(item.sourceChunkId()).isEqualTo(source.chunkId());
            assertThat(item.documentId()).isEqualTo(source.documentId());
            assertThat(item.documentName()).isEqualTo("retrieval.pdf");
            assertThat(item.chunkNo()).isEqualTo(2);
            assertThat(item.pageNo()).isEqualTo(9);
            assertThat(item.section()).isEqualTo("Evidence");
            assertThat(item.headingPath()).isEqualTo("Guide > Evidence");
            assertThat(item.content()).isEqualTo("retrieval shared source authority");
            assertThat(item.workspace().id()).isEqualTo(active.id());
        });

        EvidenceBundle hybrid = retrievalService.retrieve(
                RetrievalRequest.defaults("retrieval", RetrievalMode.HYBRID_FTS));
        assertThat(hybrid.items()).extracting(EvidenceItem::kind)
                .containsExactly(EvidenceKind.SOURCE_CHUNK, EvidenceKind.WIKI);
        assertThat(hybrid.insufficientEvidence()).isFalse();
        assertThat(hybrid.budget().usedItems()).isEqualTo(2);
    }

    @Test
    void isolatesWorkspaceAndFailsClosedWhenAuthoritativeWikiAndSourceDrift() throws Exception {
        WorkspaceFixture active = insertWorkspace("authority-active", "ACTIVE");
        WikiFixture wiki = insertWiki(active, "active-wiki", "Active Wiki",
                "authority marker wiki");
        SourceFixture source = insertSource(active.id(), "active.txt", 1, null,
                null, null, "authority marker source");

        WorkspaceFixture hidden = insertWorkspace("authority-hidden", "INACTIVE");
        insertWiki(hidden, "hidden-wiki", "Hidden Wiki", "authority marker hidden");
        insertSource(hidden.id(), "hidden.txt", 1, null,
                null, null, "authority marker hidden");

        Files.writeString(wiki.path(), "manual vault drift", StandardCharsets.UTF_8);
        db().sql("UPDATE source_chunk SET content_hash = :hash WHERE id = :id")
                .param("hash", "0".repeat(64)).param("id", source.chunkId()).update();

        EvidenceBundle bundle = retrievalService.retrieve(
                RetrievalRequest.defaults("authority", RetrievalMode.HYBRID_FTS));

        assertThat(bundle.workspace().id()).isEqualTo(active.id());
        assertThat(bundle.searchedCandidateCount()).isEqualTo(2);
        assertThat(bundle.rejectedCandidateCount()).isEqualTo(2);
        assertThat(bundle.items()).isEmpty();
        assertThat(bundle.insufficientEvidence()).isTrue();
    }

    private WorkspaceFixture insertWorkspace(String suffix, String status) throws IOException {
        Path root = tempDir.resolve(suffix);
        Files.createDirectories(root.resolve("vault/concepts"));
        Files.createDirectories(root.resolve("inbox"));
        Files.createDirectories(root.resolve("archive"));
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("config"));
        KeyHolder key = new GeneratedKeyHolder();
        db().sql("""
                        INSERT INTO workspace (name, root_path, inbox_path, archive_path, vault_path,
                            data_path, config_path, status, created_at, updated_at)
                        VALUES (:name, :root, :inbox, :archive, :vault, :data, :config, :status,
                            :created, :created)
                        """)
                .param("name", "Retrieval " + suffix)
                .param("root", root.toString())
                .param("inbox", root.resolve("inbox").toString())
                .param("archive", root.resolve("archive").toString())
                .param("vault", root.resolve("vault").toString())
                .param("data", root.resolve("data").toString())
                .param("config", root.resolve("config").toString())
                .param("status", status)
                .param("created", status.equals("ACTIVE")
                        ? "2026-08-31T02:00:00Z" : "2026-08-31T01:00:00Z")
                .update(key);
        return new WorkspaceFixture(key.getKey().longValue(), root);
    }

    private WikiFixture insertWiki(WorkspaceFixture workspace, String knowledgeId,
                                   String title, String body) throws IOException {
        String markdown = """
                ---
                id: "%s"
                title: "%s"
                type: "CONCEPT"
                status: "PUBLISHED"
                ---

                # %s

                %s
                """.formatted(knowledgeId, title, title, body);
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
        String hash = WikiContentHash.sha256(bytes);
        String logicalPath = "vault/concepts/" + title.toLowerCase().replace(' ', '-') + ".md";
        Path target = workspace.root().resolve(logicalPath);
        Files.write(target, bytes);

        db().sql("""
                        INSERT INTO knowledge_page (workspace_id, knowledge_id, title, normalized_title,
                            type, markdown_path, status, content_hash, revision, created_at, updated_at,
                            published_at)
                        VALUES (:workspace, :knowledgeId, :title, :normalizedTitle, 'CONCEPT', :path,
                            'PUBLISHED', :hash, 3, :created, :created, :created)
                        """)
                .param("workspace", workspace.id()).param("knowledgeId", knowledgeId)
                .param("title", title).param("normalizedTitle", title.toLowerCase())
                .param("path", logicalPath).param("hash", hash)
                .param("created", "2026-08-31T00:00:00Z").update();
        ftsRepository.upsertKnowledge(new KnowledgeSearchDocument(workspace.id(), knowledgeId,
                title, title.toLowerCase(), body, logicalPath, "CONCEPT", "PUBLISHED", hash));
        return new WikiFixture(target, hash);
    }

    private SourceFixture insertSource(long workspaceId, String documentName, int chunkNo,
                                       Integer pageNo, String section, String headingPath,
                                       String content) {
        KeyHolder documentKey = new GeneratedKeyHolder();
        db().sql("""
                        INSERT INTO document (workspace_id, file_name, original_file_name, source_path,
                            sha256, status, parse_status, created_at, updated_at)
                        VALUES (:workspace, :name, :name, :path, :hash, 'PENDING', 'PROCESSED',
                            :created, :created)
                        """)
                .param("workspace", workspaceId).param("name", documentName)
                .param("path", "archive/" + documentName).param("hash", "d".repeat(64))
                .param("created", "2026-08-31T00:00:00Z").update(documentKey);
        long documentId = documentKey.getKey().longValue();
        String hash = sha256(content);
        KeyHolder chunkKey = new GeneratedKeyHolder();
        db().sql("""
                        INSERT INTO source_chunk (document_id, chunk_no, page_no, section, heading_path,
                            content, normalized_content, content_hash, created_at, updated_at)
                        VALUES (:document, :chunkNo, :pageNo, :section, :headingPath, :content,
                            :content, :hash, :created, :created)
                        """)
                .param("document", documentId).param("chunkNo", chunkNo).param("pageNo", pageNo)
                .param("section", section).param("headingPath", headingPath).param("content", content)
                .param("hash", hash).param("created", "2026-08-31T00:00:00Z").update(chunkKey);
        long chunkId = chunkKey.getKey().longValue();
        ftsRepository.upsertSource(new SourceSearchDocument(workspaceId, chunkId, documentId,
                chunkNo, pageNo, content, section, headingPath, hash));
        return new SourceFixture(documentId, chunkId);
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record WorkspaceFixture(long id, Path root) {
    }

    private record WikiFixture(Path path, String contentHash) {
    }

    private record SourceFixture(long documentId, long chunkId) {
    }
}
