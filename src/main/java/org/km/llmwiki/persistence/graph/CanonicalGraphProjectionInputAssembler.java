package org.km.llmwiki.persistence.graph;

import org.jooq.DSLContext;
import org.km.llmwiki.graph.*;
import org.km.llmwiki.source.DocumentStatus;
import org.km.llmwiki.wiki.*;
import org.km.llmwiki.workspace.WorkspaceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.km.llmwiki.persistence.jooq.generated.Tables.*;

/** 有界、非 LLM profile v2；SQLite metadata 與 hash-verified canonical bytes 是唯一輸入。 */
@Component
public class CanonicalGraphProjectionInputAssembler implements GraphProjectionInputAssembler {
    public static final int MAX_ENTITIES = 10_000;
    public static final int MAX_RELATIONS = 50_000;
    public static final int MAX_CONTENT_BYTES = 1_048_576;
    public static final long MAX_CORPUS_BYTES = 16L * MAX_CONTENT_BYTES;
    private final DSLContext dsl;
    private final WorkspaceRepository workspaces;
    private final PublishedWikiRepository pages;
    private final PublishedWikiContentReader reader;

    public CanonicalGraphProjectionInputAssembler(DSLContext dsl, WorkspaceRepository workspaces,
            PublishedWikiRepository pages, PublishedWikiContentReader reader) {
        this.dsl = dsl;
        this.workspaces = workspaces;
        this.pages = pages;
        this.reader = reader;
    }

    @Override
    @Transactional(readOnly = true)
    public GraphProjectionInput assemble(GraphWorkspaceScope workspace) {
        try {
            return read(workspace);
        } catch (GraphProjectionException failure) {
            throw failure;
        } catch (org.springframework.dao.DataAccessException | org.jooq.exception.DataAccessException failure) {
            throw new GraphProjectionException(GraphProjectionFailureType.TRANSACTION_FAILURE);
        } catch (PublishedWikiUnavailableException failure) {
            throw new GraphProjectionException(GraphProjectionFailureType.FILESYSTEM_UNAVAILABLE);
        } catch (RuntimeException failure) {
            // 不傳遞可能含 raw content、絕對路徑或 SQL 的底層訊息。
            throw new GraphProjectionException(GraphProjectionFailureType.INVALID_PROVENANCE);
        }
    }

    private GraphProjectionInput read(GraphWorkspaceScope scope) {
        int id = Math.toIntExact(scope.id());
        long workspaceBytes = measuredBytes(WORKSPACE, WORKSPACE.ID.eq(id));
        var workspace = workspaces.findById(scope.id()).orElseThrow(() ->
                new GraphProjectionException(GraphProjectionFailureType.CROSS_WORKSPACE));
        if (!List.of("ACTIVE", "INACTIVE").contains(workspace.status())) {
            throw new GraphProjectionException(GraphProjectionFailureType.INVALID_PROVENANCE);
        }
        requireQuiescent(dsl, id);
        var wikiCondition = KNOWLEDGE_PAGE.WORKSPACE_ID.eq(id)
                .and(KNOWLEDGE_PAGE.STATUS.eq(PageStatus.PUBLISHED.name()));
        var sourceCondition = DOCUMENT.WORKSPACE_ID.eq(id)
                .and(DOCUMENT.PARSE_STATUS.eq(DocumentStatus.PROCESSED.name()))
                .and(DOCUMENT.STATUS.notIn(DocumentStatus.DELETED.name(),
                        DocumentStatus.SUPERSEDED.name(), DocumentStatus.DUPLICATE.name()));
        int wikiCount = dsl.fetchCount(KNOWLEDGE_PAGE, wikiCondition);
        int documentCount = dsl.fetchCount(DOCUMENT, sourceCondition);
        var chunkCondition = SOURCE_CHUNK.DOCUMENT_ID.in(
                dsl.select(DOCUMENT.ID).from(DOCUMENT).where(sourceCondition));
        int chunkCount = dsl.fetchCount(SOURCE_CHUNK, chunkCondition);
        if ((long) wikiCount + documentCount + chunkCount > MAX_ENTITIES) budgetExceeded();
        // 先計量所有即將載入的文字欄位，包含 metadata；超限時不 materialize rows。
        long sourceBytes = workspaceBytes + measuredBytes(KNOWLEDGE_PAGE, wikiCondition)
                + measuredBytes(DOCUMENT, sourceCondition) + measuredBytes(SOURCE_CHUNK, chunkCondition);
        if (sourceBytes > MAX_CORPUS_BYTES) budgetExceeded();
        long budget = MAX_CORPUS_BYTES - sourceBytes;
        List<GraphEntity> entities = new ArrayList<>();
        List<GraphRelation> relations = new ArrayList<>();
        List<WikiSnapshot> wikiSnapshots = new ArrayList<>();
        for (var page : pages.findAllPublished(scope.id())) {
            if (budget < 1) budgetExceeded();
            var content = reader.readCanonicalContent(page, (int) Math.min(budget, MAX_CONTENT_BYTES));
            budget -= content.byteCount();
            var wikiEntity = entity(scope, GraphEntityType.WIKI_PAGE, page.knowledgeId(), page.title(),
                    new GraphFreshness(page.revision(), page.contentHash()),
                    Map.of("profile", "canonical-v2", "type", page.pageType().name()));
            entities.add(wikiEntity);
            wikiSnapshots.add(new WikiSnapshot(wikiEntity,
                    CanonicalWikiRelationEvidence.parse(content.markdown())));
        }
        Map<String, GraphEntity> uniqueWikiTitles = uniqueWikiTitleIndex(
                wikiSnapshots.stream().map(WikiSnapshot::entity).toList());
        Map<Long, GraphEntity> documents = new HashMap<>();
        for (var doc : dsl.selectFrom(DOCUMENT).where(sourceCondition).orderBy(DOCUMENT.ID).fetch()) {
            if (doc.getArchivePath() != null && !doc.getArchivePath().isBlank()) {
                if (budget < 1) budgetExceeded();
                budget -= verifyArchive(Path.of(workspace.archivePath()), doc.getArchivePath(), doc.getSha256(),
                        (int) Math.min(budget, MAX_CONTENT_BYTES));
            }
            Map<String, String> metadata = new HashMap<>();
            metadata.put("profile", "canonical-v2");
            metadata.put("status", doc.getStatus());
            metadata.put("parse_status", doc.getParseStatus());
            if (doc.getExtractedTextHash() != null) metadata.put("extracted_hash", doc.getExtractedTextHash());
            var entity = entity(scope, GraphEntityType.SOURCE_DOCUMENT, "document:" + doc.getId(),
                    doc.getOriginalFileName() == null ? doc.getFileName() : doc.getOriginalFileName(),
                    GraphFreshness.contentHash(doc.getSha256()), metadata);
            documents.put(doc.getId().longValue(), entity);
            entities.add(entity);
        }
        for (var chunk : dsl.select(SOURCE_CHUNK.DOCUMENT_ID, SOURCE_CHUNK.CHUNK_NO,
                        SOURCE_CHUNK.PAGE_NO, SOURCE_CHUNK.SECTION, SOURCE_CHUNK.HEADING_PATH,
                        SOURCE_CHUNK.NORMALIZED_CONTENT, SOURCE_CHUNK.CONTENT_HASH)
                .from(SOURCE_CHUNK).where(chunkCondition)
                .orderBy(SOURCE_CHUNK.DOCUMENT_ID, SOURCE_CHUNK.CHUNK_NO).fetch()) {
            String content = chunk.get(SOURCE_CHUNK.NORMALIZED_CONTENT);
            Integer number = chunk.get(SOURCE_CHUNK.CHUNK_NO);
            Integer page = chunk.get(SOURCE_CHUNK.PAGE_NO);
            if (content == null || content.isBlank() || number == null || number < 1
                    || page != null && page < 1
                    || !java.text.Normalizer.isNormalized(content, java.text.Normalizer.Form.NFC)
                    || !WikiContentHash.sha256(content).equals(chunk.get(SOURCE_CHUNK.CONTENT_HASH))) continue;
            GraphEntity parent = documents.get(chunk.get(SOURCE_CHUNK.DOCUMENT_ID).longValue());
            if (parent == null) continue;
            var metadata = new HashMap<String, String>();
            metadata.put("chunk_no", number.toString());
            if (page != null) metadata.put("page_no", page.toString());
            // heading/section 保留 hash；避免將長段原文當成 graph metadata。
            String section = chunk.get(SOURCE_CHUNK.SECTION);
            String heading = chunk.get(SOURCE_CHUNK.HEADING_PATH);
            if (section != null) metadata.put("section_hash", WikiContentHash.sha256(section));
            if (heading != null) metadata.put("heading_hash", WikiContentHash.sha256(heading));
            var entity = entity(scope, GraphEntityType.SOURCE_CHUNK,
                    parent.provenance().authority().stableId() + ":chunk:" + number,
                    "Chunk " + number, GraphFreshness.contentHash(chunk.get(SOURCE_CHUNK.CONTENT_HASH)), metadata);
            entities.add(entity);
            addRelation(relations, GraphRelation.of(parent.identity(), GraphRelationType.CONTAINS,
                    entity.identity(), entity.provenance(), GraphMetadata.empty()));
        }
        Map<String, GraphEntity> tags = new HashMap<>();
        for (WikiSnapshot snapshot : wikiSnapshots) {
            GraphProvenance provenance = snapshot.entity().provenance();
            for (String targetTitle : snapshot.evidence().normalizedLinkTargets()) {
                GraphEntity target = uniqueWikiTitles.get(targetTitle);
                if (target != null) {
                    addRelation(relations, relation(snapshot.entity(), GraphRelationType.LINKS_TO,
                            target, provenance, "wikilink"));
                }
            }
            for (Long documentId : snapshot.evidence().sourceDocumentIds()) {
                GraphEntity target = documents.get(documentId);
                if (target != null) {
                    addRelation(relations, relation(snapshot.entity(),
                            GraphRelationType.DERIVED_FROM, target, provenance,
                            "frontmatter.sources"));
                }
            }
            for (String tag : snapshot.evidence().tags()) {
                GraphEntity target = tags.computeIfAbsent(tag, value -> {
                    if (entities.size() >= MAX_ENTITIES) budgetExceeded();
                    GraphEntity created = entity(scope, GraphEntityType.TAG, "tag:" + value, value,
                            GraphFreshness.contentHash(WikiContentHash.sha256(value)),
                            Map.of("profile", "canonical-v2", "source", "frontmatter.tags"));
                    entities.add(created);
                    return created;
                });
                addRelation(relations, relation(snapshot.entity(), GraphRelationType.TAGGED_WITH,
                        target, provenance, "frontmatter.tags"));
            }
        }
        if (entities.size() > MAX_ENTITIES || relations.size() > MAX_RELATIONS) budgetExceeded();
        return new GraphProjectionInput(scope, GraphProjectionVersion.initial(), entities, relations);
    }

    private record WikiSnapshot(GraphEntity entity, CanonicalWikiRelationEvidence evidence) { }

    /** Ambiguous normalized titles are deliberately absent so Wikilinks cannot resolve by guesswork. */
    static Map<String, GraphEntity> uniqueWikiTitleIndex(List<GraphEntity> wikiEntities) {
        Map<String, GraphEntity> unique = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (GraphEntity entity : wikiEntities) {
            String title = WikiTargetReference.normalizeTitle(entity.displayName());
            if (unique.putIfAbsent(title, entity) != null) ambiguous.add(title);
        }
        ambiguous.forEach(unique::remove);
        return Map.copyOf(unique);
    }

    private static GraphRelation relation(GraphEntity source, GraphRelationType type, GraphEntity target,
                                          GraphProvenance provenance, String evidence) {
        if (!CanonicalGraphRelationProfile.admits(type)) {
            throw new GraphProjectionException(GraphProjectionFailureType.INVALID_PROJECTION_INPUT);
        }
        return GraphRelation.of(source.identity(), type, target.identity(), provenance,
                GraphMetadata.of(Map.of("evidence", evidence)));
    }

    private static void addRelation(List<GraphRelation> relations, GraphRelation relation) {
        if (relations.size() >= MAX_RELATIONS) budgetExceeded();
        relations.add(relation);
    }

    static void requireQuiescent(DSLContext dsl, int workspaceId) {
        if (dsl.fetchExists(dsl.selectOne().from(WIKI_PUBLISH_OPERATION)
                .where(WIKI_PUBLISH_OPERATION.WORKSPACE_ID.eq(workspaceId))
                .and(WIKI_PUBLISH_OPERATION.STATUS.in(WikiPublishOperationStatus.PREPARED.name(),
                        WikiPublishOperationStatus.FILE_COMMITTED.name(),
                        WikiPublishOperationStatus.RECONCILIATION_REQUIRED.name())))) {
            throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_STALE);
        }
    }

    private static GraphEntity entity(GraphWorkspaceScope scope, GraphEntityType type, String id,
            String name, GraphFreshness freshness, Map<String, String> metadata) {
        var authority = new GraphAuthorityReference(scope, type.authorityKind(), id);
        return GraphEntity.of(GraphEntityIdentity.fromAuthority(authority, type), name,
                GraphProvenance.of(authority, freshness), GraphMetadata.of(metadata));
    }

    private static int verifyArchive(Path archive, String logicalPath, String expectedHash, int maxBytes) {
        try {
            Path relative = Path.of(logicalPath);
            if (relative.isAbsolute() || relative.normalize().startsWith("..")) throw new IllegalArgumentException();
            if (relative.startsWith("archive")) relative = relative.subpath(1, relative.getNameCount());
            Path root = archive.toRealPath();
            Path target = root.resolve(relative).normalize();
            if (!target.startsWith(root) || !target.toRealPath().startsWith(root)
                    || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException();
            byte[] bytes;
            try (var stream = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
                bytes = stream.readNBytes(maxBytes + 1);
            }
            if (bytes.length > maxBytes) budgetExceeded();
            if (!WikiContentHash.sha256(bytes).equals(expectedHash)) throw new IllegalArgumentException();
            return bytes.length;
        } catch (GraphProjectionException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new GraphProjectionException(GraphProjectionFailureType.INVALID_PROVENANCE);
        }
    }

    private long measuredBytes(org.jooq.Table<?> table, org.jooq.Condition condition) {
        org.jooq.Field<Long> rowBytes = org.jooq.impl.DSL.inline(0L);
        for (var field : table.fields()) {
            if (field.getType() == String.class) {
                rowBytes = rowBytes.add(org.jooq.impl.DSL.field(
                        "coalesce(length(CAST({0} AS BLOB)), 0)", Long.class, field));
            }
        }
        var size = dsl.select(org.jooq.impl.DSL.coalesce(org.jooq.impl.DSL.sum(rowBytes),
                        java.math.BigDecimal.ZERO), org.jooq.impl.DSL.max(rowBytes))
                .from(table).where(condition).fetchOne();
        long total = size.get(0, Long.class);
        Long largest = size.get(1, Long.class);
        if (total > MAX_CORPUS_BYTES || largest != null && largest > MAX_CONTENT_BYTES) budgetExceeded();
        return total;
    }

    private static void budgetExceeded() {
        throw new GraphProjectionException(GraphProjectionFailureType.INVALID_PROJECTION_INPUT);
    }
}
