# watch-ai (working name)

AI companion for smartwatches — "Meta's Muse Charm, on the watch you already wear". The user's own
AI plan pays for the answers: **no API keys, no API bill, no servers.**

Milestone 1 (this state): two AI "brains" running from the phone, built and tested before the watch app.

| Brain | How | Who pays | Where it runs |
|---|---|---|---|
| ChatGPT | "Sign in with ChatGPT" via OpenAI's Codex OAuth | user's Plus/Pro plan | OpenAI, under the user's own account |
| Gemma 4 E2B | LiteRT-LM, model downloaded once (2.6 GB, Apache-2.0) | free | on the phone, offline |
| Gemini Nano | ML Kit Prompt API, opt-in | free | on supported phones only (not the S23 Ultra) |

Claude and Copilot need a computer or a server (Anthropic forbids third-party Claude login), so they
come later (M3). The watch app, voice and mascot are M2.

## Modules

| Module | What |
|---|---|
| `core/brain` | `Brain` interface, errors, router, short-answer prompt. Plain Kotlin/JVM. |
| `core/brain-chatgpt` | OAuth (PKCE + loopback, device code), token refresh, Responses streaming. Plain Kotlin/JVM, fully unit-tested. |
| `core/brain-ondevice` | Gemma via LiteRT-LM (GPU → CPU fallback), Gemini Nano via ML Kit, verified model download. |
| `core/security` | Keystore + Tink vault for the sign-in tokens, log redaction, screen protection. |
| `core/testing` | Fakes and synthetic test data. |
| `app` | Phone app: AI notice, chat test console, brains, settings, debug benchmark. |

## Build and test

JDK 17 is picked by `gradle/gradle-daemon-jvm.properties`, so the machine's `JAVA_HOME` doesn't matter.
Android SDK at `C:\dev\tools` (`local.properties`).

```
gradlew test testDebugUnitTest      # JVM tests (no phone needed)
gradlew lintDebug spotlessCheck checkSyntheticFixtures
gradlew assembleDebug assembleRelease
gradlew connectedDebugAndroidTest   # on the phone (vault, Keystore)
```

**Test data is synthetic only.** Anything token-shaped in test sources needs `SYNTHETIC` on the same line;
`checkSyntheticFixtures` fails the build otherwise. Commits carry no co-author lines (`.githooks/commit-msg`,
enable with `git config core.hooksPath .githooks`).

## Security and privacy

`SECURITY.md`, `PRIVACY.md`, `docs/security/` (threat model, controls, key management, incident response),
`docs/privacy/` (data inventory, Play Data safety, GDPR records), `docs/adr/` (decisions).
