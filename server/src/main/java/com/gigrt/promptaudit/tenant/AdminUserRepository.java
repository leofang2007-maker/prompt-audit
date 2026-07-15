package com.gigrt.promptaudit.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdminUserRepository extends JpaRepository<AdminUser, String> {
    AdminUser findByEmailIgnoreCase(String email);
    List<AdminUser> findByTenantId(String tenantId);
    long countByTenantId(String tenantId);
}
