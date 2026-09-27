# Play Data safety answers (M1 design)

"Collected" in Play's sense means sent off the device by the app; data processed only on the phone
isn't collected.

| Data type | Collected | Shared | Optional | Purpose | Why |
|---|---|---|---|---|---|
| Messages → Other in-app messages (questions/answers) | Yes, only when the user uses ChatGPT | No | Yes | App functionality | Sent to OpenAI at the user's request, under the user's own account |
| App info and performance → Diagnostics | Yes, only with Gemini Nano opt-in | No | Yes | Analytics (by the ML Kit SDK) | Google's ML Kit disclosure |
| Device or other IDs | Yes, only with Gemini Nano opt-in | No | Yes | Analytics (by the ML Kit SDK) | Per-installation id used by ML Kit |
| Audio → Voice or sound recordings | Yes, only in ChatGPT voice | No | Yes | App functionality | Streamed to OpenAI at the user's request, under the user's own account; not stored by the app |
| Calendar → Calendar events | Yes, only when the user asks about their calendar | No | Yes | App functionality | Read and added through Android's calendar with the user's permission; events read go to OpenAI with that request |
| Personal info → Other (notes) | Yes, only when the user adds or reads notes | No | Yes | App functionality | Notes are kept encrypted on the phone; sent to OpenAI only with the request |
| Everything else | No | No | — | — | Stays on the phone |

To check before release: whether Play treats user-initiated transfers to the user's own OpenAI account as "shared" (see the user-initiated action exemption).

Security practices: encrypted in transit — **yes**. Users can request deletion — **yes** (in-app
"Delete everything"; email for anything else). Independent security review — **no** (MASA later).

Also declare: target audience 18+, AI-generated content (in-app flagging present), no ads.
