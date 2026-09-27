# Third-party notices

What Buddy is built on, where it comes from and under which licence. Checked 27.09.2026.

## Buddy's animation: bloub

- Source: https://github.com/jeremy-prt/bloub
- Licence: MIT, Copyright (c) 2026 Jérémy Perret (full text below)
- Used in: `core/buddy` (Shape.kt, Face.kt, Decor.kt, States.kt, Engine.kt) and `core/buddy-ui` (BuddyDraw.kt), on the phone and the watch

We ported bloub's engine from TypeScript to Kotlin: outlines as radii at fixed angles so any two shapes morph point for point, eyes on a sphere that turns with the head, gaze drift and blinks as pure functions of time, blending from what is on screen, and the animations (thinking dots, the sliding and the upright "!", the badge, the bouncing sleep dot, egg, hexagon, the play triangle with its swoosh, orbit rings, burst and comet).

bloub recreates the x.ai bot avatar, and its own README says the MIT licence covers the code, not the design it imitates. So Buddy does not copy that look: body shapes, colours, eye sizes, resting faces and every face in the animations are our own values, the rings stay in the user's own colour instead of a rainbow, and Buddy has a mouth, which bloub doesn't. No x.ai names, images or measured values are used. Buddy is not affiliated with x.ai or with bloub's author.

```
MIT License

Copyright (c) 2026 Jérémy Perret

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

The same text ships inside both apps (`core/buddy-ui/src/main/assets/licenses/bloub.txt`).

## "Hey Buddy" on the watch

| What | Source | Licence |
| --- | --- | --- |
| sherpa-onnx 1.13.8 (keyword spotting) | https://github.com/k2-fsa/sherpa-onnx | Apache-2.0 |
| ONNX Runtime (inside the sherpa-onnx AAR) | https://github.com/microsoft/onnxruntime | MIT |
| Model sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01 | https://github.com/k2-fsa/sherpa-onnx/releases/tag/kws-models | Apache-2.0, as published by k2-fsa |

The model was trained on GigaSpeech. GigaSpeech's audio is only licensed for non-commercial research and education, so check whether that carries over to the model before a paid or commercial release.

## AI models and voice on the phone

| What | Source | Licence |
| --- | --- | --- |
| Gemma 4 E2B (downloaded on first use, not in the APK) | https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm | Apache-2.0 (Google) |
| LiteRT-LM 0.17.1 | https://github.com/google-ai-edge/LiteRT-LM | Apache-2.0 |
| WebRTC for Android (io.github.webrtc-sdk:android) | https://github.com/webrtc-sdk/webrtc | BSD-3-Clause, plus WebRTC's patent grant |

## Libraries

All Apache-2.0:

- Kotlin standard library, kotlinx.coroutines, kotlinx.serialization (JetBrains)
- AndroidX: Core, Activity, Lifecycle, WorkManager, Browser, Concurrent Futures, Jetpack Compose (UI, Foundation, Material 3), Wear, Wear Compose, Wear Tiles, ProtoLayout (Google)
- OkHttp 5 (Square)
- Tink (Google)
- Timber (Jake Wharton)

## Services and terms (not open source)

- ChatGPT: runs on the user's own ChatGPT account, under OpenAI's terms.
- ML Kit GenAI Prompt API (Gemini Nano): ML Kit Terms of Service.
- Google Play services Wearable (watch to phone link): Google APIs Terms of Service.

## Only in tests (not shipped)

JUnit 4 (EPL-1.0), Truth, Turbine and MockWebServer (Apache-2.0).
