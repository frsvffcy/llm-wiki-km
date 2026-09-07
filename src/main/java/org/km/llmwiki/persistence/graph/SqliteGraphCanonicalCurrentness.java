package org.km.llmwiki.persistence.graph;

import org.jooq.DSLContext;
import org.km.llmwiki.graph.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

import static org.km.llmwiki.persistence.jooq.generated.Tables.WORKSPACE;

/** backend I/O 之外的短 SQLite writer reservation；canonical 驗證與 CAS 共用連線。 */
@Component
public class SqliteGraphCanonicalCurrentness implements GraphCanonicalCurrentness {
    private final DSLContext dsl;
    private final GraphProjectionInputAssembler assembler;
    private final TransactionTemplate transaction;

    public SqliteGraphCanonicalCurrentness(DSLContext dsl, GraphProjectionInputAssembler assembler,
                                          PlatformTransactionManager manager) {
        this.dsl = dsl;
        this.assembler = assembler;
        this.transaction = new TransactionTemplate(manager);
    }

    @Override
    public <T> T withCurrent(GraphWorkspaceScope workspace, String fingerprint, Supplier<T> action) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new GraphProjectionException(GraphProjectionFailureType.TRANSACTION_FAILURE);
        }
        try {
            return transaction.execute(status -> {
                // 在任何 read 之前取得 SQLite writer reservation，避免 deferred read upgrade。
                if (dsl.update(WORKSPACE).set(WORKSPACE.UPDATED_AT, WORKSPACE.UPDATED_AT)
                        .where(WORKSPACE.ID.eq(Math.toIntExact(workspace.id()))).execute() != 1) {
                    throw new GraphProjectionException(GraphProjectionFailureType.CROSS_WORKSPACE);
                }
                CanonicalGraphProjectionInputAssembler.requireQuiescent(dsl, Math.toIntExact(workspace.id()));
                if (!assembler.assemble(workspace).sourceFingerprint().equals(fingerprint)) {
                    throw new GraphProjectionException(GraphProjectionFailureType.PROJECTION_STALE);
                }
                return action.get();
            });
        } catch (GraphProjectionException failure) {
            throw failure;
        } catch (org.springframework.dao.DataAccessException | org.jooq.exception.DataAccessException
                 | org.springframework.transaction.TransactionException failure) {
            throw new GraphProjectionException(GraphProjectionFailureType.TRANSACTION_FAILURE);
        }
    }
}
