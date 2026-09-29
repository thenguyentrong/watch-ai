# Controls

Mapped to OWASP MASVS v2.1 (app) and the SOC 2 Trust Services Criteria (organisation).

**SOC 2 is an audit, not a feature.** An independent CPA firm attests to it: Type I checks the design on
one date, Type II checks that controls actually ran over 3–12 months. With no servers and no customer
data held by us, nobody needs it yet. This file and the evidence below are there so a readiness
assessment is quick when a customer asks. For the app itself, the matching independent review is the
App Defense Alliance MASA (based on MASVS).

## App controls (MASVS)

| Area | Control | Evidence |
|---|---|---|
| STORAGE | Tokens only in `KeystoreVault` (Tink AES-256-GCM, keyset wrapped by a Keystore key, StrongBox when present). Conversations (History) and remembered facts in their own `KeystoreVault` (own key), 30 days, deletable in the app. No backups (`allowBackup=false`, `dataExtractionRules` exclude everything incl. device transfer). Model in `noBackupFilesDir`. | `core/security/.../KeystoreVault.kt`, `KeystoreVaultTest` (device), `app/src/main/res/xml/data_extraction_rules.xml` |
| CRYPTO | Tink only, no own crypto. Associated data binds each file to its purpose and name. Software-only keys refused. Decrypt failure wipes, never falls back to plaintext. | `KeystoreVault.kt` |
| AUTH | PKCE S256 + state, loopback on 127.0.0.1 only, one-shot, Host check; device code fallback with phishing warning; refresh under one mutex; sign-out revokes and wipes. | `core/brain-chatgpt/.../auth/*`, `CallbackTest`, `AuthSessionTest` |
| NETWORK | HTTPS only, system CAs, Certificate Transparency, host allow-lists for OpenAI and Hugging Face, no redirects on auth calls. No pinning (ADR 0004). | `network_security_config.xml`, `ChatGptHttp.kt`, `ModelDownloader.kt` |
| PLATFORM | Only the launcher exported; `enforceIntentFilter`; FLAG_SECURE + no recents thumbnail + hidden overlays (release); no WebView; replies as plain text. | `AndroidManifest.xml`, `ScreenSecurity.kt` |
| CODE | R8 in release, debug/info logs stripped, `BrainLog` codes-only events, redactor, lint + ktlint in CI, targetSdk 36. | `proguard-rules.pro`, `Logging.kt`, CI |
| RESILIENCE | R8 obfuscation only. Play Integrity needs a server — accepted risk (`exceptions.md`). | — |
| PRIVACY | No analytics/crash SDKs, AD_ID removed, ML Kit only after opt-in, cloud answers off by default, AI notice, delete everything in-app. | `PRIVACY.md`, `docs/privacy/*` |

## Organisation controls (SOC 2 criteria)

| Criterion | What we do | Evidence |
|---|---|---|
| CC1 control environment | Short security policy (this folder), yearly self-review | `control-review-log.md` |
| CC2 communication | `SECURITY.md`, `PRIVACY.md`, release notes | repo |
| CC3 risk assessment | Threat model, reviewed when brains/permissions/destinations change | `threat-model.md` |
| CC4 monitoring | Monthly 15-minute control review; Dependabot and OSV triage | `control-review-log.md`, CI runs |
| CC5 control activities | CI gates: tests, lint, format, fixture guard, gitleaks, mobsfscan, OSV, SBOM | `.github/workflows/ci.yml` |
| CC6 access | GitHub passkey/2FA, minimal token scopes, Play Console 2-step, upload key custody, dev machine baseline | `key-management.md`, `exceptions.md` |
| CC7 operations | Vulnerability SLAs, incident runbook, Android vitals | `secure-sdlc.md`, `incident-response.md` |
| CC8 change management | Changes through pull requests with green CI; releases built by CI only; no AI co-author lines | `secure-sdlc.md`, git history |
| CC9 risk mitigation | Vendor register, exceptions log | `vendors.md`, `exceptions.md` |

CI evidence (test results, lint, scans, SBOM, APK checksums) is kept as workflow artifacts (90 days;
raise to 400 days in repo settings) and summarised per milestone in `docs/test-reports/`.
