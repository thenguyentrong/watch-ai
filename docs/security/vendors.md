# Vendors

Reviewed in the monthly control review. None of them receives data from us; the table says what they
see when a user uses the feature.

| Vendor | Used for | What they see | Terms / notes |
|---|---|---|---|
| OpenAI | ChatGPT brain (user's own account and plan) | The user's questions and answers, account id | OpenAI terms + user's ChatGPT data settings. Codex sign-in is allowed for third-party tools today; get written OK or move to SIWC before launch. |
| Hugging Face | One-time model download | User's IP address, the file requested | Public download, no account. Mirror to our own static bucket before launch (Apache-2.0 allows it). |
| Google (ML Kit / AICore) | Gemini Nano, opt-in | Diagnostics: device model, per-install id, speed, error codes | Google APIs Terms; initialised only after opt-in. |
| Google Play | Distribution, Android vitals | Install and crash data under Google's terms | Play Developer Program Policy (AI-generated content rules apply). |
| GitHub | Code, CI, Dependabot | Source code, build logs | Private repo; CodeQL/secret scanning need a public repo or paid plan. |
| Samsung / Android | Keystore, StrongBox (Knox Vault) | Nothing (on-device) | Hardware-backed keys. |
