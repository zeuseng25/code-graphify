package com.graphify.store;

import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a multi-query read in one Oracle read-only transaction (plan 3 follow-up): every query sees the database as of
 * its first statement, even while index runs commit. Always a new transaction, so SET TRANSACTION is its first
 * statement.
 */
@Component
public class ReadSnapshot {

    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;

    public ReadSnapshot(PlatformTransactionManager transactionManager, JdbcTemplate jdbc) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.jdbc = jdbc;
    }

    public <T> T read(Supplier<T> work) {
        return transactions.execute(status -> {
            jdbc.execute("SET TRANSACTION READ ONLY");
            return work.get();
        });
    }
}
