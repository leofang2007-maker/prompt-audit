# Audit Gateway Health Check

Check whether the prompt-audit plugin's gateway is configured and reachable.

Steps:
1. Locate the active `prompt-audit.conf` / `config.conf` by priority and read `QODER_AUDIT_GATEWAY_URL` and `QODER_AUDIT_GATEWAY_TOKEN`.
2. Send a test POST with a sample payload to the gateway using the bearer token.
3. Report the HTTP status and response body. A `200` with `{"ok":true}` means auditing is live.
4. If there are queued failures under `${QODER_PLUGIN_DATA}/failed/`, summarize how many pending records exist.

Do not print the token value in output; mask it.
