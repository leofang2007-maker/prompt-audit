package com.gigrt.promptaudit.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<Tenant, String> {
    Tenant findByToken(String token);
    Tenant findByNameIgnoreCase(String name);
}
