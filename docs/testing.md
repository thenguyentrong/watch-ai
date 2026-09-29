# Testing

How Buddy is checked, on this machine, in CI and on the devices.

---

## Checks

```bash
gradlew test testDebugUnitTest      # JVM tests, no phone needed
gradlew lintDebug spotlessCheck checkSyntheticFixtures
gradlew assembleDebug assembleRelease
gradlew connectedDebugAndroidTest   # on the phone: vault, Keystore
```

- **Unit tests** cover the safety gate and the cleaner (`GuardTest`, `RedactorTest`), the ChatGPT
  protocol (sign-in, token refresh, streaming, errors), the model download, the watch link and the
  audio code.
- **`spotlessCheck`** runs ktlint. **`lintDebug`** fails the build on any lint error.
- **`connectedDebugAndroidTest`** runs on the phone, because the vault needs the real Keystore.

## Test data is synthetic only

No real messages, names, numbers or tokens go into tests. Anything token-shaped in test sources (JWTs,
`sk-` keys, GitHub tokens) needs `SYNTHETIC` on the same line, or `checkSyntheticFixtures` fails the
build. The secret scan in CI lets those lines through.

## CI

Every push to `main` runs [`.github/workflows/ci.yml`](../.github/workflows/ci.yml):

| Job | What |
|---|---|
| `build` | Formatting, the synthetic-data check, tests, lint, both builds and the SBOM; 16 KB page alignment; checksums; the debug APK and reports as artifacts |
| `secrets` | gitleaks, with the rules in `.gitleaks.toml` |
| `sast` | mobsfscan |
| `osv` | OSV-Scanner on the dependencies |
| `commits` | Checks the commit messages |

## Debug hooks on the phone

Debug builds have a broadcast receiver for opening screens and showing made-up pop-ups without
tapping. It needs the `DUMP` permission, so only adb can call it. Buddy has to be open first:

```bash
adb shell am start -n com.vinhnguyen.watchai/.MainActivity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER
adb shell am broadcast -n com.vinhnguyen.watchai/.debug.TestHooks --es page safety
```

- `--es page` opens a screen: `home`, `menu`, `chat`, `abilities`, `activity`, `ai`, `settings`,
  `safety`, `inbox`, `plus`, `look`, `voice_lab`, `buddies`.
- `--es card` shows a made-up pop-up: `timer`, `note`, `app`, `text`, `call`, `place`, `messages`.
- `--es check speak` tries the phone's own voice (add `--ez aloud true` to hear it).
- `--es screen <tool>` runs one screen tool through the real Guard on the app in front (Buddy's
  accessibility service has to be on): `look_at_screen`, `find_on_screen`, `tap`, `type_text`,
  `scroll`, `go_back`, `read_screen`, with `--es args '{"id":3}'`. The log (tag `BuddyTest`) shows
  what ChatGPT would see; for `read_screen` only how long the phone took and how many words it said,
  never what. Add `--ez aloud true` to hear it.

```bash
adb shell am broadcast -n com.vinhnguyen.watchai/.debug.TestHooks --es screen look_at_screen
adb shell am broadcast -n com.vinhnguyen.watchai/.debug.TestHooks --es screen read_screen --es args '{"instruction":"say who wrote the newest message"}' --ez aloud true
```

Gemma's calls, with made-up inputs from the command line (so no language or app is written into the
code, and the answers can be logged):

- `--es check answer --es data '<messages>' --es language <tag>`: its answer, the language it came
  out in, and whether the phone has an offline voice for it.
- `--es check tap --es app <name> --es words '<label>' [--es name <view id>] [--ez list true]`:
  `moves` or `does`.
- `--es check app --es label <name> --es pkg <package>`: `money`, `secrets` or `other`.
- `--es limits <package>`: whether Buddy stays out of an installed app. `--es check voices`: the
  phone's offline voices.
- `--es e2e '<what the user says>'`: a conversation with the real ChatGPT planner, as a watch call's
  look-ups have it (the same tools, the same Guard, Gemma on the phone), one typed turn at a time;
  `--es e2e_new x` starts a new one. The log (tag `BuddyE2E`) shows each tool call, what ChatGPT got
  back and its answer; what the phone says privately only as a word count and its language.
  `--es e2e_message '<from>|<text>'` puts a made-up message into the inbox (from a made-up chat
  app that's on), `--es e2e_clean x` takes those and the test's notes out again.
- `--es tool <name> --es args '<json>'`: any tool through the real Guard, as if the user had just
  spoken (for `remember`). `--es check memory`: what a new conversation would start with, as counts;
  `--ez reveal true` shows it (made-up content only). `--es demo add` / `--es demo remove` puts a
  made-up conversation into History and takes it out again.

A shell script with many of these lines, pushed with `adb push` and run with `adb shell sh`, keeps
text in any script intact (Windows can garble it on the command line).

> [!WARNING]
> Tapping Buddy on the phone starts a real ChatGPT conversation, with the microphone on.

## Debug hooks on the watch

Opening the watch app starts a real conversation. To only switch "Hey Buddy" back on after an install,
open it with `quiet`:

```bash
adb shell am start -n com.vinhnguyen.watchai/com.vinhnguyen.watchai.wear.WearActivity --ez quiet true
```

Debug builds of the watch also have receivers for testing "Hey Buddy" with recorded clips (`HearWav`,
`TeachWav`, `TuneWake`, `SpeakWav`) and `BuddyShowcase`, which draws Buddy without a conversation. They
are in [`wear/src/debug`](../wear/src/debug). Test the watch with release builds when speed matters.

## In the app

**Settings → See what ChatGPT gets.** Type a message someone could send you, or pick a tricky one
(a prompt injection, a one-time code, a fake parcel, bank details, a password). Buddy treats it like
your real messages and shows what your phone would say and what would reach ChatGPT. Nothing is sent.

## Reports

- [M1](test-reports/m1.md): the phone brains, ChatGPT and Gemma, tested on the S23 Ultra.
- [Voice spike](test-reports/voice-spike.md): the first spoken conversations, with timings.
