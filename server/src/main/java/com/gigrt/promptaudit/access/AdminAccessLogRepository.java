package com.gigrt.promptaudit.access;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AdminAccessLogRepository extends JpaRepository<AdminAccessLog, String> {

    // paged views (newest first) — tenant-scoped for org admins, all for platform
    Page<AdminAccessLog> findByTenantOrgId(String tenantOrgId, Pageable pageable);

    // chain-order reads for verification
    List<AdminAccessLog> findByTenantOrgIdOrderByChainSeqAsc(String tenantOrgId);
    List<AdminAccessLog> findByTenantOrgIdIsNullOrderByChainSeqAsc();

    @Query("select distinct a.tenantOrgId from AdminAccessLog a where a.tenantOrgId is not null")
    List<String> distinctTenantOrgIds();
}
