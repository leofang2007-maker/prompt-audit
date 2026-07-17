package com.gigrt.promptaudit.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Startup guard for deploying onto a database created before the redaction column (spec 0002) existed.
 * Such rows have {@code redaction_count = NULL}, which cannot map into the primitive {@code int} field —
 * so any full-entity read (integrity verification, evidence pack) would throw. This normalizes NULL → 0
 * with a native bulk update. It is chain-safe: {@code redaction_count} is NOT part of the record hash
 * (spec 0001), so the tamper-evident chain is unaffected.
 *
 * Runs at HIGHEST precedence so it executes before {@link ChainBackfill} (which reads full entities).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LegacyColumnBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacyColumnBackfill.class);

    private final PromptRepository repo;

    public LegacyColumnBackfill(PromptRepository repo) { this.repo = repo; }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int n = repo.backfillNullRedactionCount();
            if (n > 0) log.info("legacy backfill: set redaction_count=0 on {} pre-redaction row(s)", n);
        } catch (Exception e) {
            // Never block startup; fresh installs (column always non-null) make this a no-op anyway.
            log.warn("legacy redaction_count backfill skipped: {}", e.getMessage());
        }
    }
}
