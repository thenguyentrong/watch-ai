# Voice spike test report — 2026-09-27

Device: Galaxy S23 Ultra (SM-S918B), Android 16. Debug build, "Voice" tab (debug builds only).
Goal: a real spoken conversation (talk, it answers out loud, talk over it to interrupt), not dictation.

## Engines

| Engine | How | Where the audio goes |
|---|---|---|
| ChatGPT voice | GPT-Live (`gpt-live-1-codex`) over WebRTC, call set up with the user's ChatGPT sign-in (Codex realtime endpoint, undocumented) | phone ↔ OpenAI, the user's own plan |
| On this phone | on-device speech recogniser → Gemma 4 E2B (LiteRT-LM, GPU) → Android text-to-speech, sentence by sentence | stays on the phone |

## Results so far

| Check | Result |
|---|---|
| GPT-Live call from the phone | works — first live call lasted ~8 min (15:51–15:59) |
| GPT-Live connect time (tap → connected) | 963 ms after the changes below (was ~2.3 s) |
| GPT-Live turn time, live test by a person (end of speech → first sound) | 1.1 / 1.3 / 1.5 s (median 1.3 s, 3 turns) |
| GPT-Live: "what time is it", "what is JEV" | silence on the first try (see below); fixed, to re-test by voice |
| Stop tapped while GPT-Live is still connecting | crashed natively on the first build (see below); 3/3 clean after the fix |
| Audio mode back to normal after every stop | pass |
| On-device: recogniser | on-device recognition available and used |
| On-device: first answer, cold model | 8.6 s (7.2 s of it loading Gemma) |
| On-device: model loaded before the first question | pass — loads while the Voice screen is open and while the user is still talking |
| On-device: turn time with a warm model | to measure (expected ~1–1.5 s: recogniser end-of-speech + ~0.2 s first token + TTS start) |
| Interrupting (talking over the answer), both engines | to measure |
| Gemma listening to raw audio (no recogniser), 4 s clip | load 6.5 s, first word 1.2 s, total 1.6 s — works, but slower than the recogniser path |
| Screens in dark and light mode | checked on the phone; screenshots kept outside the repo |

Timings are saved automatically when a session stops, to the app's own files folder
(`Android/data/com.vinhnguyen.watchai/files/voice/voice-<time>.json`): latencies and notes only, never what was said.

## Crash found and fixed

Tapping Stop about a second after Start (while the call was still being set up) killed the app:
`SIGSEGV` in `PeerConnection.nativeSetRemoteDescription`. `stop()` had disposed the WebRTC connection while `start()`
was still waiting for OpenAI's answer, and `start()` then used the disposed connection.

Fix: every WebRTC object is now created, used and disposed on one thread, and `stop()` cancels the connect step and
waits for it before disposing anything. Same idea for the on-device session (stop is instant; late callbacks find nothing to touch).

## Questions that got no answer (fixed)

GPT-Live answers small talk itself, but hands anything that needs facts, current information or the
exact time to the client as a *delegation* (`delegation.created` on the data channel, item with
`target: "client"`, an `id` and the task as `input_text`). The app ignored it, so the voice waited forever.

Now the app answers it: ChatGPT (same plan, `gpt-6-luna`, web search on, told the phone's local time)
works out the answer, and it goes back as `delegation.context.append` frames (`delegation_item_id`,
`channel: "speakable"`, ≤ 500 bytes each, ≤ 1,800 characters in total), which the voice then says in its
own words. Wire format as in OpenClaw's client (`extensions/openai/realtime-quicksilver-*.ts`).
Checked on the phone that the data channel takes client events: a `session.context.append` was
acknowledged (`session.context.appended`) and spoken.

Every prompt now carries the phone's local time, date and time zone (text chat, on-device voice,
the GPT-Live call and its look-ups). Checked in the text chat: Gemma answered the time correctly.
Captions for GPT-Live now read the events it really sends (`input_transcript.added`,
`output_transcript.added`, `turn.done`).

## Round 3 (same day): interrupting and phone actions

**Interrupting.** In a 17-turn test the answer kept playing 1.1–5.3 s after the user started talking over it:
GPT-Live only stops when its own detector is sure. The phone now mutes the answer itself after 0.2 s of speech
over it and unmutes when the answer stops or after 1 s of quiet (a cough, "mm-hm"). The first 0.4 s of every answer
only measure how much of it leaks into the mic, so the answer can't mute itself; a mute never lasts over 3 s.
The server's own stop time is still noted in the session details. To re-test by voice.

**Phone actions.** ChatGPT (voice hand-offs and the text chat) can now add and read notes (kept encrypted in the app,
own vault and key), add and read calendar events and reminders (the phone's calendar, with the user's permission, so
they sync to Google or Samsung calendar and the watch), and set timers and alarms (clock app). Only adding and reading,
every argument checked, results as short sentences. Function calling on the Codex endpoint works: checked on the phone
in the text chat — "add test note from setup to my notes" called `add_note` and confirmed (5.2 s, two round trips),
"what is in my notes?" called `list_notes`; the test note was deleted afterwards. Calendar, reminders, timers and
alarms were not run by me (they touch the user's real calendar and clock) — to test by the user.

Gemma on this phone can't use the actions yet; it's told so and says it needs ChatGPT instead of pretending.

## Other changes in this round

- Model load can no longer be abandoned halfway (that would leak a 1–2 GB native engine); an abandoned load is freed after the idle timeout.
- The first spoken piece may end at a comma (≥ 28 characters), so the voice starts sooner on long first sentences.
- Spoken-style prompt for voice (short, no symbols, no "anything else?" after every answer).
- Answers play on a headset if connected, else the loudspeaker (not the earpiece); music pauses during a conversation; volume keys set the call volume.
- Interrupted turns are kept in the conversation, so "no, I meant…" still has context.
- Shorter ICE wait before posting the GPT-Live offer (400 ms instead of 2.5 s).

## Open

- Live turn and interrupt timings for both engines (needs a person talking to it).
- Delegations answered by voice: works (9 in the 16:50 test; 1.8–3.4 s, 8–12 s when the web was searched).
- Interrupting with the local mute; calendar, reminder, timer and alarm actions by voice.
- A foreground service with the microphone type before voice can run with the screen off or from the watch (M2).
