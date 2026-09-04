package org.km.llmwiki.workspace;

import org.jooq.DSLContext;
import org.km.llmwiki.persistence.jooq.generated.tables.records.WorkspaceRecord;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static org.jooq.impl.DSL.coalesce;
import static org.km.llmwiki.persistence.jooq.generated.Tables.WORKSPACE;

@Repository
public class WorkspaceRepository {

    private final DSLContext dsl;

    public WorkspaceRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<Long> findIdByRootPath(String normalizedRootPath) {
        Integer id = dsl.select(WORKSPACE.ID)
                .from(WORKSPACE)
                .where(WORKSPACE.ROOT_PATH.eq(normalizedRootPath))
                .fetchOne(WORKSPACE.ID);
        return Optional.ofNullable(id).map(Integer::longValue);
    }

    public long insert(org.km.llmwiki.workspace.WorkspaceRecord record) {
        Integer id = dsl.insertInto(WORKSPACE)
                .columns(
                        WORKSPACE.NAME,
                        WORKSPACE.ROOT_PATH,
                        WORKSPACE.INBOX_PATH,
                        WORKSPACE.ARCHIVE_PATH,
                        WORKSPACE.VAULT_PATH,
                        WORKSPACE.DATA_PATH,
                        WORKSPACE.CONFIG_PATH,
                        WORKSPACE.STATUS,
                        WORKSPACE.CREATED_AT,
                        WORKSPACE.UPDATED_AT
                )
                .values(
                        record.name(),
                        record.rootPath(),
                        record.inboxPath(),
                        record.archivePath(),
                        record.vaultPath(),
                        record.dataPath(),
                        record.configPath(),
                        record.status(),
                        record.createdAt(),
                        record.updatedAt()
                )
                .returningResult(WORKSPACE.ID)
                .fetchOne(WORKSPACE.ID);

        if (id == null) {
            throw new IllegalStateException("Workspace insert did not return a generated id");
        }
        return id.longValue();
    }

    public List<WorkspaceRow> findAll() {
        return dsl.selectFrom(WORKSPACE)
                .orderBy(WORKSPACE.CREATED_AT.desc())
                .fetch(this::toRow);
    }

    public Optional<WorkspaceRow> findById(long id) {
        return dsl.selectFrom(WORKSPACE)
                .where(WORKSPACE.ID.eq((int) id))
                .fetchOptional(this::toRow);
    }

    public Optional<WorkspaceRow> findActive() {
        return dsl.selectFrom(WORKSPACE)
                .where(WORKSPACE.STATUS.eq("ACTIVE"))
                .orderBy(coalesce(WORKSPACE.LAST_OPENED_AT, WORKSPACE.CREATED_AT).desc())
                .limit(1)
                .fetchOptional(this::toRow);
    }

    @Transactional
    public void activate(long id) {
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        dsl.update(WORKSPACE)
                .set(WORKSPACE.STATUS, "INACTIVE")
                .set(WORKSPACE.UPDATED_AT, now)
                .where(WORKSPACE.STATUS.eq("ACTIVE"))
                .and(WORKSPACE.ID.ne((int) id))
                .execute();

        dsl.update(WORKSPACE)
                .set(WORKSPACE.STATUS, "ACTIVE")
                .set(WORKSPACE.LAST_OPENED_AT, now)
                .set(WORKSPACE.UPDATED_AT, now)
                .where(WORKSPACE.ID.eq((int) id))
                .execute();
    }

    @Transactional
    public void enforceSingleActive() {
        Integer winnerId = dsl.select(WORKSPACE.ID)
                .from(WORKSPACE)
                .where(WORKSPACE.STATUS.eq("ACTIVE"))
                .orderBy(
                        coalesce(WORKSPACE.LAST_OPENED_AT, WORKSPACE.CREATED_AT).desc(),
                        WORKSPACE.ID.desc()
                )
                .limit(1)
                .fetchOne(WORKSPACE.ID);

        if (winnerId == null) {
            return;
        }

        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        dsl.update(WORKSPACE)
                .set(WORKSPACE.STATUS, "INACTIVE")
                .set(WORKSPACE.UPDATED_AT, now)
                .where(WORKSPACE.STATUS.eq("ACTIVE"))
                .and(WORKSPACE.ID.ne(winnerId))
                .execute();
    }

    private WorkspaceRow toRow(WorkspaceRecord r) {
        return new WorkspaceRow(
                r.getId().longValue(),
                r.getName(),
                r.getRootPath(),
                r.getInboxPath(),
                r.getArchivePath(),
                r.getVaultPath(),
                r.getDataPath(),
                r.getConfigPath(),
                r.getStatus(),
                r.getCreatedAt(),
                r.getUpdatedAt(),
                r.getLastOpenedAt()
        );
    }
}
