package com.gigrt.promptaudit.access;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AdminAccessLogRepository extends JpaRepository<AdminAccessLog, String> {

    // paged views (newest first) — tenant-scoped for org admins, all for platform
    Page<AdminAccessLog> findByTenantOrgId(String tenantOrgId, Pageable pageable);

    // chain-order reads for verification
    List<AdminAccessLog> findByTenantOrgIdOrderByChainSeqAsc(String tenantOrgId);
    List<AdminAccessLog> findByTenantOrgIdIsNullOrderByChainSeqAsc();

    @Query("select distinct a.tenantOrgId from AdminAccessLog a where a.tenantOrgId is not null")
    List<String> distinctTenantOrgIds();

    // ---- audit-ready evidence aggregates (spec 0007) ----
    @Query("select count(a) from AdminAccessLog a where (:scope is null or a.tenantOrgId = :scope) "
            + "and a.createdAt between :from and :to")
    long countInPeriod(@Param("scope") String scope, @Param("from") Instant from, @Param("to") Instant to);

    @Query("select count(a) from AdminAccessLog a where (:scope is null or a.tenantOrgId = :scope) "
            + "and a.action = :action and a.createdAt between :from and :to")
    long countByActionInPeriod(@Param("scope") String scope, @Param("action") String action,
                               @Param("from") Instant from, @Param("to") Instant to);
}
