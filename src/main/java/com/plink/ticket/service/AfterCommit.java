package com.plink.ticket.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Defers something the outside world sees - mail, mostly - until the transaction that
 * decided it has committed.
 *
 * <p>Sent from inside, a message can announce a link or a cancellation that a rollback
 * then takes back, and a slow relay holds a pooled connection for as long as it dawdles.
 * Outside a transaction the task simply runs now.
 */
public final class AfterCommit {
    private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

    private AfterCommit() { }

    public static void run(Runnable task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            task.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // The commit has happened; a failure here can no longer undo it, and must
                // not reach the caller as if the request itself had failed.
                try {
                    task.run();
                } catch (RuntimeException failed) {
                    log.error("A post-commit task failed: {}", failed.getMessage(), failed);
                }
            }
        });
    }
}
