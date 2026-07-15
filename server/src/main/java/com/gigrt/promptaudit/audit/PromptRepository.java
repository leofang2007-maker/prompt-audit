package com.gigrt.promptaudit.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * JPA repository. {@link JpaSpecificationExecutor} gives us dynamic, index-friendly filtering
 * (built in {@link PromptSpecs}) for the audit list/export without hand-writing SQL.
 */
public interface PromptRepository
        extends JpaRepository<PromptRecord, String>, JpaSpecificationExecutor<PromptRecord> {

    /** Idempotency lookup — the event_id column is UNIQUE, so at most one match. */
    PromptRecord findByEventId(String eventId);
}
