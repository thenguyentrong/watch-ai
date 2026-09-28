# Buddy

A personal AI agent that lives on the watch and earbuds you already wear, and gets things done on
your phone with the AI plan you already pay for. Repo name: watch-ai.

## Why

Small things are still fiddly. Telling someone you're late, setting a timer, checking what's next,
finding your phone. AI agents can already do a lot of this, but mostly at a computer: open the
laptop, type, wait. On the phone it means unlocking, finding the app and typing again. And often you
only want one small thing done, right now, with your hands full.

New AI gadgets try to fix that, a pin or a charm with a little character on it. But that's another
device to buy, charge and carry, usually with its own subscription. Expensive, and not sustainable.

Most people already wear a smartwatch, and earbuds or headphones for a big part of the day. So Buddy
uses those. Raise your wrist and say "Hey Buddy", or tap the watch, and say what you need. Buddy
answers on the watch or in your ear, and does it on your phone. A small character on the watch shows
what it's doing. It runs on your existing AI subscription: no new device, no new plan, no API keys.

## What it does (28.09.2026)

- **Talk from the watch** with the phone in your pocket, on ChatGPT voice and your own plan. The
  watch is mic, speaker and face; with earbuds in, they take over the sound.
- **"Hey Buddy"**, heard on the watch itself: after you raise your wrist, or all the time if you
  switch that on. **Learn my voice** (read five short sentences) helps it hear you.
- **Things on your phone**: text and call people, read and answer your messages (WhatsApp, Signal,
  SMS and more, through their notifications), timers and alarms, calendar and reminders, notes, music
  and volume, ringer and Do Not Disturb, find my phone, flashlight, open apps, directions in Maps.
  Say "bye" when you're done.
- **On the phone**: the same Buddy. Tap it and talk; everything else is behind one menu.

## Safety

- **Nothing goes out without your yes.** Messages and calls are read back to you and only sent if
  you say yes in a later turn, within two minutes. The AI can't confirm its own proposal, and nothing
  inside a message it reads to you can.
- **Your OK for each kind of access**: contacts, texts, calls, notifications, calendar. You can take
  it back any time.
- **"Hey Buddy" stays on the watch.** Nothing is recorded or sent until it hears the phrase. Learn my
  voice keeps what the model heard, not the recordings.
- **No servers of ours.** Your words go from your phone to OpenAI under your own account. The sign-in
  is encrypted with a key that never leaves the phone. No analytics, no ads.

## Status and next

Tested on a Galaxy Watch5 and a Galaxy S23 Ultra, with ChatGPT (Sign in with ChatGPT, Plus or Pro).

Next:
- A "Hey Buddy" model of its own, trained on thousands of voices, so it hears everyone well out of
  the box, for less battery.
- Your computer too: Buddy hands things to an agent on your PC (Claude Code on your own Claude plan),
  as long as the PC is on and online. Anthropic doesn't allow other apps to use a Claude login, so
  that part runs on your computer, not in this app.
- Play Store: closed test first.

## How it works

| Part | What |
|---|---|
| Watch (`wear`) | Buddy's face, mic and speaker, "Hey Buddy" (sherpa-onnx keyword spotting on the watch), Learn my voice |
| Phone (`app`) | Runs the conversation (ChatGPT voice over WebRTC), does the actions, keeps the sign-in |
| Between them | Wear Data Layer channel, ADPCM audio, both ways |
| AI | OpenAI, under your own ChatGPT account. Optional: Gemma on the phone for chat |

## Modules

| Module | What |
|---|---|
| `core/brain` | Brain interface, errors, router, tools (`Toolbox`). Plain Kotlin/JVM. |
| `core/brain-chatgpt` | Sign in with ChatGPT (PKCE + loopback, device code), token refresh, Responses streaming with tools. JVM, unit-tested. |
| `core/brain-ondevice` | Gemma via LiteRT-LM, Gemini Nano via ML Kit, verified model download. |
| `core/voice` | ChatGPT voice (GPT-Live over WebRTC), talking over Buddy, hand-offs to the tools, ending on "bye". |
| `core/watchlink` | The watch-phone audio link: frames, ADPCM, outbox, jitter buffers, leveller. JVM, unit-tested. |
| `core/buddy`, `core/buddy-ui` | Buddy's shapes, faces, moods and drawing (ported from bloub, MIT). |
| `core/security` | Keystore + Tink vault, log redaction, screen protection. |
| `core/testing` | Fakes and synthetic test data. |
| `app` | Phone app: Buddy, conversations, actions (texts, calls, messages, calendar and so on), settings. |
| `wear` | Watch app: face, conversations through the phone, "Hey Buddy", Learn my voice. |

## Build and test

JDK 17 is picked by `gradle/gradle-daemon-jvm.properties`, so the machine's `JAVA_HOME` doesn't matter.
Android SDK at `C:\dev\tools` (`local.properties`).

```
gradlew test testDebugUnitTest      # JVM tests (no phone needed)
gradlew lintDebug spotlessCheck checkSyntheticFixtures
gradlew assembleDebug assembleRelease
gradlew connectedDebugAndroidTest   # on the phone (vault, Keystore)
```

Test the watch with release builds (`:wear:assembleRelease`): debug builds are much slower on it.
Debug builds have test hooks for "Hey Buddy" (`HearWav`, `SpeakWav`, `TeachWav`, `TuneWake`), for
synthetic voices only.

**Test data is synthetic only.** Anything token-shaped in test sources needs `SYNTHETIC` on the same line;
`checkSyntheticFixtures` fails the build otherwise. Commits carry no co-author lines (`.githooks/commit-msg`,
enable with `git config core.hooksPath .githooks`).

## Security and privacy

`SECURITY.md`, `PRIVACY.md`, `docs/security/` (threat model, controls, key management, incident response),
`docs/privacy/` (data inventory, Play Data safety, GDPR records), `docs/adr/` (decisions).
