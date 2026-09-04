package org.km.llmwiki.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.km.llmwiki.wiki.PageStatus;
import org.km.llmwiki.wiki.PublishedWikiContentReader;
import org.km.llmwiki.wiki.PublishedWikiRepository;
import org.km.llmwiki.wiki.StoredPublishedWiki;
import org.km.llmwiki.wiki.WikiPageType;
import org.springframework.dao.TransientDataAccessResourceException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
class PublishedWikiIndexingServiceTest {

    @Test
    void recordsSqliteBusyAsIndexPendingEvenWhenTheSqlMentionsContentHash() {
        PublishedWikiRepository published = mock(PublishedWikiRepository.class);
        PublishedWikiContentReader contentReader = mock(PublishedWikiContentReader.class);
        FtsSearchIndexRepository fts = mock(FtsSearchIndexRepository.class);
        WikiSearchIndexSyncRepository sync = mock(WikiSearchIndexSyncRepository.class);
        StoredPublishedWiki page = page();
        when(published.findPublishedById(page.workspaceId(), page.id())).thenReturn(Optional.of(page));
        when(contentReader.readSearchableContent(page)).thenReturn("searchable content");
        var busy = new TransientDataAccessResourceException(
                "SQL [INSERT INTO knowledge_fts (content_hash)]; [SQLITE_BUSY] database is locked");
        org.mockito.Mockito.doThrow(busy).when(fts).upsertKnowledge(any());
        when(sync.markPending(eq(page), eq(WikiSearchIndexSyncStatus.INDEX_PENDING), any()))
                .thenReturn(new StoredWikiSearchIndexSync(page.workspaceId(), page.id(), page.knowledgeId(),
                        WikiSearchIndexSyncStatus.INDEX_PENDING, page.contentHash(), null, null,
                        CjkBigramProjector.VERSION, "database is locked", "2026-09-04T00:00:00Z"));

        WikiIndexSyncResult result = new PublishedWikiIndexingService(published, contentReader, fts, sync)
                .reindex(page.workspaceId(), page.id());

        assertThat(result.status()).isEqualTo(WikiIndexSyncStatus.INDEX_PENDING);
        verify(sync).markPending(eq(page), eq(WikiSearchIndexSyncStatus.INDEX_PENDING), any());
    }

    private static StoredPublishedWiki page() {
        return new StoredPublishedWiki(1L, 1L, "topic", "Topic", "Topic", WikiPageType.CONCEPT,
                "vault/concepts/topic.md", PageStatus.PUBLISHED, "content-hash", 1,
                "2026-09-04T00:00:00Z", "2026-09-04T00:00:00Z");
    }
}
