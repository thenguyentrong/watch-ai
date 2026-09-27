# 0003 — Gemma 4 on the phone, Gemini Nano where available (2026-09-27)

**Decision.** Default on-device model: Gemma 4 E2B through LiteRT-LM 0.17.1, GPU first and CPU if the GPU
fails. Gemini Nano (ML Kit Prompt API) is used only when the user opts in, the phone is supported and
the app is on screen.

**Why.** The Gemini subscription can't be used by other apps (Google suspended accounts that tried).
Nano doesn't run on the test phone (Galaxy S23 Ultra) and Google blocks it in the background, which
the watch will need. Gemma 4 is Apache-2.0, runs on most phones with 8 GB RAM, and works offline.

**Details.**
- Model pinned to a Hugging Face commit, 2,588,147,712 bytes, SHA-256
  `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`; checked before first use.
- The catalog ships inside the signed APK, so a model only changes through an app update.
- Download over unmetered networks only, resumable, visible as a notification.
- E4B (3.7 GB) only as an opt-in after the benchmark, on phones with ~11 GB RAM or more.
