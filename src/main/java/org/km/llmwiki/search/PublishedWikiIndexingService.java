package org.km.llmwiki.search;

import org.km.llmwiki.wiki.PageStatus;
import org.km.llmwiki.wiki.PublishedWikiRepository;
import org.km.llmwiki.wiki.StoredPublishedWiki;
import org.km.llmwiki.wiki.WikiContentHash;
import org.km.llmwiki.wiki.WikiPageType;
import org.km.llmwiki.wiki.WikiPathContract;
import org.km.llmwiki.wiki.WikiPublishResult;
import org.km.llmwiki.wiki.WikiCreatePublishResponse;
import org.km.llmwiki.wiki.WikiMergePublishResponse;
import org.km.llmwiki.workspace.WorkspaceRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Optional;

/**
 * Projects an already successful Published Wiki into the rebuildable FTS index.
 *
 * <p>The service reads the canonical bytes from vault and verifies the durable
 * {@code knowledge_page.content_hash} before writing FTS. It never renders or asks an
 * LLM for content, and all failures are recorded as repairable ledger state instead of
 * escaping into the completed publish transaction.
 */
@Service
public class PublishedWikiIndexingService {

    private static final String FRONTMATTER_SEPARATOR = "\n---\n\n";

    private final PublishedWikiRepository publishedWikiRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WikiPathContract pathContract;
    private final FtsSearchIndexRepository ftsRepository;
    private final WikiSearchIndexSyncRepository syncRepository;

    public PublishedWikiIndexingService(PublishedWikiRepository publishedWikiRepository,
                                        WorkspaceRepository workspaceRepository,
                                        WikiPathContract pathContract,
                                        FtsSearchIndexRepository ftsRepository,
                                        WikiSearchIndexSyncRepository syncRepository) {
        this.publishedWikiRepository = publishedWikiRepository;
        this.workspaceRepository = workspaceRepository;
        this.pathContract = pathContract;
        this.ftsRepository = ftsRepository;
        this.syncRepository = syncRepository;
    }

    /** Syncs one Published Wiki by the workspace-scoped knowledge_page id. */
    public WikiIndexSyncResult reindex(long workspaceId, long knowledgePageId) {
        Optional<StoredPublishedWiki> page = publishedWikiRepository
                .findPublishedById(workspaceId, knowledgePageId);
        if (page.isEmpty()) {
            return new WikiIndexSyncResult(WikiIndexSyncStatus.NOT_FOUND, workspaceId, knowledgePageId,
                    "Published Wiki page was not found in this workspace");
        }

        StoredPublishedWiki published = page.get();
        try {
            Path target = resolveTarget(published);
            byte[] bytes = Files.readAllBytes(target);
            String markdown = decodeUtf8(bytes);
            if (!WikiContentHash.sha256(bytes).equals(published.contentHash())) {
                return pending(published, WikiSearchIndexSyncStatus.DRIFT,
                        "Vault Markdown hash differs from knowledge_page.content_hash; index was not changed");
            }
            validateCanonicalMarkdown(published, markdown);

            String searchableContent = searchableProjection(markdown);
            KnowledgeSearchDocument document = new KnowledgeSearchDocument(
                    published.workspaceId(), published.knowledgeId(), published.title(),
                    published.normalizedTitle(), searchableContent, published.markdownPath(),
                    published.pageType().name(), PageStatus.PUBLISHED.name(), published.contentHash());
            ftsRepository.upsertKnowledge(document);
            StoredWikiSearchIndexSync ledger = syncRepository.markSynced(published);
            return new WikiIndexSyncResult(WikiIndexSyncStatus.SYNCED, ledger.workspaceId(),
                    ledger.knowledgePageId(), null);
        } catch (RuntimeException | IOException exception) {
            return pending(published, WikiSearchIndexSyncStatus.INDEX_PENDING,
                    "Published Wiki FTS sync failed: " + exception.getClass().getSimpleName()
                            + ": " + safeMessage(exception));
        }
    }

    /** Alias used by publish/recovery callers; the operation is intentionally idempotent. */
    public WikiIndexSyncResult synchronize(long workspaceId, long knowledgePageId) {
        return reindex(workspaceId, knowledgePageId);
    }

    /**
     * Called only after CREATE/MERGE publish has completed its vault and relational finalization.
     * NO_OP is also checked so a retry can repair an earlier index-pending row.
     */
    public WikiIndexSyncResult synchronizeAfterPublish(WikiPublishResult result) {
        if (result instanceof WikiCreatePublishResponse create) {
            return reindex(create.workspaceId(), create.knowledgePageId());
        }
        if (result instanceof WikiMergePublishResponse merge) {
            return reindex(merge.workspaceId(), merge.knowledgePageId());
        }
        throw new IllegalArgumentException("Unsupported Wiki publish result");
    }

    private Path resolveTarget(StoredPublishedWiki page) {
        var workspace = workspaceRepository.findById(page.workspaceId())
                .orElseThrow(() -> new IllegalStateException("Published Wiki workspace was not found"));
        WikiPageType pathType = pathContract.validateLogicalPath(page.markdownPath());
        if (pathType != page.pageType()
                || !pathContract.resolveLogicalPath(page.pageType(), page.title()).equals(page.markdownPath())) {
            throw new IllegalStateException("Published Wiki metadata path is not canonical");
        }
        return pathContract.resolveAndValidateRealPath(Path.of(workspace.vaultPath()), page.markdownPath());
    }

    private static void validateCanonicalMarkdown(StoredPublishedWiki page, String markdown) {
        if (!markdown.startsWith("---\n") || !markdown.contains(FRONTMATTER_SEPARATOR)) {
            throw new IllegalStateException("Published Wiki Markdown has no complete frontmatter block");
        }
        requireFrontmatter(markdown, "id", quote(page.knowledgeId()));
        requireFrontmatter(markdown, "title", quote(page.title()));
        requireFrontmatter(markdown, "type", quote(page.pageType().name()));
        requireFrontmatter(markdown, "status", quote(PageStatus.PUBLISHED.name()));
        if (!markdown.contains("\n# " + page.title() + "\n")) {
            throw new IllegalStateException("Published Wiki Markdown title does not match metadata");
        }
    }

    private static String searchableProjection(String markdown) {
        int separator = markdown.indexOf(FRONTMATTER_SEPARATOR);
        if (separator < 0 || separator + FRONTMATTER_SEPARATOR.length() >= markdown.length()) {
            throw new IllegalStateException("Published Wiki Markdown has no searchable body");
        }
        String body = markdown.substring(separator + FRONTMATTER_SEPARATOR.length());
        return Normalizer.normalize(body, Normalizer.Form.NFC);
    }

    private static void requireFrontmatter(String markdown, String name, String value) {
        String expected = name + ": " + value;
        if (markdown.lines().noneMatch(line -> line.equals(expected))) {
            throw new IllegalStateException("Published Wiki frontmatter field does not match " + name);
        }
    }

    private WikiIndexSyncResult pending(StoredPublishedWiki page, WikiSearchIndexSyncStatus status,
                                        String detail) {
        try {
            StoredWikiSearchIndexSync ledger = syncRepository.markPending(page, status, detail);
            return new WikiIndexSyncResult(WikiIndexSyncStatus.valueOf(status.name()),
                    ledger.workspaceId(), ledger.knowledgePageId(), detail);
        } catch (RuntimeException ledgerFailure) {
            // The caller must never turn a rebuildable-index failure into a publish rollback.
            return new WikiIndexSyncResult(WikiIndexSyncStatus.valueOf(status.name()),
                    page.workspaceId(), page.id(),
                    detail + "; repair ledger write failed: " + safeMessage(ledgerFailure));
        }
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n") + '"';
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "unspecified failure" : message;
    }
}
