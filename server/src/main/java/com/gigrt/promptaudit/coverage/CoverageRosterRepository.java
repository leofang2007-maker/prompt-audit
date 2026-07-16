package com.gigrt.promptaudit.coverage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface CoverageRosterRepository extends JpaRepository<CoverageRoster, String> {

    List<CoverageRoster> findByScopeKey(String scopeKey);

    @Modifying
    @Transactional
    void deleteByScopeKey(String scopeKey);
}
