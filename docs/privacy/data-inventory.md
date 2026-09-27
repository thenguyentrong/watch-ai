# Data inventory (M1)

| Data | Personal? | Stored where | Encrypted | Retention | Leaves the phone? |
|---|---|---|---|---|---|
| Chat text | Yes (whatever the user types) | App memory | — | Until the app closes | Only to OpenAI when the user chose ChatGPT |
| ChatGPT access/refresh token | Yes | `noBackupFilesDir/vault` | Tink + Keystore | Until sign-out / delete everything | Sent to OpenAI to authenticate |
| Account id, plan type, masked email | Yes | Same vault file | Tink + Keystore | Same | No |
| Flagged reports | Yes | Vault | Tink + Keystore | Last 50, until delete everything | No (delivery channel decided before launch) |
| Voice audio (ChatGPT voice) | Yes | Not stored | — | Only while talking | Streamed to OpenAI under the user's account while a ChatGPT voice conversation is on |
| Voice captions and the call's transcript | Yes | App memory | — | Until the conversation stops | Recent lines go to ChatGPT with a question the voice hands over |
| Notes | Yes (whatever the user asks to note) | `noBackupFilesDir/notes`, own Keystore key | Tink + Keystore | Last 500, until deleted in the app or delete everything; sign-out keeps them | Only to ChatGPT, when the user asks to add or read notes |
| Calendar events (read and added) | Yes | The phone's calendar (Android calendar provider), with the user's permission | Android | The user's calendar | Events read are sent to ChatGPT only when the user asks about their calendar; added events sync with the user's own calendar account |
| Timers and alarms | No | The phone's clock app | — | Clock app | No |
| Voice timings (debug builds) | No (timings and event types only) | App external files dir | — | Manual | Only via `adb pull` by the developer |
| Settings (opt-ins, notice accepted) | No | SharedPreferences | Android file encryption | Until delete everything | No |
| GPU-broken flag | No (device model string) | SharedPreferences | — | Until delete everything | No |
| Model file | No | `noBackupFilesDir/models` | — (public file, integrity-checked) | Until deleted | No |
| Benchmark files (debug builds) | No (synthetic prompts, timings only) | App external files dir | — | Manual | Only via `adb pull` by the developer |
| ML Kit diagnostics (opt-in) | Pseudonymous (install id) | Google | Google | Google's terms | Yes, to Google |
