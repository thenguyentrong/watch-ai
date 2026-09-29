# Threat model — M1 (phone only), 2026-09-27

Scope: the Android app with the ChatGPT and on-device brains. No servers of ours.
Assets: ChatGPT tokens (access + rotating refresh), conversation text (memory only), flagged
reports, the on-device model file, the app's integrity.

```
[user] → app ── HTTPS ──> auth.openai.com / chatgpt.com   (user's own account)
           ├── HTTPS ──> huggingface.co / *.hf.co          (model download, once)
           ├── 127.0.0.1:1455/1457 listener                 (OAuth redirect, one-shot)
           └── Keystore key → Tink keyset → encrypted token file (noBackupFilesDir)
```

| # | Threat (STRIDE) | Where | Mitigation | Status |
|---|---|---|---|---|
| T1 | Another app steals the OAuth code (port squatting, fake redirect) — S/I | loopback | PKCE S256 (verifier never leaves the process), bind before opening the browser, 127.0.0.1 only, exact `state` (constant time), Host check, one-shot, 10-strike abort; device code if both ports are taken | done, unit-tested |
| T2 | Code intercepted or replayed — T/I | exchange | single use, never retried, auth client doesn't follow redirects | done, unit-tested |
| T3 | Token theft from storage or backups — I | vault | Tink AES-256-GCM, keyset wrapped by a non-exportable Keystore key (StrongBox on the S23 Ultra), associated data per file, `allowBackup=false` + no cloud/device-transfer backup | done, tested on the S23 Ultra (StrongBox) |
| T4 | Tokens leak through logs, screenshots, notifications — I | app | codes-only logging (`BrainLog`), redactor, release strips v/d/i logs, FLAG_SECURE in release, no content on lock screen | done, logcat leak check clean on device |
| T5 | MITM on OpenAI or Hugging Face — T/I | network | TLS only, system CAs only, Certificate Transparency (Android 16+), host allow-lists; no pinning (ADR 0004) | done |
| T6 | Refresh token reuse / race — D/E | session | one mutex, rotation persisted uncancellably, reuse errors wipe and ask to sign in | done, unit-tested (50 parallel → 1 refresh) |
| T7 | Tampered or swapped model file — T | download | URL pinned to a commit, size + SHA-256 checked before use, redirects only to Hugging Face hosts, catalog inside the signed APK | done, unit-tested |
| T8 | Malicious or hijacked dependency — T/E | build | version catalog, pinned action SHAs, Dependabot, OSV, gitleaks, mobsfscan, SBOM; Gradle dependency verification before release | partly (verification metadata pending) |
| T9 | Prompt injection makes the model do something harmful or leak private data — E/I | brains, tools | since tools (28.09): every call through the `Guard` (levels, unknown tools refused, stop), texts and calls only after a yes in a later turn (`Pending`), a yes only when the owner is there (phone or watch unlocked, earbuds) and at most 10 an hour; private results through the `Redactor` and, with the offline model, Gemma first (no tools, no network); web search cached-only once private data is in the conversation. Plan: docs/security/agent-safety.md | step 1 done, unit-tested incl. poisoned messages |
| T10 | Harmful or wrong answers shown to users — repudiation/safety | UI | AI notice, "answered by" label, flag button (Play AI-content policy) | done |
| T11 | Lost or rooted phone — I | device | tokens bound to this phone's Keystore; sign out + "log out of all devices" at OpenAI; rooted phones are out of scope (documented) | accepted |
| T12 | OpenAI changes or restricts Codex sign-in — D | provider | isolated module, `ProviderChanged` error, app update; move to SIWC before launch | accepted, tracked |
| T13 | Gemini Nano diagnostics sent without consent — privacy | ML Kit | init provider removed; ML Kit starts only after opt-in | done |

Review this file whenever a brain, a permission or a network destination is added.
