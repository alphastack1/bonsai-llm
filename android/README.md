# Bonsai 2 for Android

Build instructions, offline behavior, model sizes, signing, and shared UI details are in the [root README](../README.md).

Run `build-apk.bat` here or from the repository root. It fetches and verifies the pinned PrismML ARM runtime, includes **Bonsai 2 2B PQ2_0** for offline chat, and creates `dist/Bonsai-2-Android.apk`.

The 4B and 9B models and matching Q8 image projectors are optional **on-device** downloads. Once downloaded they also work in airplane mode. Inference uses ARM CPU kernels, not the PC's NVIDIA engine. A Windows RAM upgrade has no effect on phone memory or speed.

The package ID remains `com.bonsai.llm`, with version code 4 and version name 3.0.0. The sideload signature is compatible with earlier APKs built using the same local key. Native libraries are extracted by Android because the local server is launched from `nativeLibraryDir` as `libllama_server.so`. The HTML, JavaScript, CSS, fonts and images come directly from the root `static/` directory, avoiding a second copy of the UI source.
