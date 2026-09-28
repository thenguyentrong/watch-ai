# Play Data safety answers (draft, 28.09.2026)

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
| Messages → SMS or MMS, Other in-app messages (messages the user got) | Yes, only when the user asks to read or answer them | No | Yes | App functionality | Read from notifications with notification access; sent to OpenAI with that request |
| Contacts | Yes, only when the user names someone to text or call | No | Yes | App functionality | The chosen name and number type go to OpenAI with the request |
| App activity → Other user-generated content (a place the user asks directions to) | Yes, only when the user asks for directions | Yes? (OpenStreetMap) | Yes | App functionality | The place name goes to OpenStreetMap to show a small map in the pop-up |
| Audio → Voice or sound recordings ("Hey Buddy", Learn my voice) | No | No | Yes | App functionality | Handled on the watch only, never sent |
| Everything else | No | No | — | — | Stays on the phone |

To check before release: whether Play treats user-initiated transfers to the user's own OpenAI account as "shared" (see the user-initiated action exemption), whether the place name sent to OpenStreetMap for the map counts as "shared" (same exemption), and the SMS and Call Log permissions policy (SEND_SMS, CALL_PHONE are restricted for apps that aren't the default handler; the fallback is opening the messages or phone app with the text filled in).

Security practices: encrypted in transit — **yes**. Users can request deletion — **yes** (in-app
"Delete everything"; email for anything else). Independent security review — **no** (MASA later).

Also declare: target audience 18+, AI-generated content (in-app flagging present), no ads.
