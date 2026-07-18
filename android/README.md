# Bonsai 27B for Android

This directory builds a single, signed, arm64 Android APK. The APK contains the
UI and PrismML `llama.cpp` runtime, but no model weights. On first launch, open
**Models** to download these official Hugging Face artifacts into app-private
storage:

| Artifact | Size | Purpose |
| --- | ---: | --- |
| `Bonsai-27B-Q1_0.gguf` | 3.80 GB | 1-bit language model |
| `Bonsai-27B-mmproj-Q8_0.gguf` | 629 MB | Vision tower/projector |

Downloads resume from `.part` files and are accepted only after their published
SHA-256 hashes and exact byte sizes match. Allow roughly 4.95 GB of free storage
for the weights plus download headroom.

## Phone expectations

- Android 9 or newer on `arm64-v8a`.
- A 12 GB-class flagship is recommended. PrismML reports roughly 5.2 GB peak
  memory for the language model at 4K context before vision overhead.
- The Android build currently uses ARM CPU inference. PrismML's published phone
  speed is for its iPhone/MLX runtime, so do not assume the same speed on Android.
- Photos are selected with Android's system picker and resized to at most 1280 px
  in the WebView before being sent to the local server. Nothing is uploaded.

## Build and install

Run from this directory:

```bat
build-apk.bat
```

The current Windows cross-build also expects MSYS2 UCRT64 `g++` at
`C:\msys64\ucrt64\bin\g++.exe`; upstream uses it to generate an empty embedded
server-UI stub. Bonsai's own WebView UI is packaged separately in the APK.

The script checks the Android toolchain, pins PrismML's runtime to commit
`62061f91088281e65071cc38c5f69ee95c39f14e`, removes stale bundled GGUF assets,
and creates:

```text
app\build\outputs\apk\release\app-release.apk
```

Install it on a connected phone with:

```bat
adb install -r app\build\outputs\apk\release\app-release.apk
```

## Runtime configuration

The app starts `llama-server` on loopback only with the 4-bit K/V cache, Flash
Attention, a 4096-token default context, and the Q8 vision projector. Interrupted
weight downloads can be retried without starting again from zero.

Official resources:

- [Bonsai 27B model card](https://huggingface.co/prism-ml/Bonsai-27B-gguf)
- [PrismML release announcement](https://prismml.com/news/bonsai-27b)
- [PrismML llama.cpp fork](https://github.com/PrismML-Eng/llama.cpp)
