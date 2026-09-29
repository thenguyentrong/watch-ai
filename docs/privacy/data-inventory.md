# Data inventory (28.09.2026)

| Data | Personal? | Stored where | Encrypted | Retention | Leaves the phone? |
|---|---|---|---|---|---|
| Chat text | Yes (whatever the user types) | App memory, and History (below) | — | Until the app closes; History 30 days | Only to OpenAI when the user chose ChatGPT |
| ChatGPT access/refresh token | Yes | `noBackupFilesDir/vault` | Tink + Keystore | Until sign-out / delete everything | Sent to OpenAI to authenticate |
| Account id, plan type, masked email | Yes | Same vault file | Tink + Keystore | Same | No |
| Flagged reports | Yes | Vault | Tink + Keystore | Last 50, until delete everything | No (delivery channel decided before launch) |
| Voice audio (ChatGPT voice) | Yes | Not stored | — | Only while talking | Streamed to OpenAI under the user's account while a ChatGPT voice conversation is on |
| Voice captions and the call's transcript | Yes | App memory, and History (below) | — | Until the conversation stops; History 30 days | Recent lines go to ChatGPT with a question the voice hands over |
| History (the user's words and Buddy's replies, voice and chat) | Yes | `noBackupFilesDir/memory`, own Keystore key | Tink + Keystore | 30 days (at most 300 conversations, 3,000 turns), until deleted in History or delete everything; off switch in History | The last conversations of two days and the facts go to ChatGPT at the start of each conversation, cleaned; turns only Gemma heard (marked local) never |
| Facts the user asked Buddy to keep | Yes | Same vault file | Tink + Keystore | Until forgotten (by voice or in History), at most 100 | With every conversation, to ChatGPT |
| Screen of the app in front (accessibility, when a tool asks) | Yes (chats, emails, whatever it shows) | Not stored | — | One look | Labels of controls to ChatGPT; content only to Gemma on the phone |
| Apps Buddy can use | Package names the user turned on; Gemma's answer per app version (money, secrets or other) | `shared_prefs/app_limits*` | Private app storage | Until delete everything | No |
| Notes | Yes (whatever the user asks to note) | `noBackupFilesDir/notes`, own Keystore key | Tink + Keystore | Last 500, until deleted in the app or delete everything; sign-out keeps them | Only to ChatGPT, when the user asks to add or read notes |
| Calendar events (read and added) | Yes | The phone's calendar (Android calendar provider), with the user's permission | Android | The user's calendar | Events read are sent to ChatGPT only when the user asks about their calendar; added events sync with the user's own calendar account |
| Timers and alarms | No | The phone's clock app, or the watch's | — | Clock app | No |
| Watch microphone for "Hey Buddy" | Yes (sound near the watch) | Watch memory only | — | 100 ms at a time, while a window is open (30 s after the screen comes on, or always if the user switches that on) | No; after the phrase, a conversation starts |
| Learn my voice takes | Yes (the user's voice) | Watch memory only | — | Until learned, then dropped | No |
| Learned spellings | Yes (derived from the user's voice) | Watch SharedPreferences | Android file encryption | Until Forget my voice, delete or reinstall | No |
| Messages from notifications | Yes (senders' names and texts) | Phone memory (`MessageInbox`); banking, payment, password manager and authenticator apps skipped (`SensitiveApps`) | — | At most 6 h and 40 messages, gone with the notification or the app | With "Private things stay on this phone" (default): no. Gemma answers on the phone (`GemmaReader.answer`) and the phone says it (`LocalSpeech`, offline voice) or shows it in the chat; ChatGPT gets only "the phone is telling the user". Switched off: to ChatGPT after `Redactor` (codes, cards, IBANs, passwords, keys, links, numbers, emails) |
| Contacts | Yes (names, numbers) | The phone's contacts (read only) | Android | Not copied | The chosen name and number type go to ChatGPT with the request; the number goes only to the carrier |
| Pending message or call | Yes | Phone memory (`Pending`) | — | 2 min, or until the user says yes, no or stop | No |
| Speech of private answers (`LocalSpeech`) | Yes (what the phone says) | A WAV in the app's cache while it's made | — | Deleted as soon as it's read (seconds) | No: Android's speech engine with a voice that needs no network |
| What Buddy did (`ActionLogStore`) | Low (tool name, level, outcome, time; no content, no names) | `noBackupFilesDir/log`, own Keystore key | Tink + Keystore | 30 days, last 1000; clear in the app or delete everything | No |
| Texts and calls the user confirmed | Yes | The phone's SMS and call history | Android | The phone's history | To the other person through the carrier |
| Places and app names | Yes (whatever the user asks) | Not stored (the pop-up's last 8 maps in memory) | — | Until the app closes | To Google Maps or the opened app, on the phone; for the pop-up's map, the place name to OpenStreetMap (Nominatim, then map tiles), with the phone's IP address |
| Voice timings (debug builds) | No (timings and event types only) | App external files dir | — | Manual | Only via `adb pull` by the developer |
| Buddy Plus state (`Plus`) | Pseudonymous (RevenueCat's anonymous app user id) | RevenueCat SDK cache on the phone | Android file encryption | Until uninstall | To RevenueCat at app start (entitlement, prices) and with a purchase or restore; no conversation data |
| Chosen Buddy (Plus) | No (a number) | SharedPreferences | Android file encryption | Until delete everything | No (the seed goes to the watch) |
| Settings (opt-ins, notice accepted) | No | SharedPreferences | Android file encryption | Until delete everything | No |
| GPU-broken flag | No (device model string) | SharedPreferences | — | Until delete everything | No |
| Model file | No | `noBackupFilesDir/models` | — (public file, integrity-checked) | Until deleted | No |
| Benchmark files (debug builds) | No (synthetic prompts, timings only) | App external files dir | — | Manual | Only via `adb pull` by the developer |
| ML Kit diagnostics (opt-in) | Pseudonymous (install id) | Google | Google | Google's terms | Yes, to Google |
