# Overview

**Buddy** is a private, hands-free AI agent on the smartwatch you already wear. You raise your wrist and
say "Hey Buddy", or tap the watch, and say what you need. Buddy answers on the watch or in your ear,
and does the thing on your phone while the phone stays in your pocket.

---

## The problem

Small things are still fiddly. Telling someone you're late, setting a timer, checking what's next,
finding your phone. AI agents can already do a lot of this, but mostly at a computer: open the laptop,
type, wait. On the phone it means unlocking, finding the app and typing again. Often you only want one
small thing done, right now, with your hands full.

New AI gadgets try to fix that, a pin or a charm with a little character on it. But that's another
device to buy, charge and carry, usually with its own subscription.

Most people already wear a smartwatch, and earbuds or headphones for a big part of the day. Buddy
uses those.

## What Buddy does

- **Talk from the watch** with the phone in your pocket, on ChatGPT's voice and your own plan. The watch
  is microphone, speaker and face; with earbuds in, they take over the sound. Say "bye" when you're done.
- **"Hey Buddy"**, heard on the watch itself after you raise your wrist, or all the time if you switch
  that on. **Learn my voice** (read five short sentences) helps it hear you.
- **Things on your phone**: text and call people, read and answer your messages (WhatsApp, Signal, SMS
  and more, through their notifications), timers and alarms, calendar and reminders, notes, music and
  volume, ringer and Do Not Disturb, find my phone, flashlight, open apps, directions in Maps.
- **Your own Buddy**: its shape, colour and face come from your account. On the phone it's the whole
  home screen: tap it and talk.

## What makes it different

- **No new hardware.** The watch and earbuds you already have.
- **Bring your own AI.** Sign in with ChatGPT and Buddy runs on your Plus or Pro plan. No API keys, and
  no servers of mine.
- **Privacy-first, with on-device AI.** ChatGPT hears what you ask and decides what to do. Your
  messages, notes and calendar are read by an AI model on the phone, and the phone says the answer
  itself. See [How it works](architecture.md#private-readout).
- **Agent safety by design.** Every action goes through one gate written in code, not in a prompt. See
  [Agent safety](security/agent-safety.md).
- **You can see it.** "What Buddy did" lists every action, "Messages Buddy has" shows what it keeps from
  your notifications (in memory only, at most 6 hours), and "See what ChatGPT gets" shows what would
  leave the phone for any message you type.

## Buddy Plus

Buddy does everything for free. Plus, through RevenueCat, is for people who want to support it:
choose your Buddy (any look, on the watch too), new things first, and a thank-you mark. Privacy and
safety are never part of it. Offers, prices and the `plus` entitlement come from RevenueCat.

## Status

Buddy runs on a Galaxy Watch5 and a Galaxy S23 Ultra, the devices I build and test on. It isn't on
Google Play yet. The beta waitlist is at [heybuddy-watch.vercel.app](https://heybuddy-watch.vercel.app).

## What's next

- A "Hey Buddy" model of its own, trained on thousands of voices: better for everyone, and light enough
  to listen all day.
- Buddy on your computer: tasks handed to Claude Code on your own PC, in a sandbox, with approvals on
  your wrist. The plan is in [Agent safety](security/agent-safety.md#on-the-pc).
- Google Play, and Claude as a second AI plan.

The longer story, written for the RevenueCat Shipaton 2026: [shipaton.md](shipaton.md).
