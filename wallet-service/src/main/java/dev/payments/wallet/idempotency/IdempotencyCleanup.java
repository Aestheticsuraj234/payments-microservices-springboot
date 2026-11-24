package dev.payments.wallet.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes expired idempotency keys every hour. Every instance of the service
 * runs it; that's harmless, because deleting rows that are already gone does
 * nothing.
 */
@Component
class IdempotencyCleanup {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanup.class);

    private final IdempotencyStore store;

    IdempotencyCleanup(IdempotencyStore store) {
        this.store = store;
    }

    @Scheduled(fixedDelayString = "${wallet.idempotency.cleanup-interval:PT1H}", initialDelayString = "PT1M")
    void deleteExpiredKeys() {
        int deleted = store.deleteExpired();
        if (deleted > 0) {
            log.info("deleted {} expired idempotency keys", deleted);
        }
    }
}
