# Glossary

Words used in the code and the docs.

---

- **ADPCM**: A compact audio format. The watch and the phone send each other audio in it.
- **Buddy**: The app, and the character on the watch and the phone.
- **Buddy Plus**: Optional extras for people who want to support Buddy, sold through RevenueCat. It
  unlocks the `plus` entitlement.
- **Data Layer**: Wear OS's channel between a watch and its phone. Buddy streams audio over it.
- **Gemma**: Google's open AI model. Buddy runs it on the phone to read what's private.
- **GPT-Live**: ChatGPT's realtime voice. Buddy's conversations run on it, under your own account.
- **Guard**: The safety gate. Every tool call goes through it (`core/brain/.../guard/Guard.kt`).
- **"Hey Buddy"**: The wake phrase, heard on the watch itself with sherpa-onnx keyword spotting.
- **Learn my voice**: Five sentences you read on the watch, so "Hey Buddy" hears you better.
- **Level**: How much can go wrong if a tool runs without you wanting it: `LOOKUP`, `LOCAL`,
  `PRIVATE`, `OUTBOUND` or `NEVER`. See [How it works](architecture.md#the-safety-gate).
- **Leveller**: Lifts the quiet watch microphone to a level the wake word model can hear.
- **LiteRT-LM**: Google's runtime for language models on phones. Gemma runs on it.
- **Presence**: Whether you're there: the watch unlocked on your wrist, the phone unlocked, or earbuds
  in. Outbound actions need it.
- **Private readout**: Reading your messages, notes or calendar on the phone and saying the answer in
  the phone's own voice, instead of sending the data to ChatGPT.
- **Redactor**: Takes codes, numbers, links, cards, IBANs, passwords and keys out of text, for when data
  does go to ChatGPT.
- **Test Store**: RevenueCat's simulated store for development. No money moves.
- **Vault**: Where the ChatGPT sign-in is kept: Tink, under a key in Android's Keystore.
