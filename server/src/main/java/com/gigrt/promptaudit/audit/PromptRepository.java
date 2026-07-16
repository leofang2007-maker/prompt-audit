package com.gigrt.promptaudit.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

/**
 * JPA repository. {@link JpaSpecificationExecutor} gives us dynamic, index-friendly filtering
 * (built in {@link PromptSpecs}) for the audit list/export without hand-writing SQL.
 */
import java.util.List;

public interface PromptRepository
        extends JpaRepository<PromptRecord, String>, JpaSpecificationExecutor<PromptRecord> {

    /** Idempotency lookup — the event_id column is UNIQUE, so at most one match. */
    PromptRecord findByEventId(String eventId);

    // ---- tamper-evident chain (spec 0001) ----
    List<PromptRecord> findByTenantOrgIdOrderByChainSeqAsc(String tenantOrgId);
    List<PromptRecord> findByTenantOrgIdIsNullOrderByChainSeqAsc();
    List<PromptRecord> findByTenantOrgIdOrderByReceivedAtAscIdAsc(String tenantOrgId);
    List<PromptRecord> findByTenantOrgIdIsNullOrderByReceivedAtAscIdAsc();

    long countByRecordHashIsNull();

    @Query("select distinct p.tenantOrgId from PromptRecord p")
    List<String> distinctTenantOrgIds();
}
