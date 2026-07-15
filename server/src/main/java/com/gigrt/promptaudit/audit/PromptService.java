package com.gigrt.promptaudit.audit;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class PromptService {

    /** Length of the prompt preview returned in list responses. */
    static final int PREVIEW_CHARS = 200;
    /** Safety cap on export size so a filterless export can't stream the entire table unbounded. */
    static final int EXPORT_MAX = 50_000;

    private final PromptRepository repo;

    public PromptService(PromptRepository repo) { this.repo = repo; }

    /** Outcome of an ingest: the stored record, and whether it was an existing one (deduped). */
    public static final class IngestResult {
        public final PromptRecord record;
        public final boolean deduplicated;
        IngestResult(PromptRecord record, boolean deduplicated) {
            this.record = record; this.deduplicated = deduplicated;
        }
    }

    /**
     * Persist a reported prompt, idempotent on {@code event_id}.
     *
     * If the client supplied an event_id we've already stored (IDE double-fire, SessionStart drain
     * retry, …), we DON'T create a new row — we return the existing record so the same logical
     * submission always maps to one pr_ id. The UNIQUE index is the hard guarantee; the pre-check
     * handles the common case cleanly and the catch handles a genuine race.
     */
    public IngestResult ingest(PromptRecord r) {
        String eventId = r.getEventId();
        boolean hasEventId = eventId != null && !eventId.isEmpty();

        if (hasEventId) {
            PromptRecord existing = repo.findByEventId(eventId);
            if (existing != null) return new IngestResult(existing, true);
        }

        r.setId("pr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        r.setReceivedAt(Instant.now());
        r.setPromptLength(r.getPrompt() == null ? 0 : r.getPrompt().length());
        try {
            return new IngestResult(repo.save(r), false);
        } catch (DataIntegrityViolationException raced) {
            // Concurrent insert of the same event_id won the UNIQUE index — return the winner.
            if (hasEventId) {
                PromptRecord existing = repo.findByEventId(eventId);
                if (existing != null) return new IngestResult(existing, true);
            }
            throw raced;
        }
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
    public java.util.List<PromptRecord> forExport(PromptQuery q) {
        PageRequest pr = PageRequest.of(0, EXPORT_MAX, Sort.by(Sort.Direction.DESC, "receivedAt"));
        return repo.findAll(PromptSpecs.from(q), pr).getContent();
    }

    /** Single-line preview snippet of a prompt, for list rows. */
    public static String preview(String prompt) {
        if (prompt == null) return "";
        String oneLine = prompt.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= PREVIEW_CHARS ? oneLine : oneLine.substring(0, PREVIEW_CHARS) + "…";
    }
}
