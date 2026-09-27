# 0001 — Phone only, no servers (2026-09-27)

**Decision.** M1 runs every brain from the phone. No backend of ours.

**Why.** The user's own AI plan should pay, and anything we host would hold users' logins and chats —
more to secure, more to certify, and a monthly bill.

**Consequence.** ChatGPT (Codex sign-in, runs from the phone) and Google's on-device models work.
Claude and Copilot don't: Anthropic forbids third-party apps to sign in with Claude and Claude Code
can't run on Android; Copilot's official runtime needs a desktop or server. They move to M3, either
through a small app on the user's computer or a server — decided then.
