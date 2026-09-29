# Buddy

**A private, hands-free AI agent on the smartwatch you already wear.** It doesn't just answer, it acts
on your phone: texts, calls, messages, timers, calendar. It runs on the AI plan you already pay for,
and your private data never leaves your phone.

> For the Devpost form. **Tagline:** A private AI agent for the watch you already own: hands-free,
> acts on your phone, runs on your own ChatGPT plan, keeps your data on-device.
> **Built with:** kotlin, jetpack-compose, android, wear-os, revenuecat, openai, chatgpt, webrtc,
> gemma, litert, on-device-ai, sherpa-onnx, tink

## The problem

Most of what I want from an assistant is small. Tell someone I'm late. Set a timer with dough on my
hands. Hear what a message says while I'm on the bike. Each time it's the same: take the phone out,
unlock it, find the app, type.

AI agents can do this kind of thing by now, but they're stuck on the laptop. The AI wearables that
bring them onto your body (pins, pendants, little characters you clip on) are one more gadget to buy,
charge and carry, often with one more subscription.

And an assistant that reads your messages usually sends them to a server, where every message it
reads can try to talk the AI into doing something else.

## What Buddy does

Buddy is a voice-first AI agent that lives on the watch and earbuds people already have. Raise your
wrist and say "Hey Buddy", or tap the watch, and say what you need. The phone stays in your pocket.

- "Text Anna I'm ten minutes late." Buddy reads it back and sends it when you say yes.
- "Any new messages?" Your phone reads them to you, in its own voice.
- "Reply to Anna: see you at eight." "Call Jan." Again, only after your yes.
- "Timer for the pasta, ten minutes." It rings on your wrist.
- "What's on my calendar?" "Add milk to my notes." "Pause the music." "Where's my phone?"
  "Take me to the station."
- "Bye", and Buddy hangs up.

A small character on the watch shows what's going on: it listens, thinks, talks and reacts. Every
Buddy is different. Its shape, colour and face come from your account, so no one else has yours.

## Why it's different

- **No new hardware.** Your watch is the microphone, speaker and face. With earbuds in, they take over
  the sound.
- **Bring your own AI.** Sign in with ChatGPT and Buddy runs on your Plus or Pro plan. No API keys, and
  no servers of mine: your requests go from your phone to OpenAI, under your own account.
- **Privacy-first, with on-device AI.** ChatGPT hears what you ask and decides what to do. Reading your
  messages, notes and calendar is done by an AI model that runs on the phone, and the phone says the
  answer in its own voice. ChatGPT only learns that the phone told you. While the phone speaks,
  ChatGPT's microphone hears silence, so nothing slips back in.
- **Agent safety by design.** Prompt injection is the weak spot of AI agents: any message they read can
  try to give them orders. So every action goes through one gate written in code, not in a prompt. Texts and calls go out only after your yes in a later turn, only while you're there (the
  watch unlocked on your wrist, or the phone unlocked), and at most ten an hour. A number or link in a
  message can never become a recipient. Banking and password apps are never read, one-time codes never
  leave the phone, and "stop" stops everything.
- **Transparent.** "What Buddy did" lists every action. "Messages Buddy has" shows exactly
  what it keeps from your notifications. "See what ChatGPT gets" lets you paste any tricky message and
  shows what would leave the phone.

## Buddy Plus, with RevenueCat

Buddy does everything for free. The AI runs on your own plan and there are no servers to pay for, so
there's no reason to lock the useful parts away. Plus is for people who want to support Buddy, and it
adds a few extras:

- **Choose your Buddy.** Pick any look you like; it follows you to the watch.
- **New things first.** Early access to what comes next, starting with Buddy on your computer.
- **A thank-you mark** next to your Buddy in the app.

Privacy and safety are the same for everyone and never part of Plus.

RevenueCat runs all of it. The offers and prices come from RevenueCat, the `plus` entitlement unlocks
the extras, a purchase comes back on a new phone with Restore, and RevenueCat's Test Store lets anyone
try the whole flow without a store account. RevenueCat only sees an anonymous id and the purchase,
never a conversation.

## How I built it

- **Native Kotlin and Jetpack Compose**, on Android and Wear OS: one repository, 11 modules, over 200
  tests.
- **The watch is microphone, speaker and face; the phone runs the conversation.** Android doesn't let
  apps use a watch as a Bluetooth headset, so Buddy has its own audio link over the Wear Data Layer:
  compressed 40 ms frames, 0.1 to 0.5 s there and back.
- **Voice** is ChatGPT's realtime voice over WebRTC. When Buddy has to do something, the voice hands
  the request to ChatGPT with function calling, and the phone runs the action. From the end of your
  sentence to Buddy's answer takes about a second and a half.
- **"Hey Buddy" runs on the watch** (keyword spotting with sherpa-onnx). Watch microphones are quiet,
  so the sound is levelled first. I tested it with 322 synthetic voices in 74 languages, and added
  "Learn my voice": read five short sentences and it hears you better.
- **The AI on the phone** is Gemma 4, downloaded once and checked against its hash. You can pick a
  bigger model if your phone has the memory. Private answers are spoken by Android's own offline
  voice.
- **Buddy's animation** is a Kotlin port of an open-source character engine (bloub, MIT), with our own
  shapes, colours and a mouth. On the watch it draws on its own thread at 10 to 24 frames a second,
  about 10% CPU during a conversation.
- **Security:** the sign-in sits in a hardware-backed vault (Android Keystore and Tink), there are no
  analytics, and every push runs secret scanning, dependency checks and static analysis.

## Challenges

- **Getting audio off the watch fast enough.** Opus through Android's codec took up to a second per
  frame on the watch. I switched to ADPCM and a queue that drops old audio instead of falling behind.
- **"Hey Buddy" for everyone, without draining the battery.** It listens only after you raise your
  wrist, levels the quiet microphone, and was measured on hundreds of voices instead of mine only.
- **Safety that doesn't depend on the AI behaving.** A prompt can't stop a message from tricking the
  model, so the rules live in code, and private data never reaches the cloud model at all.
- **Keeping the private readout private.** The phone's voice plays while the cloud microphone gets
  silence, so it can't leak back through the microphone.

## What I'm proud of

It works end to end on a real watch and phone, with the phone in a pocket. And the split feels right:
the cloud model decides what to do, the phone reads what's yours.

## What I learned

- The cloud AI doesn't need your data to be useful. Deciding what to do only takes your request;
  reading your messages can happen on the phone.
- Safety has to be code. Telling the model that a message is someone else's words helps, but it's no
  guarantee, so the rules and the private reading live outside the model.
- You trust an assistant with your messages when you can see what it keeps. After I let Buddy read my
  notifications I wasn't sure what it had, so now the app shows it.

## What's next

- **A "Hey Buddy" model of its own**, trained on thousands of synthetic voices: better for every voice,
  and light enough to listen all day.
- **Buddy on your computer:** tasks handed to Claude Code on your own PC, in a sandbox, with the
  approvals on your wrist.
- **Google Play**, and Claude as a second AI plan.

## Try it

The code is open source under Apache-2.0: https://github.com/thenguyentrong/watch-ai. The README has
the build steps. The demo shows Buddy on a Galaxy Watch5 and a Galaxy S23 Ultra.
