package com.gigrt.promptaudit.audit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Secret redaction at capture (spec 0002). Detects well-formed secrets in a prompt and replaces each
 * with a typed token like {@code [REDACTED:aws_key]} BEFORE the prompt is stored — so the audit log
 * keeps the evidence (that a secret was sent, and its type) without hoarding the secret itself.
 *
 * Default mode is {@code mask}, using a HIGH-CONFIDENCE, low-false-positive ruleset only (prefixed
 * secret formats + explicit credential assignments) — no generic high-entropy heuristic — so normal
 * prompts are never mangled. Deterministic (same input → same output), so the tamper-evident chain
 * (spec 0001) still verifies. Operators can add rules via {@code app.redaction.extra} ("type=regex").
 */
@Component
public class Redactor {

    /** Result of a redaction pass. */
    public static final class Result {
        public final String text;
        public final int count;
        public final String types;   // distinct types, sorted, comma-joined
        Result(String text, int count, String types) { this.text = text; this.count = count; this.types = types; }
    }

    private static final class Rule {
        final String type; final Pattern pattern; final String replacement;
        Rule(String type, String regex, String replacement) {
            this.type = type; this.pattern = Pattern.compile(regex); this.replacement = replacement;
        }
    }

    private final boolean enabled;
    private final List<Rule> rules = new ArrayList<>();

    public Redactor(@Value("${app.redaction.mode:mask}") String mode,
                    @Value("${app.redaction.extra:}") List<String> extra) {
        this.enabled = !"off".equalsIgnoreCase(mode);

        // Built-in high-confidence rules (whole-match → typed token, unless noted).
        add("aws_key",        "\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b");
        add("github_token",   "\\b(?:gh[opusr]_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{22,})\\b");
        add("google_api_key", "\\bAIza[A-Za-z0-9_-]{35}\\b");
        add("slack_token",    "\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b");
        add("stripe_key",     "\\bsk_live_[A-Za-z0-9]{16,}\\b");
        add("api_key",        "\\bsk-[A-Za-z0-9_-]{20,}\\b");
        add("jwt",            "\\beyJ[A-Za-z0-9_-]{5,}\\.eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\b");
        // private key block (DOTALL via [\s\S])
        rules.add(new Rule("private_key",
                "-----BEGIN (?:RSA |EC |OPENSSH |DSA |PGP )?PRIVATE KEY-----[\\s\\S]*?-----END (?:RSA |EC |OPENSSH |DSA |PGP )?PRIVATE KEY-----",
                "[REDACTED:private_key]"));
        // explicit credential assignment — mask only the value, keep key + operator (groups 1-3)
        rules.add(new Rule("credential",
                "(?i)\\b(password|passwd|pwd|secret|api[_-]?key|access[_-]?token|auth[_-]?token|client[_-]?secret|token)(\\s*[:=]\\s*)([\"']?)[^\\s\"']{8,}",
                "$1$2$3[REDACTED:credential]"));

        // operator-supplied extra rules: "type=regex" (split on first '=')
        if (extra != null) for (String e : extra) {
            if (e == null) continue;
            int i = e.indexOf('=');
            if (i > 0 && i < e.length() - 1) {
                try { add(e.substring(0, i).trim(), e.substring(i + 1)); } catch (Exception ignore) {}
            }
        }
    }

    private void add(String type, String regex) {
        rules.add(new Rule(type, regex, "[REDACTED:" + type + "]"));
    }

    /** Redact a prompt. Returns the original untouched when disabled or when nothing matches. */
    public Result redact(String prompt) {
        if (!enabled || prompt == null || prompt.isEmpty()) return new Result(prompt, 0, "");
        String cur = prompt;
        int count = 0;
        TreeSet<String> types = new TreeSet<>();
        for (Rule rule : rules) {
            Matcher m = rule.pattern.matcher(cur);
            StringBuffer sb = new StringBuffer();
            boolean any = false;
            while (m.find()) { any = true; count++; m.appendReplacement(sb, rule.replacement); }
            if (any) { m.appendTail(sb); cur = sb.toString(); types.add(rule.type); }
        }
        return new Result(cur, count, String.join(",", types));
    }
}
