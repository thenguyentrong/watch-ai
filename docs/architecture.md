# How it works

![How Buddy works](media/architecture.png)

Buddy has three parts: the **watch** is the microphone, speaker and face; the **phone** runs the
conversation, the safety gate and the actions; the **cloud** is your own ChatGPT account, plus
RevenueCat for Buddy Plus and a one-time model download.

---

## The watch (`wear`)

- **Buddy's face** shows what's happening: listening, thinking, talking. The character is drawn from
  the app's own animation engine (`core/buddy`, ported from bloub, MIT).
- **"Hey Buddy"** is heard on the watch itself, with sherpa-onnx keyword spotting. Nothing is recorded
  or sent until it hears the phrase. A leveller lifts the quiet watch microphone, and the gates follow
  the room's noise. **Learn my voice** stores only the spellings the model heard in your five takes.
- **Audio** goes to the phone and back over a Wear Data Layer channel, as ADPCM, with a 0.1 to 0.5 s
  round trip. With earbuds in, they take over the sound.

**What it sends:** your voice, after "Hey Buddy" or a tap. **What it gets:** Buddy's voice and face.

## The phone (`app`)

### Conversation

ChatGPT's realtime voice (GPT-Live over WebRTC, `core/voice`), signed in with your own ChatGPT account
(`core/brain-chatgpt`: PKCE in a Custom Tab with a loopback listener, or a device code). ChatGPT hears
what you ask and calls Buddy's tools. On the phone alone, the time from the end of your sentence to the
first sound was 1.1 to 1.5 s in a live test ([voice spike report](test-reports/voice-spike.md)).

### The safety gate

Every tool call goes through `Guard` (`core/brain/.../guard/Guard.kt`) before it runs:

| Level | What | Example |
|---|---|---|
| `LOOKUP` | Nothing personal, nothing changes | the time, the battery |
| `LOCAL` | Small, on the phone, can be undone | a timer, a note, the flashlight, opening an app, tapping and typing in an app |
| `PRIVATE` | Reads your data or other people's words, which stay on the phone | messages, notes, the calendar, what's on an app's screen |
| `OUTBOUND` | Leaves the phone or can't be undone | a text, a reply, a call, a tap on Send, Pay or Delete in an app |
| `NEVER` | Never, whatever the model says | banking, payment and password apps, the phone's settings |

Outbound actions are read back and only happen after your yes, said in a later turn, within two
minutes, while you're there (watch unlocked on your wrist, phone unlocked, or earbuds in), at most ten
an hour. The AI can't confirm its own proposal, and nothing inside a message can pick a recipient.
"Stop" ends everything and drops what's waiting. Unknown tools are hidden and refused. The full
design: [Agent safety](security/agent-safety.md).

### Private readout

When a `PRIVATE` tool runs (reading messages, notes, the calendar), the result never goes to ChatGPT:

1. **Gemma on the phone** (LiteRT-LM, `core/brain-ondevice`) answers your question from the data, in a
   few spoken sentences. Codes, numbers and links are only read out if you asked for them. On the S23
   Ultra that takes about 3 s with the model warm, 9 to 11 s cold; Buddy warms it at the start of each
   conversation.
2. **The phone's own voice** (Android's offline speech) says the answer. ChatGPT's voice is muted and
   its microphone hears silence meanwhile, so nothing slips back in.
3. **ChatGPT only gets** "done: the phone is telling the user this itself", and replies "Here you go".

Without an offline model, the phone reads the items out plainly, with the same rules. You can switch
the private readout off in Settings; then ChatGPT gets the data with codes, numbers and links taken
out by the `Redactor`.

### Actions

Texts and calls, messages through their notifications (WhatsApp, Signal, SMS and more), timers and
alarms, calendar and reminders, notes, music and volume, ringer and Do Not Disturb, find my phone,
flashlight, open apps, directions in Maps. Each one shows a small pop-up on the phone. Banking,
payment, password and authenticator apps are never read.

### Vault

The ChatGPT sign-in is stored with Tink, under a key in Android's Keystore (StrongBox when the phone
has it). Nothing is backed up to the cloud. See [Key management](security/key-management.md).

### Buddy Plus

The RevenueCat SDK loads the offers, runs the purchase and unlocks the `plus` entitlement. RevenueCat
sees an anonymous id and the purchase, never the conversations.

**What leaves the phone:** what you say, and the tool calls ChatGPT makes. **What stays:** your
messages, notes, calendar and sign-in keys.

## The cloud

- **OpenAI, under your own account.** Hears your requests and decides what to do. No servers of mine
  sit in between.
- **RevenueCat.** Offers and purchases for Buddy Plus.
- **Hugging Face.** The on-device model, downloaded once, checked against a pinned SHA-256.

---

## Three examples

**"Text Alex I'm running ten minutes late."** The watch hears "Hey Buddy" and streams your voice to the
phone. ChatGPT calls `send_text_message`. The gate sees `OUTBOUND`, so Buddy reads the text back and
waits. You say "Yes." in the next turn; the gate checks you're there and the hourly budget, and the
text goes out. A pop-up on the phone shows it.

**"Any new messages?"** ChatGPT calls `read_messages`. The gate sees `PRIVATE`: Gemma answers from the
messages on the phone and the phone says it in its own voice. ChatGPT gets only the "phone is telling
the user" note.

**"Set a timer for ten minutes."** `LOCAL`: it runs right away, and the timer pop-up shows on the phone.

**"What's the newest chat in my chat app?"** ChatGPT opens the app, looks at its controls (`look_at_screen`:
labels only, list entries as numbers), finds the chat by the name the user said, taps it (Gemma first
checks the tap only opens something), then calls `read_screen` with "say who wrote the newest message
and what it says" and the user's language. Gemma reads the screen on the phone and the phone says it,
in that language. ChatGPT never sees the chat. Nothing in the code depends on the app or the language.

**"Remember that my sister is called Mai."** ChatGPT calls `remember` (`LOCAL`, only right after the user
spoke). It's kept encrypted on the phone and shown in History; the next conversation, on the watch or
the phone, starts with it and with the last conversations, so "text my sister" finds Mai.

---

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
| `app` | Phone app: Buddy, conversations, actions (incl. using apps on screen), the gate, memory and History, the apps Buddy can use, privacy screens, Buddy Plus. |
| `wear` | Watch app: face, conversations through the phone, "Hey Buddy", Learn my voice. |

The decisions behind the big choices are in [docs/adr](adr/).
