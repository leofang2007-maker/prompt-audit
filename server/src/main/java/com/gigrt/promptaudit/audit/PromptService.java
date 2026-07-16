package com.gigrt.promptaudit.audit;

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

@Service
public class PromptService {

    private static final Logger log = LoggerFactory.getLogger(PromptService.class);

    /** Length of the prompt preview returned in list responses. */
    static final int PREVIEW_CHARS = 200;
    /** Safety cap on export size so a filterless export can't stream the entire table unbounded. */
    static final int EXPORT_MAX = 50_000;

    private final PromptRepository repo;
    private final ChainHeadRepository chainHeads;

    public PromptService(PromptRepository repo, ChainHeadRepository chainHeads) {
        this.repo = repo;
        this.chainHeads = chainHeads;
    }

    /** Outcome of an ingest: the stored record, and whether it was an existing one (deduped). */
    public static final class IngestResult {
        public final PromptRecord record;
        public final boolean deduplicated;
        IngestResult(PromptRecord record, boolean deduplicated) {
            this.record = record; this.deduplicated = deduplicated;
        }
    }

    /**
     * Persist a reported prompt, idempotent on {@code event_id}, and append it to its tenant's
     * tamper-evident hash chain (spec 0001). Transactional: the chain-head is locked FOR UPDATE so
     * concurrent reports on the same chain serialize and can't fork it. A deduped repeat adds NO
     * chain link (it returns the already-chained original).
     */
    @Transactional
    public IngestResult ingest(PromptRecord r) {
        String eventId = r.getEventId();
        boolean hasEventId = eventId != null && !eventId.isEmpty();
        if (hasEventId) {
            PromptRecord existing = repo.findByEventId(eventId);
            if (existing != null) return new IngestResult(existing, true);
        }

        r.setId("pr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        // truncate to the DB's microsecond precision so the in-memory value == the stored value,
        // otherwise re-hashing on verification (which reads the stored value) would mismatch.
        r.setReceivedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        r.setPromptLength(r.getPrompt() == null ? 0 : r.getPrompt().length());

        // ---- append to the tamper-evident chain ----
        String chainKey = ChainHash.chainKey(r.getTenantOrgId());
        ChainHead head = chainHeads.findForUpdate(chainKey);
        boolean newChain = head == null;
        String prev = newChain ? ChainHash.GENESIS : head.getHeadHash();
        long seq = (newChain ? 0 : head.getSeq()) + 1;
        r.setPrevHash(prev);
        r.setChainSeq(seq);
        r.setRecordHash(ChainHash.hash(r, prev));

        PromptRecord saved = repo.save(r);
        if (newChain) chainHeads.save(new ChainHead(chainKey, saved.getRecordHash(), seq));
        else { head.setHeadHash(saved.getRecordHash()); head.setSeq(seq); chainHeads.save(head); }

        // Anchor: emit the advancing head to the log — if logs ship to a SIEM the DBA can't rewrite,
        // this bounds silent re-chaining (spec 0001, decision 3).
        log.info("chain {} advanced seq={} head={}", chainKey, seq, saved.getRecordHash());
        return new IngestResult(saved, false);
    }

    /** Paged list, newest first. */
    public Page<PromptRecord> list(PromptQuery q, int page, int pageSize) {
        PageRequest pr = PageRequest.of(page, pageSize, Sort.by(Sort.Direction.DESC, "receivedAt"));
        return repo.findAll(PromptSpecs.from(q), pr);
    }

    public PromptRecord get(String id) {
        return repo.findById(id).orElse(null);
    }

    /** Full matching set for export (newest first), bounded by {@link #EXPORT_MAX}. */
    public List<PromptRecord> forExport(PromptQuery q) {
        PageRequest pr = PageRequest.of(0, EXPORT_MAX, Sort.by(Sort.Direction.DESC, "receivedAt"));
        return repo.findAll(PromptSpecs.from(q), pr).getContent();
    }

    /** Single-line preview snippet of a prompt, for list rows. */
    public static String preview(String prompt) {
        if (prompt == null) return "";
        String oneLine = prompt.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= PREVIEW_CHARS ? oneLine : oneLine.substring(0, PREVIEW_CHARS) + "…";
    }

    // ---- tamper-evident chain verification (spec 0001) ----

    /**
     * Verify chain integrity. {@code scope} = a tenant id for an org admin (verify that chain only),
     * or null for the platform admin (verify every chain). Returns {ok, chains:[…]}.
     */
    public Map<String, Object> verify(String scope) {
        List<String> chainKeys = new ArrayList<>();
        if (scope != null) {
            chainKeys.add(ChainHash.chainKey(scope));
        } else {
            LinkedHashSet<String> keys = new LinkedHashSet<>();
            for (String t : repo.distinctTenantOrgIds()) keys.add(ChainHash.chainKey(t));
            chainKeys.addAll(keys);
        }

        List<Map<String, Object>> chains = new ArrayList<>();
        boolean allOk = true;
        for (String key : chainKeys) {
            Map<String, Object> c = verifyChain(key, recordsInOrder(key));
            allOk &= Boolean.TRUE.equals(c.get("ok"));
            chains.add(c);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", allOk);
        resp.put("chains", chains);
        return resp;
    }

    private List<PromptRecord> recordsInOrder(String chainKey) {
        return ChainHash.GLOBAL_CHAIN.equals(chainKey)
                ? repo.findByTenantOrgIdIsNullOrderByChainSeqAsc()
                : repo.findByTenantOrgIdOrderByChainSeqAsc(chainKey);
    }

    private Map<String, Object> verifyChain(String chainKey, List<PromptRecord> rows) {
        String prev = ChainHash.GENESIS;
        String head = ChainHash.GENESIS;
        long expected = 1;
        int checked = 0, unchained = 0;
        String firstBroken = null;
        for (PromptRecord r : rows) {
            if (r.getRecordHash() == null) { unchained++; continue; }   // pre-chain / not backfilled
            String recompute = ChainHash.hash(r, r.getPrevHash());
            boolean ok = recompute.equals(r.getRecordHash())
                    && Objects.equals(r.getPrevHash(), prev)
                    && r.getChainSeq() != null && r.getChainSeq() == expected;
            if (!ok) { firstBroken = r.getId(); break; }
            prev = r.getRecordHash();
            head = r.getRecordHash();
            expected++;
            checked++;
        }
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("chain", chainKey);
        c.put("ok", firstBroken == null);
        c.put("checked", checked);
        c.put("unchained", unchained);
        c.put("head_hash", head);
        c.put("first_broken_id", firstBroken);
        return c;
    }
}
