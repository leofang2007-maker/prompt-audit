package com.gigrt.promptaudit.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One-time backfill (spec 0001, decision 4): on startup, chain any rows that predate the
 * tamper-evident feature (record_hash IS NULL), in received_at order per chain, then seed the
 * chain_head. Guarded by the null-hash count, so it runs once and is a no-op thereafter.
 */
@Component
public class ChainBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ChainBackfill.class);

    private final PromptRepository repo;
    private final ChainHeadRepository chainHeads;

    @Value("${app.chain.backfill-on-start:true}")
    private boolean enabled;

    public ChainBackfill(PromptRepository repo, ChainHeadRepository chainHeads) {
        this.repo = repo; this.chainHeads = chainHeads;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        if (repo.countByRecordHashIsNull() == 0) return;   // already chained → no-op

        log.info("chain backfill: unchained rows found, backfilling…");
        Set<String> keys = new LinkedHashSet<>();
        for (String t : repo.distinctTenantOrgIds()) keys.add(ChainHash.chainKey(t));

        int total = 0;
        for (String key : keys) {
            List<PromptRecord> rows = ChainHash.GLOBAL_CHAIN.equals(key)
                    ? repo.findByTenantOrgIdIsNullOrderByReceivedAtAscIdAsc()
                    : repo.findByTenantOrgIdOrderByReceivedAtAscIdAsc(key);
            String prev = ChainHash.GENESIS;
            long seq = 0;
            for (PromptRecord r : rows) {
                if (r.getRecordHash() != null) {           // already chained — continue from it
                    prev = r.getRecordHash();
                    seq = r.getChainSeq() != null ? r.getChainSeq() : seq;
                    continue;
                }
                seq++;
                r.setPrevHash(prev);
                r.setChainSeq(seq);
                r.setRecordHash(ChainHash.hash(r, prev));
                repo.save(r);
                prev = r.getRecordHash();
                total++;
            }
            ChainHead h = chainHeads.findById(key).orElseGet(ChainHead::new);
            h.setChainKey(key);
            h.setHeadHash(prev);
            h.setSeq(seq);
            chainHeads.save(h);
        }
        log.info("chain backfill: chained {} record(s) across {} chain(s)", total, keys.size());
    }
}
