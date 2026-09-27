# 0002 — ChatGPT through OpenAI's Codex sign-in (2026-09-27)

**Decision.** Sign in with the public Codex OAuth client (`app_EMoamEEZ73f0CkXaXp7hrann`): PKCE in a
Custom Tab with a one-shot 127.0.0.1 listener (ports 1455/1457), device code as fallback. Answers stream
from `chatgpt.com/backend-api/codex/responses` on the user's Plus/Pro plan. We send our own
`originator` and never pretend to be Codex.

**Why.** It's the only way a ChatGPT subscription (not an API key) pays for another app's requests
today. OpenAI supports it for third-party tools (OpenCode, Pi, OpenClaw).

**Risks.**
- The endpoint is Codex's, not a documented public API. It can change: the decoder is tolerant and
  unknown answers become `ProviderChanged` ("needs an app update").
- OpenAI's EU Terms still ban "programmatically extracting Output" and don't name third-party apps.
  Get a written OK before public launch.
- Usage counts against the user's Codex allowance (Plus: 5-hour windows). Default model `gpt-6-luna`
  with low effort; the app shows the usage meter.

**Next.** OpenAI is previewing "Sign in with ChatGPT" token sharing (SIWC) for third-party apps, with
per-app registration and the public Responses API. The `AuthMethod` seam is there; apply before launch.
