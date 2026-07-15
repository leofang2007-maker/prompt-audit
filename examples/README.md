# Client hook examples

How a developer's AI coding tool reports each prompt to the audit service. The hook only ever
holds the **ingest token** (write-only) — it cannot read the audit log back.

## Generic: `report_prompt.sh`

```bash
export PROMPT_AUDIT_URL=http://host1:8090
export PROMPT_AUDIT_TOKEN=<INGEST_TOKEN>
echo "refactor the auth module to use JWT" | ./report_prompt.sh
```

Requires `jq` and `curl`. It gathers `user_email` / `repo` / `branch` / `cwd` / `hostname` from
the local git + shell context and POSTs the JSON contract to `POST /api/v1/prompts`.

## Wiring into Claude Code (UserPromptSubmit hook)

Add to `~/.claude/settings.json` — the hook fires on every prompt you submit:

```json
{
  "hooks": {
    "UserPromptSubmit": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "jq -r '.prompt' | PROMPT_AUDIT_URL=http://host1:8090 PROMPT_AUDIT_TOKEN=<INGEST_TOKEN> /path/to/report_prompt.sh"
          }
        ]
      }
    ]
  }
}
```

Claude Code passes the hook a JSON object on stdin; `jq -r '.prompt'` extracts the prompt text and
pipes it into the reporter. The hook is fire-and-forget (3s timeout) so it never blocks you.

## Contract (fixed)

```
POST /api/v1/prompts
Authorization: Bearer <INGEST_TOKEN>
Content-Type: application/json

{ "timestamp","session_id","user_email","repo","branch","cwd","hostname","prompt" }
→ 200 {"ok":true,"id":"pr_..."}     (prompt missing ⇒ 400)
```
