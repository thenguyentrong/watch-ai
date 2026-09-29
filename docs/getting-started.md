# Getting started

How to build Buddy and put it on your phone and watch.

---

## What you need

- A **Wear OS watch** paired with an **Android phone** (minimum Android 12). I build and test on a
  Galaxy Watch5 and a Galaxy S23 Ultra.
- A **ChatGPT Plus or Pro** account. Buddy runs on your plan; there are no API keys.
- The **Android SDK** with platform 37, and adb on the phone and the watch (wireless debugging works).
- **JDK 17.** The Gradle daemon picks it through `gradle/gradle-daemon-jvm.properties`.

## 1. Point Gradle at the SDK

Put the SDK path in `local.properties` at the repository root:

```properties
sdk.dir=C:\\path\\to\\Android\\Sdk
```

## 2. Optional: Buddy Plus

Plus needs a RevenueCat project with an entitlement called `plus` and an offering with at least one
package. RevenueCat's Test Store works without any store account. Add its public key to
`~/.gradle/gradle.properties`:

```properties
revenuecat.apiKey=test_...
```

Without a key, Plus just isn't shown.

> [!IMPORTANT]
> A Test Store key only goes into **debug** builds. RevenueCat's SDK crashes on purpose when a Test Store
> key is used in a release build, so release builds leave Plus out when the key starts with `test_`.

## 3. Install

```bash
gradlew :app:installDebug      # phone
gradlew :wear:installRelease   # watch (release is much faster on it)
```

Both builds are signed with the same debug key. The watch-phone link needs that: the two apps only
talk to each other when their signatures match.

## 4. First run

1. Open Buddy on the phone and sign in with ChatGPT under **Your AI**.
2. Under **What Buddy can do**, allow what you want: contacts, texts, calls, notification access (for
   reading and answering messages), calendar. You can take any of it back later.
3. Optional: in **Your AI**, download an offline model for private readouts. The picker shows which one
   fits your phone.
4. On the watch, open Buddy once and switch on **"Hey Buddy"**. **Learn my voice** helps if it misses
   you.

Now raise your wrist and say "Hey Buddy".

## 5. Check the build

```bash
gradlew test testDebugUnitTest      # JVM tests, no phone needed
gradlew lintDebug spotlessCheck checkSyntheticFixtures
gradlew assembleDebug assembleRelease
```

More in [Testing](testing.md). If something doesn't work, see [Troubleshooting](troubleshooting.md).
