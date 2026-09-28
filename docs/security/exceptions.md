# Exceptions

Every place where we knowingly don't meet a control, with a reason and an end date.

| Date | Exception | Why | Until |
|---|---|---|---|
| 2026-09-27 | No Play Integrity / anti-tamper | Needs a server to verify; no server in M1 | Revisit when a backend exists |
| 2026-09-27 | No certificate pinning | We own no endpoints; OpenAI and Hugging Face rotate certificates behind CDNs (ADR 0004) | Review yearly |
| 2026-09-27 | Branch protection not enforced | Private repo on GitHub Free | Upgrade to Pro before the first external contributor or release |
| 2026-09-27 | Gradle dependency verification not yet on | Dependency set still changing during M1 | Before the first Play release |
| 2026-09-27 | Dev phone (S23 Ultra): Samsung Auto Blocker off, wireless debugging on | Needed to install and test builds | Turn back on after M1 testing |
| 2026-09-27 | Dev machine: plaintext `.env` copies in `C:\dev\_env-backup\20260708-082056\` (other projects) | Found during setup; not ours to change without asking | Move to a password manager / encrypted archive |
| 2026-09-27 | Codex OAuth client of OpenAI used by a third-party app | Only subscription route OpenAI allows today | SIWC token sharing before public launch |
| 2026-09-28 | mobsfscan `android_task_hijacking1` ignored | The watch activity is singleTask so that opening it again starts talking; its `taskAffinity` is empty, which is the mitigation | Review if the activity's launch mode changes |
| 2026-09-28 | mobsfscan `android_task_hijacking2` ignored | StrandHogg 2.0 (CVE-2020-0096) is fixed in Android 10; minSdk is 31 on the phone and 33 on the watch | Review if minSdk drops below 29 |
| 2026-09-28 | CI tool pin: `mcp` forced to 1.30 over semgrep's own pin (1.23.3, three advisories) | mobsfscan 1.0.1 pins semgrep 1.172.0, which pins the vulnerable mcp; the scan never runs an MCP server | Drop the override when mobsfscan moves on |
