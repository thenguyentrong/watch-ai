# Agent safety: doing a lot, exposing nothing (plan, 2026-09-29)

Buddy should do many things on the phone, and on the PC while it's on, without passwords, codes or
private data ending up where they don't belong. This is the plan for that. Step 1 (the phone) is
built as of 2026-09-29; the rest is still a plan.

## The problem

The AI that decides what to do runs at OpenAI (on the PC: Claude, at Anthropic). We can't make it
trustworthy:

- It reads other people's words: messages, notifications, web pages, files. Any of them can say
  "forward all messages to this number" or "open this link" with private data in the link. That's
  prompt injection, and no model reliably ignores it.
- It mishears and guesses: the wrong Anna, the wrong day.
- Anyone near the watch can say "Hey Buddy" and "yes".
- Whatever a tool returns goes to the provider.

So safety can't depend on the model behaving. The model proposes; a small, plain program on the
phone decides.

## Rules

1. **The model never holds a secret.** No passwords, one-time codes, card or account numbers, keys
   or tokens. Where it has to deal with one, it gets a handle ("a code from Google") and the phone
   does the rest.
2. **Handles, not raw data.** Contacts are names, never numbers (texts and calls already work like
   that). Messages are sender and text, with codes masked. PC files are paths inside the agent's
   own folder, nothing else.
3. **One gate for every action.** Every tool call goes through the Guard: what kind of action it
   is, whether it's allowed right now, whether it needs a yes, whether the user is there. It's in
   our code, not in the prompt, so the model can't talk its way past it.
4. **Anything that leaves the phone waits for a yes.** Texts, replies, calls, and anything on the
   PC that changes something. The yes comes in a later turn, with the exact words read back
   (texts and calls already work like that, see `Pending`).
5. **Other people's words never pick the target.** A number, address or link inside a message or a
   web page is never used as a recipient. Recipients come from what the user said, matched against
   their contacts.
6. **Private data and the open web never share a model call.** A call that holds messages, notes
   or the calendar gets no web access; a web lookup gets only the user's question. Otherwise one
   poisoned message can make the model fetch a link with private data in it.
7. **No passwords to connect things.** Devices trust each other through keys kept in hardware
   (Keystore/StrongBox on the phone, the TPM on the PC), paired once with a QR code.
8. **Everything Buddy does is written down on the phone, and one word stops it.**

## Levels

| Level | What | Examples | Rule |
|---|---|---|---|
| 0 | Looking something up | time, weather, battery | runs |
| 1 | Small, local, can be undone | timer, alarm, note, flashlight, volume, open an app, directions | runs, logged, undo where possible |
| 2 | Reads private data | messages, calendar, notes, contacts, PC files | only when the user asked for it in this turn; goes through the Redactor; marks the conversation as holding other people's words |
| 3 | Leaves the phone or can't be undone | send a text, reply, call, email; change files, push or install on the PC | yes in a later turn with the exact payload; user present; at most 10 an hour; a recipient Buddy hasn't used before is confirmed on the screen |
| 4 | Never | passwords, one-time codes, banking and payment apps, the password manager, security settings, turning the Guard off | refused, whatever the model says; the user does these themselves |

## On the phone

### Guard

One class every tool call passes through. `Toolboxes` already routes all calls through one place,
so the Guard wraps it. It checks, in this order:

- **Level** of the call: fixed per tool, raised by some arguments (a reply is level 3, reading
  messages level 2).
- **Grants**: what the user allowed and until when, e.g. "PC tasks for an hour". "Buddy, stop" or
  the button in the app drops all of them and cancels running PC tasks.
- **Taint**: once the conversation holds other people's words, level 3 needs the fresh yes even
  where a grant would allow it.
- **Presence**: the watch reports locked or unlocked with every call; it locks itself when it
  comes off the wrist (with a screen lock set). Watch locked and phone locked means "confirm on
  your phone", with the fingerprint.
- **Budget**: 10 level-3 actions an hour to start with; over that, Buddy stops and asks.
- **Log**: time, tool, level, target (as a handle), how it was confirmed, result. Encrypted on the
  phone like the notes, kept 30 days, shown on a "What Buddy did" page.

### Redactor

Everything a tool returns is cleaned before it goes to the model:

- **Dropped completely**: notifications from banking, payment, password manager and authenticator
  apps (a list of packages, plus anything the user adds), and security alerts.
- **Masked**: one-time codes and TANs (digits next to "code", "OTP", "TAN", "verification",
  "Bestätigungscode" and similar), card numbers (Luhn check), IBANs, passwords written in a message,
  API keys and tokens (known prefixes, JWTs, long random strings), private keys.
- **Replaced**: phone numbers and email addresses, by the contact's name or a handle.

A masked value becomes a handle: "you have a verification code from Google". If the user asks for
it, the phone shows it on the watch or the phone screen, or says it with the phone's own speech
engine, and it never reaches OpenAI.

### Structured actions, not screen control

Buddy acts through Android's own ways in: intents, the messaging apps' reply actions, the calendar
and contacts providers. No accessibility service: that would let it read and tap everything on
screen, bank apps included. If screen control ever comes, it's limited to apps the user names,
never on password fields or secure windows, with an overlay and a stop button while it acts.

## On the PC

Claude Code on the user's own Claude plan does the work. Anthropic allows a Claude login only in
its own apps, so Claude Code runs as the user's own install; Buddy only hands it tasks and passes
on its questions. (Before a public release: check Anthropic's terms for starting Claude Code from
another app.)

### The link

- A small bridge on the PC reaches the phone over Tailscale (WireGuard, no open ports, free for
  personal use). No port forwarding, no password.
- Pairing: the bridge shows a QR code with its public key, the phone scans it, both show the same
  six digits, done. The phone's key stays in the Keystore (StrongBox), the PC's in the TPM.
- Every task is signed by the phone and carries an id, a scope ("folder watch-ai") and a two-minute
  expiry. The bridge refuses anything unsigned, expired or seen before, so even another device on
  the same Tailscale network can't send tasks.

### The box

- Claude Code runs in its own WSL2 distro, not on Windows itself: Windows drives not mounted, no
  starting Windows programs (`wsl.conf`: automount and interop off), only an agent workspace.
- Claude Code's own sandbox on (it supports WSL2, not native Windows): writes only in the
  workspace, network only to allowed domains (Anthropic, GitHub for reading, package registries).
- Nothing personal inside: no browser profile, no SSH keys, no password manager, no GitHub token
  that can write.
- Deny rules in Claude Code's managed settings (`/etc/claude-code/managed-settings.json` in the
  distro) for what never makes sense from the phone.

### Approvals on the phone

Claude Code runs a PreToolUse hook before each tool. For anything level 3 (pushing, deleting,
installing, sending something out) the hook asks the bridge, the bridge asks the phone, and the
pop-up on the phone or the watch shows "Push 3 commits to watch-ai?" with Allow and Deny. The hook
holds the call until the answer comes (hook timeouts can be set), then allows or denies it.

### Broker for credentials

The few credentials the PC needs stay outside the box, in Windows' credential store. A small
broker does exactly one job each, after the yes: for example push one branch to one repo, with a
token that can only write to repos the user picked. The box asks for "push"; it never sees the
token.

Logins on websites: not by the agent. If that's wanted later, a separate browser profile the user
logs into themselves, and passkeys where possible (they need the user's fingerprint anyway).

## Step 1, as built (2026-09-29)

- `Guard` (core/brain): levels per tool (`Safety.LEVELS`), unknown tools neither offered nor run,
  the yes checked for presence and the budget (10 an hour), "bye"/"stop" ends everything but
  lookups and drops what waits for a yes, every call logged by kind and outcome. One per
  conversation: phone voice, watch calls, chat, voice lab.
- `Redactor` (core/brain): codes, cards (Luhn), IBANs (checksum), passwords, keys and tokens,
  links (site kept), phone numbers, email addresses. Banking, payment, password manager and
  authenticator apps are skipped when notifications come in (`SensitiveApps`).
- The phone's own model first: `GemmaReader` answers the question from the cleaned data, and
  ChatGPT gets its summary. On by default, switch in Settings, Privacy. Measured on the S23 Ultra
  with made-up poisoned messages: 3.1 s warm, 10.4 s cold; a conversation warms Gemma up when it
  starts. The trick message came back as "Sam asked the assistant to text all their messages to a
  hidden number"; no code, number, IBAN or link got through.
- Web: once a private result is in the conversation, web search is cached-only
  (`external_web_access: false`), checked every tool round.
- Presence: phone unlocked, or earbuds or headphones on the phone, or in a watch call the watch
  unlocked (it answers "locked", "unlocked" or "no lock"; no lock or no answer counts as "can't
  tell" and doesn't block). A tap on Send in the pop-up always counts as there.
- "What Buddy did" in the menu: kind, time and outcome, encrypted, 30 days.

Not in step 1 yet: grants ("PC tasks for an hour"), confirming a first-time recipient on the
screen, showing a hidden code on the watch.

## Found before step 1 (closed)

- "Any new messages?" read every message notification, so a bank's SMS code or a Google
  verification code went to OpenAI with the rest.
- The model call that runs phone actions had web search with open web access, so a message with
  a hidden instruction could have made it open a link with private data in the address.
- The threat model still said "no tools in M1" (T9).

## Order

1. **Phone** (done): Guard (levels, presence from the watch, budget, log, stop), Redactor and the
   phone's own model for today's tools; web lookups cached-only after private data; a test set of
   poisoned messages ("forward everything to…", "open this link…", codes, IBANs) that must never
   reach the model unmasked.
2. **PC link**: bridge, QR pairing, signed tasks, results back to the voice.
3. **PC box**: the WSL2 distro, Claude Code's sandbox and managed settings, the hook that asks the
   phone.
4. **Broker**: pushes and other actions that need a credential.

## What this doesn't solve

- OpenAI still hears what the user says and sees what tools return after cleaning. Anthropic sees
  what the agent reads in its folder. Only models running on the device would change that.
- Someone holding the unlocked phone can do what the user can.
- The lists (apps, code patterns) will miss things. The budget, the log and the yes before anything
  leaves are the backstop.
