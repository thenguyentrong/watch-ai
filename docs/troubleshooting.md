# Troubleshooting

Common problems and what to do about them.

---

### The watch and the phone don't find each other

- The watch has to be paired with the phone in the Galaxy Wearable or Wear OS app.
- Both apps have to be signed with the **same key**, or the Data Layer won't connect them. Install
  both from this repository (`:app:installDebug` and `:wear:installRelease` use the same debug key).

### The watch app is slow

Install the **release** build on the watch: `gradlew :wear:installRelease`. Debug builds are much slower
on a watch.

### "Hey Buddy" doesn't hear me

- By default the watch listens after you raise your wrist. Raise it, then say it.
- Open **Learn my voice** on the watch and read the five sentences. Buddy keeps only the spellings it
  heard, not your recordings.
- **Always listening** keeps it on all the time. It uses more battery.

### Buddy can't read my messages

- Allow **notification access** in "What Buddy can do". Buddy reads messages from their notifications,
  keeps them in memory only, and for at most 6 hours.
- Banking, payment, password and authenticator apps are skipped on purpose.
- Buddy can reply to a chat that has a notification, but can't start a new WhatsApp chat hands-free.

### Nothing was sent after I said yes

A text or call waits for your yes **in a later turn**, **within two minutes**, **while you're there**
(watch unlocked on your wrist, phone unlocked, or earbuds in), and at most ten an hour. If one of those
doesn't hold, the gate refuses. "What Buddy did" shows what happened.

### Private answers take long the first time

The on-device model has to load: about 9 to 11 s cold on the S23 Ultra, about 3 s warm. Buddy warms it
at the start of each conversation. Without an offline model, the phone reads the items out plainly.

### Buddy Plus isn't there

The build has no RevenueCat key. See [Getting started](getting-started.md#2-optional-buddy-plus). A
Test Store key only goes into debug builds; release builds leave Plus out.

### Sign-in doesn't come back from the browser

Use the device code option instead. It needs **"Allow device code login"** in your ChatGPT security
settings.

### Starting the phone app from adb says "Activity class does not exist"

The app only accepts launches that match its intent filter. Add the launcher action and category:

```bash
adb shell am start -n com.vinhnguyen.watchai/.MainActivity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER
```

### The build picks the wrong Java

Gradle's daemon uses the JDK 17 from `gradle/gradle-daemon-jvm.properties`. If your shell starts
Gradle with an older Java anyway, set `JAVA_HOME` to a JDK 17 for that command.

### Windows blocks a tool

Smart App Control can block unsigned tools, for example the Java runtime bundled with Android's newest
command-line tools. The command-line tools 22.0 work.
