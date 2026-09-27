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
