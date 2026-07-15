package com.gigrt.promptaudit.audit;

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

    /** Persist a reported prompt; assigns id + received_at. */
    public PromptRecord ingest(PromptRecord r) {
        r.setId("pr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        r.setReceivedAt(Instant.now());
        r.setPromptLength(r.getPrompt() == null ? 0 : r.getPrompt().length());
        return repo.save(r);
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
