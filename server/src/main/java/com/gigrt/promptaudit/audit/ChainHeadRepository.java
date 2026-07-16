package com.gigrt.promptaudit.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import javax.persistence.LockModeType;

public interface ChainHeadRepository extends JpaRepository<ChainHead, String> {

    /** Pessimistic-write lock (SELECT … FOR UPDATE) — serializes appends on one chain. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from ChainHead h where h.chainKey = :k")
    ChainHead findForUpdate(@Param("k") String k);
}
