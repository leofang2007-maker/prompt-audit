package com.gigrt.promptaudit.access;

import com.gigrt.promptaudit.audit.ChainHead;
import com.gigrt.promptaudit.audit.ChainHeadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Writes and verifies the tamper-evident admin access log (spec 0003). Reuses the spec-0001 chain_head
 * table with namespaced keys ("acl:&lt;tenant&gt;") so the access log has its own per-tenant chains,
 * independent from the prompt chains.
 */
@Service
public class AccessLogService {

    private static final Logger log = LoggerFactory.getLogger(AccessLogService.class);

    public static final String ACTION_VIEW = "view_detail";
    public static final String ACTION_EXPORT = "export";

    private final AdminAccessLogRepository repo;
    private final ChainHeadRepository chainHeads;

    public AccessLogService(AdminAccessLogRepository repo, ChainHeadRepository chainHeads) {
        this.repo = repo;
        this.chainHeads = chainHeads;
    }

    /**
     * Append one access record and link it into its (data-tenant) access chain. Transactional; the
     * chain head is locked FOR UPDATE so concurrent accesses on one tenant serialize.
     */
    @Transactional
    public AdminAccessLog record(String actorEmail, String actorRole, String dataTenantOrgId,
                                 String action, String targetRecordId, String queryJson,
                                 String reason, String ip) {
        AdminAccessLog a = new AdminAccessLog();
        a.setId("acc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        a.setActorEmail(actorEmail);
        a.setActorRole(actorRole);
        a.setTenantOrgId(dataTenantOrgId);
        a.setAction(action);
        a.setTargetRecordId(targetRecordId);
        a.setQueryJson(queryJson);
        a.setReason(reason);
        a.setIp(ip);
        a.setCreatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));

        String chainKey = AccessChain.chainKey(dataTenantOrgId);
        ChainHead head = chainHeads.findForUpdate(chainKey);
        boolean newChain = head == null;
        String prev = newChain ? AccessChain.GENESIS : head.getHeadHash();
        long seq = (newChain ? 0 : head.getSeq()) + 1;
        a.setPrevHash(prev);
        a.setChainSeq(seq);
        a.setRecordHash(AccessChain.hash(a, prev));

        AdminAccessLog saved = repo.save(a);
        if (newChain) chainHeads.save(new ChainHead(chainKey, saved.getRecordHash(), seq));
        else { head.setHeadHash(saved.getRecordHash()); head.setSeq(seq); chainHeads.save(head); }

        log.info("access {} by {} ({}) tenant={} target={} chain={} seq={}",
                action, actorEmail, actorRole, dataTenantOrgId, targetRecordId, chainKey, seq);
        return saved;
    }

    /** Paged access log, newest first. scope = tenant id for an org admin, null for platform (all). */
    public Page<AdminAccessLog> list(String scope, int page, int pageSize) {
        PageRequest pr = PageRequest.of(page, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        return scope != null ? repo.findByTenantOrgId(scope, pr) : repo.findAll(pr);
    }

    /** Verify access-chain integrity. scope = tenant id (that chain) or null (every access chain). */
    public Map<String, Object> verify(String scope) {
        List<String> tenants = new ArrayList<>();
        boolean includeGlobal;
        if (scope != null) {
            tenants.add(scope);
            includeGlobal = false;
        } else {
            LinkedHashSet<String> keys = new LinkedHashSet<>(repo.distinctTenantOrgIds());
            tenants.addAll(keys);
            includeGlobal = true;
        }

        List<Map<String, Object>> chains = new ArrayList<>();
        boolean allOk = true;
        for (String t : tenants) {
            Map<String, Object> c = verifyChain(AccessChain.chainKey(t),
                    repo.findByTenantOrgIdOrderByChainSeqAsc(t));
            allOk &= Boolean.TRUE.equals(c.get("ok"));
            chains.add(c);
        }
        if (includeGlobal) {
            Map<String, Object> c = verifyChain(AccessChain.GLOBAL_CHAIN,
                    repo.findByTenantOrgIdIsNullOrderByChainSeqAsc());
            allOk &= Boolean.TRUE.equals(c.get("ok"));
            chains.add(c);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", allOk);
        resp.put("chains", chains);
        return resp;
    }

    private Map<String, Object> verifyChain(String chainKey, List<AdminAccessLog> rows) {
        String prev = AccessChain.GENESIS;
        String head = AccessChain.GENESIS;
        long expected = 1;
        int checked = 0;
        String firstBroken = null;
        for (AdminAccessLog a : rows) {
            if (a.getRecordHash() == null) continue;
            String recompute = AccessChain.hash(a, a.getPrevHash());
            boolean ok = recompute.equals(a.getRecordHash())
                    && Objects.equals(a.getPrevHash(), prev)
                    && a.getChainSeq() != null && a.getChainSeq() == expected;
            if (!ok) { firstBroken = a.getId(); break; }
            prev = a.getRecordHash();
            head = a.getRecordHash();
            expected++;
            checked++;
        }
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("chain", chainKey);
        c.put("ok", firstBroken == null);
        c.put("checked", checked);
        c.put("head_hash", head);
        c.put("first_broken_id", firstBroken);
        return c;
    }
}
