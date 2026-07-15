package com.gigrt.promptaudit.audit;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/** Minimal RFC4180 CSV writer — quotes every field, escapes embedded quotes, keeps newlines intact. */
final class CsvWriter {

    private static final String[] HEADER = {
            "id", "event_id", "timestamp", "received_at", "session_id",
            "user_email", "user_name", "user_uid", "org_id", "org_name",
            "repo", "branch", "cwd", "transcript_path", "hostname", "prompt"
    };

    private CsvWriter() {}

    static void write(Writer w, List<PromptRecord> rows) throws IOException {
        writeRow(w, HEADER);
        for (PromptRecord r : rows) {
            writeRow(w, new String[]{
                    r.getId(), r.getEventId(),
                    r.getTimestamp() == null ? "" : r.getTimestamp().toString(),
                    r.getReceivedAt() == null ? "" : r.getReceivedAt().toString(),
                    r.getSessionId(), r.getUserEmail(), r.getUserName(), r.getUserUid(),
                    r.getOrgId(), r.getOrgName(), r.getRepo(), r.getBranch(),
                    r.getCwd(), r.getTranscriptPath(), r.getHostname(), r.getPrompt()
            });
        }
    }

    private static void writeRow(Writer w, String[] cells) throws IOException {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) w.write(',');
            w.write(quote(cells[i]));
        }
        w.write("\r\n");
    }

    private static String quote(String s) {
        if (s == null) s = "";
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}
