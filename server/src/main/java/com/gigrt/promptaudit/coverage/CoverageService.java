package com.gigrt.promptaudit.coverage;

import com.gigrt.promptaudit.audit.PromptRepository;
import com.gigrt.promptaudit.audit.PromptRepository.HostAgg;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reporting-coverage / gap detection (spec 0004). Derives, per tenant, which HOSTS are actively
 * reporting vs went-dark, purely from existing {@code prompt_record} data (no new capture). With an
 * optional roster, also flags hosts that were expected but never reported.
 *
 * Deliberately HOST-granular only (spec 0004 decision 3): coverage answers "is the control working on
 * this machine", never "which developer is quiet" — so it can't become activity surveillance (#3).
 */
@Service
public class CoverageService {

    private static final String GLOBAL = "__global__";

    private final PromptRepository prompts;
    private final CoverageRosterRepository rosters;
    private final double silentMultiplier;
    private final long floorSeconds;
    private final long minHistory;

    public CoverageService(PromptRepository prompts, CoverageRosterRepository rosters,
                           @Value("${app.coverage.silent-multiplier:3.0}") double silentMultiplier,
                           @Value("${app.coverage.floor-hours:24}") long floorHours,
                           @Value("${app.coverage.min-history:5}") long minHistory) {
        this.prompts = prompts;
        this.rosters = rosters;
        this.silentMultiplier = silentMultiplier;
        this.floorSeconds = floorHours * 3600L;
        this.minHistory = minHistory;
    }

    private static String scopeKey(String scope) { return scope == null ? GLOBAL : scope; }

    /** Compute the coverage picture for a tenant (scope=null ⇒ platform, all hosts). */
    public Map<String, Object> compute(String scope) {
        Instant now = Instant.now();
        List<HostAgg> aggs = prompts.aggregateHosts(scope);

        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> silent = new ArrayList<>();
        int active = 0;

        for (HostAgg a : aggs) {
            String host = a.getHostname();
            seen.add(host);
            long cnt = a.getCnt();
            long lastSec = a.getLastSeen().getEpochSecond();
            long silenceSec = now.getEpochSecond() - lastSec;
            // average interval between this host's reports (proxy for its cadence)
            Long intervalSec = cnt > 1 ? (lastSec - a.getFirstSeen().getEpochSecond()) / (cnt - 1) : null;
            long threshold = intervalSec != null
                    ? Math.max((long) (silentMultiplier * intervalSec), floorSeconds)
                    : floorSeconds;

            boolean wentDark = cnt >= minHistory && intervalSec != null && silenceSec > threshold;
            if (wentDark) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("entity", host);
                m.put("kind", "went_dark");
                m.put("last_seen", a.getLastSeen().toString());
                m.put("report_count", cnt);
                m.put("expected_interval_sec", intervalSec);
                m.put("silent_for_sec", silenceSec);
                silent.add(m);
            } else if (silenceSec <= floorSeconds) {
                active++;
            }
        }

        // never-reported: on the roster, but never seen
        List<Map<String, Object>> never = new ArrayList<>();
        List<String> roster = rosterEntities(scope);
        for (String e : roster) {
            if (!seen.contains(e)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("entity", e);
                m.put("kind", "never_reported");
                never.add(m);
            }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("generated_at", now.toString());
        resp.put("total_hosts", seen.size());
        resp.put("active_hosts", active);
        resp.put("silent", silent);
        resp.put("never_reported", never);
        resp.put("roster_size", roster.size());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("silent_multiplier", silentMultiplier);
        params.put("floor_hours", floorSeconds / 3600);
        params.put("min_history", minHistory);
        resp.put("thresholds", params);
        return resp;
    }

    public List<String> rosterEntities(String scope) {
        List<String> out = new ArrayList<>();
        for (CoverageRoster r : rosters.findByScopeKey(scopeKey(scope))) out.add(r.getEntity());
        return out;
    }

    /** Replace a tenant's roster with the given host list (deduped, trimmed). Returns the stored count. */
    public int setRoster(String scope, List<String> entities) {
        String key = scopeKey(scope);
        rosters.deleteByScopeKey(key);
        Set<String> unique = new LinkedHashSet<>();
        if (entities != null) for (String e : entities) {
            if (e != null && !e.trim().isEmpty()) unique.add(e.trim());
        }
        Instant now = Instant.now();
        for (String e : unique) {
            rosters.save(new CoverageRoster("cvr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                    key, e, now));
        }
        return unique.size();
    }
}
