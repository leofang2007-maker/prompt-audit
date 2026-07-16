package com.gigrt.promptaudit.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * JPA repository. {@link JpaSpecificationExecutor} gives us dynamic, index-friendly filtering
 * (built in {@link PromptSpecs}) for the audit list/export without hand-writing SQL.
 */
import java.time.Instant;
import java.util.List;

public interface PromptRepository
        extends JpaRepository<PromptRecord, String>, JpaSpecificationExecutor<PromptRecord> {

    /** Idempotency lookup — the event_id column is UNIQUE, so at most one match. */
    PromptRecord findByEventId(String eventId);

    // ---- reporting-coverage / gap detection (spec 0004) ----

    /** Per-host reporting summary for a tenant (scope=null ⇒ all). Drives went-dark/active detection. */
    interface HostAgg {
        String getHostname();
        long getCnt();
        Instant getFirstSeen();
        Instant getLastSeen();
    }

    @Query("select p.hostname as hostname, count(p) as cnt, min(p.receivedAt) as firstSeen, max(p.receivedAt) as lastSeen "
            + "from PromptRecord p where p.hostname is not null and (:scope is null or p.tenantOrgId = :scope) "
            + "group by p.hostname")
    List<HostAgg> aggregateHosts(@Param("scope") String scope);

    // ---- tamper-evident chain (spec 0001) ----
    List<PromptRecord> findByTenantOrgIdOrderByChainSeqAsc(String tenantOrgId);
    List<PromptRecord> findByTenantOrgIdIsNullOrderByChainSeqAsc();
    List<PromptRecord> findByTenantOrgIdOrderByReceivedAtAscIdAsc(String tenantOrgId);
    List<PromptRecord> findByTenantOrgIdIsNullOrderByReceivedAtAscIdAsc();

    long countByRecordHashIsNull();

    @Query("select distinct p.tenantOrgId from PromptRecord p")
    List<String> distinctTenantOrgIds();
}
