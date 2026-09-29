# Record of processing (GDPR Art. 30) and DPIA screening

Controller: Vinh Nguyen (VYDE), vyde.apps@gmail.com. Processors of ours: none (no servers). OpenAI,
Hugging Face, Google and the OpenStreetMap Foundation act under their own terms with the user when the
user uses those features.

| Processing | Purpose | Legal basis | Data subjects | Data | Recipients | Retention |
|---|---|---|---|---|---|---|
| Answering questions | Provide the app | Art. 6(1)(b) | App users | Questions/answers | OpenAI, only for ChatGPT answers | Session; History below |
| History and remembered facts | Remember what the user talked about and asked Buddy to keep | Art. 6(1)(b) (the user can switch it off and delete it) | App users; people they mention | Their words, Buddy's replies, facts they asked to keep | OpenAI, cleaned, at the start of each conversation | 30 days; facts until forgotten |
| Keeping the ChatGPT sign-in | Stay signed in | Art. 6(1)(b); § 25(2) TDDDG strictly necessary | App users | Tokens, account id, plan, masked email | None (on the phone) | Until sign-out |
| Gemini Nano | On-device answers | Art. 6(1)(a) consent; § 25(1) TDDDG | Users who opt in | ML Kit diagnostics | Google | Google's terms |
| Map of a place in the pop-up | Show where a place is | Art. 6(1)(b) | Users who ask for directions | Place name, IP address | OpenStreetMap Foundation (Nominatim, tile servers) | Not stored by us |
| Flagged answers | Safety (Play policy) | Art. 6(1)(f) | Users who flag | Answer, optionally the question | None yet (on the phone) | Last 50 |

## DPIA screening (2026-09-27)

New technology (AI) and users may type sensitive things, so the screening is recorded. Result for M1:
**no full DPIA needed yet** — we don't receive or store conversations, nothing is profiled, and the only
off-device processing is by providers the user chooses under their own account. Redo the screening
before M2 (voice from the watch) and before any server or history feature.
