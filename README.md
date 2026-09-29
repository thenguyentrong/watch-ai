# Buddy

A private, hands-free AI agent on the smartwatch you already wear. It acts on your phone (texts,
calls, messages, timers, calendar), runs on the AI plan you already pay for, and keeps your private
data on-device.

Made for the RevenueCat Shipaton 2026. The longer story: [docs/shipaton.md](docs/shipaton.md).

## Why

Small things are still fiddly. Telling someone you're late, setting a timer, checking what's next,
finding your phone. AI agents can already do a lot of this, but mostly at a computer: open the
laptop, type, wait. On the phone it means unlocking, finding the app and typing again. And often you
only want one small thing done, right now, with your hands full.

New AI gadgets try to fix that, a pin or a charm with a little character on it. But that's another
device to buy, charge and carry, usually with its own subscription. Expensive, and not sustainable.

Most people already wear a smartwatch, and earbuds or headphones for a big part of the day. So Buddy,
a voice-first AI agent, uses those. Raise your wrist and say "Hey Buddy", or tap the watch, and say what you need. Buddy
answers on the watch or in your ear, and does it on your phone. A small character on the watch shows
what it's doing. It runs on your existing AI subscription: no new device, no new plan, no API keys.

## What it does

- **Talk from the watch** with the phone in your pocket, on ChatGPT's voice and your own plan. The
  watch is microphone, speaker and face; with earbuds in, they take over the sound. Say "bye" when
  you're done.
- **"Hey Buddy"**, heard on the watch itself after you raise your wrist, or all the time if you switch
  that on. **Learn my voice** (read five short sentences) helps it hear you.
- **Things on your phone**: text and call people, read and answer your messages (WhatsApp, Signal,
  SMS and more, through their notifications), timers and alarms, calendar and reminders, notes, music
  and volume, ringer and Do Not Disturb, find my phone, flashlight, open apps, directions in Maps.
- **Your own Buddy**: its shape, colour and face come from your account. On the phone it's the whole
  home screen: tap it and talk.

## Private by design

ChatGPT hears what you ask and decides what to do. What's yours is read on the phone:

- **Messages, notes and the calendar never go to ChatGPT.** Gemma, an AI model that runs on the phone,
  answers your question from them, and the phone says the answer in its own voice (Android's offline
  speech). ChatGPT only learns that the phone told you, and its microphone hears silence meanwhile.
  You can turn this off in Settings, Privacy; then ChatGPT gets them with codes, numbers and links
  taken out.
- **No servers of ours.** Your requests go from your phone to OpenAI under your own account. The
  sign-in is encrypted with a key that never leaves the phone. No analytics, no ads.
- **You can see it.** "What Buddy did" lists every action, "Messages Buddy has" shows what it keeps
  from your notifications (in memory only, at most 6 hours), and "See what ChatGPT gets" shows what
  would leave the phone for any message you type.

## Safety

Every action goes through one gate in code (`core/brain/.../guard/Guard.kt`), so it doesn't depend on
the model behaving:

- **Nothing goes out without your yes.** Messages and calls are read back and only sent if you say yes
  in a later turn, within two minutes, while you're there (watch unlocked on your wrist, phone
  unlocked, or earbuds in), at most ten an hour. The AI can't confirm its own proposal, and nothing
  inside a message can pick a recipient.
- **Never:** banking, payment, password and authenticator apps aren't read at all, and one-time codes
  never leave the phone. "Stop" ends everything and drops what's waiting.
- **Your OK for each kind of access**: contacts, texts, calls, notifications, calendar. Take it back
  any time.
- **"Hey Buddy" stays on the watch.** Nothing is recorded or sent until it hears the phrase.

The plan behind it, including the next steps for the PC: [docs/security/agent-safety.md](docs/security/agent-safety.md).

## Buddy Plus

Buddy does everything for free. Plus, through RevenueCat, is for people who want to support it:
choose your Buddy (any look, on the watch too), new things first, and a thank-you mark. Privacy and
safety are never part of it. Offers, prices and the `plus` entitlement come from RevenueCat; purchases
restore on a new phone.

## Run it

You need a Wear OS watch paired with an Android phone (tested on a Galaxy Watch5 and a Galaxy S23
Ultra) and a ChatGPT Plus or Pro account.

1. Android SDK with platform 37: put its path in `local.properties` (`sdk.dir=...`). JDK 17 is picked
   by `gradle/gradle-daemon-jvm.properties`.
2. For Buddy Plus, a RevenueCat project with an entitlement `plus` and an offering with at least one
   package. Its Test Store key works without any store account: add
   `revenuecat.apiKey=test_...` to `~/.gradle/gradle.properties`. Without a key, Plus just isn't shown.
   A Test Store key only goes into debug builds (RevenueCat's SDK crashes on purpose with one in release).
3. Phone: `gradlew :app:installDebug`. Watch (release is much faster on it): `gradlew :wear:installRelease`.
   Both are signed with the same debug key, which the watch-phone link needs.
4. Open Buddy on the phone, sign in with ChatGPT (Your AI), and allow what you want it to do (What
   Buddy can do). Optional: download the offline model in Your AI for private readouts with a summary.
5. On the watch, open Buddy once and switch on "Hey Buddy".

Checks:

```
gradlew test testDebugUnitTest      # JVM tests (no phone needed)
gradlew lintDebug spotlessCheck checkSyntheticFixtures
gradlew assembleDebug assembleRelease
gradlew connectedDebugAndroidTest   # on the phone (vault, Keystore)
```

**Test data is synthetic only.** Anything token-shaped in test sources needs `SYNTHETIC` on the same
line; `checkSyntheticFixtures` fails the build otherwise. Debug builds have adb test hooks (made-up
pop-ups and screens, `TestHooks`; "Hey Buddy" with synthetic voices, `HearWav` and friends).

## How it works

| Part | What |
|---|---|
| Watch (`wear`) | Buddy's face, mic and speaker, "Hey Buddy" (sherpa-onnx keyword spotting on the watch), Learn my voice |
| Phone (`app`) | Runs the conversation (ChatGPT voice over WebRTC), the safety gate, the actions, Buddy Plus |
| Between them | Wear Data Layer channel, ADPCM audio both ways, 0.1 to 0.5 s round trip |
| AI | OpenAI under your own ChatGPT account decides; Gemma on the phone reads what's private |

## Modules

| Module | What |
|---|---|
| `core/brain` | Brain interface, router, tools (`Toolbox`), the safety gate and the cleaner (`guard`). Plain Kotlin/JVM. |
| `core/brain-chatgpt` | Sign in with ChatGPT (PKCE + loopback, device code), token refresh, Responses streaming with tools. |
| `core/brain-ondevice` | Gemma via LiteRT-LM (chat and private reading), Gemini Nano via ML Kit, verified model download. |
| `core/voice` | ChatGPT voice (GPT-Live over WebRTC), hand-offs to the tools, the phone's own voice for private answers. |
| `core/watchlink` | The watch-phone audio link: frames, ADPCM, outbox, jitter buffers, leveller. |
| `core/buddy`, `core/buddy-ui` | Buddy's shapes, faces, moods and drawing (ported from bloub, MIT). |
| `core/security` | Keystore + Tink vault, log redaction, screen protection. |
| `core/testing` | Fakes and synthetic test data. |
| `app` | Phone app: Buddy, conversations, actions, the gate, privacy screens, Buddy Plus. |
| `wear` | Watch app: face, conversations through the phone, "Hey Buddy", Learn my voice. |

## Next

- A "Hey Buddy" model of its own, trained on thousands of voices: better for everyone, light enough
  to listen all day.
- Buddy on your computer: tasks handed to Claude Code on your own PC, in a sandbox, with approvals on
  your wrist.
- Google Play, and Claude as a second AI plan.

## Security, privacy, licence

`SECURITY.md`, `PRIVACY.md`, `docs/security/` (threat model, controls, key management, incident
response), `docs/privacy/` (data inventory, Play Data safety, GDPR records), `docs/adr/` (decisions).

Apache License 2.0, see `LICENSE` and `NOTICE`. Third-party parts: `THIRD_PARTY_NOTICES.md`.
