# Bonsai for Android

This directory builds a single, signed, arm64 Android APK. The APK contains the
UI, PrismML `llama.cpp` runtime, and the official Bonsai 1.7B Q1 model for
immediate offline text chat. Larger models are optional verified downloads into
app-private storage:

| Model | Download | Purpose |
| --- | ---: | --- |
| Bonsai 1.7B Q1 | Included (248 MB) | Fast offline text default |
| Bonsai 4B Q1 | 572 MB | Text model for older/lower-memory phones |
| Bonsai 8B Q1 | 1.16 GB | Higher-quality text on mainstream phones |
| Bonsai 27B Q1 + Q8 projector | 4.43 GB | Flagship-class reasoning and vision |

Bundled extraction and downloads resume from `.part` files and are accepted only
after their published SHA-256 hashes and exact byte sizes match. The included
model is copied from the APK into app-private storage on first launch, so allow
roughly 500 MB for the APK plus extracted 1.7B model.

## Phone expectations

- Android 9 or newer on `arm64-v8a`.
- A 12 GB-class flagship is recommended for 27B. PrismML reports roughly 5.2 GB
  peak memory for its language model at 4K context before vision overhead.
- The Android build currently uses ARM CPU inference. PrismML's published phone
  speed is for its iPhone/MLX runtime, so do not assume the same speed on Android.
- The official smaller Bonsai releases are text-only. Photos are enabled only
  when 27B Vision is active, resized to at most 1280 px, and never uploaded.
- Advanced settings expose CPU threads, context, system prompt, temperature,
  Top-P, Top-K, and response length. Try 4-6 threads on heterogeneous phone CPUs.

## Build and install

Run from this directory:

```bat
build-apk.bat
```

The current Windows cross-build also expects MSYS2 UCRT64 `g++` at
`C:\msys64\ucrt64\bin\g++.exe`; upstream uses it to generate an empty embedded
server-UI stub. Bonsai's own WebView UI is packaged separately in the APK.

The script checks the Android toolchain, pins PrismML's runtime to commit
`62061f91088281e65071cc38c5f69ee95c39f14e`, downloads and verifies the official
1.7B asset when necessary, and creates:

```text
app\build\outputs\apk\release\app-release.apk
```

Install it on a connected phone with:

```bat
adb install -r app\build\outputs\apk\release\app-release.apk
```

## Runtime configuration

The app starts `llama-server` on loopback only with the 4-bit K/V cache, Flash
Attention, and a 4096-token default context. The Q8 vision projector is loaded
only for Bonsai 27B Vision. Interrupted downloads resume from `.part` files.

Official resources:

- [Bonsai 27B model card](https://huggingface.co/prism-ml/Bonsai-27B-gguf)
- [PrismML release announcement](https://prismml.com/news/bonsai-27b)
- [PrismML llama.cpp fork](https://github.com/PrismML-Eng/llama.cpp)
