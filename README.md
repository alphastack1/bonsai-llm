# Bonsai 2

A private, local AI app for **Windows EXE and Android APK**, rebuilt for PrismML's Qwen3.5-based Bonsai 2 models. Chat, deeper thinking, and optional image understanding all run on your own device.

**Every model is offline after installation.** The app has no hosted inference provider, account requirement, subscription, or cloud API key. Internet access is needed only to obtain the app, optional models, or an additional runtime. Conversations and attached images are stored on-device.

## Get the app

Download `Bonsai-2-Windows.exe` or `Bonsai-2-Android.apk` from the [v3.0.0 release](https://github.com/alphastack1/bonsai-llm/releases/tag/v3.0.0). Both include the **smallest new model, Bonsai 2 2B**, for immediate offline use. On the first launch, the included model is verified and copied into persistent storage; let this finish before chatting.

Windows uses a native WebView2 window. Windows 10/11 x64 and Microsoft Edge WebView2 Runtime are required. Downloaded models persist under `%LOCALAPPDATA%\Bonsai2\models`, independently of the EXE. The one-file EXE temporarily extracts its bundled runtime when launched; allow extra disk space and time for that step. The release bundles CPU, Vulkan, and NVIDIA CUDA 12.4 engines. Automatic mode tries the installed GPU engines and falls back to CPU when a GPU engine cannot start.

Android requires Android 9+ and `arm64-v8a`. It runs the included ARM CPU engine; a dedicated Android GPU backend is not included. Install the APK, wait for the bundled 2B extraction, and chat in airplane mode. The smallest model remains available alongside the optional larger ones. Model data is stored in the app's private storage. Uninstalling the Android app removes that data.

## Model library

| Model | Purpose | Local language download | Optional local image file |
|---|---|---:|---:|
| **Bonsai 2 2B** | Quick answers; included in EXE and APK | 517 MB | 365 MB |
| **Bonsai 2 4B** | Everyday writing and questions | 1.15 GB | 367 MB |
| **Bonsai 2 9B** | More capable writing, coding, and harder questions | 2.42 GB | 624 MB |

These are the **PQ2_0** language packs and matching **Q8_0** vision projectors. Download sizes are decimal and rounded. RAM/VRAM consumption is higher than file size because the engine also needs conversation state and working buffers. Larger models are optional on both platforms, and need enough available memory. The app never switches to a cloud model when memory is tight.

Open **Model library** to download, resume, load, or remove a model. Image support downloads separately for each size; choose **Use with images** after the matching files are installed. Removing image support from inference does not delete its downloaded file. Images are resized locally to a maximum dimension of 1280 pixels, stored locally in IndexedDB, and sent only to the device's loopback inference process.

**Think deeper** enables the model's reasoning mode. It can take much longer than an ordinary answer. Use 16K context or more to leave room for the default 8192-token thinking output budget. Ordinary chat defaults to thinking off and a 2048-token answer budget. Settings also expose context length, CPU threads, and Windows backend selection. Engine changes reload the current model; they do not delete conversations.

Chat history and response instructions stay in local browser/WebView storage. Closing the native desktop window stops its inference process. No network connectivity is required to reopen downloaded models or saved chats. Clearing local app/browser storage removes conversation history. Browser mode stores its history separately from the native Windows window.

## Run from source

Install Python 3.11+ on Windows, then run:

```powershell
.\start.bat
```

The first source launch installs Python dependencies. If runtime/model files are missing, choose **Set up local engine & 2B** in the app. To prepare the files explicitly:

```powershell
.\venv\Scripts\python.exe scripts\prepare.py --platform windows --models 2B 4B 9B
.\venv\Scripts\python.exe scripts\prepare.py --platform windows --backend cuda --models
.\venv\Scripts\python.exe scripts\prepare.py --platform models --models --vision 2B 4B 9B
```

The source app stores models, logs, and preferences in the checkout's ignored directories. Set `BONSAI_DATA_DIR` to use another persistent data folder. The EXE uses `%LOCALAPPDATA%\Bonsai2` by default. For a browser window use `scripts\run.ps1 -Browser`; for a local server use `scripts\run.ps1 -NoWindow -Port 7860`.

## Build EXE and APK

```powershell
.\build-exe.bat -Backend cuda
.\android\build-apk.bat
```

The Windows script prepares CPU plus the selected backend; the spec bundles every backend currently prepared in `bin/`. Prepare Vulkan too for the complete GPU/CPU release. Outputs are `dist\Bonsai-2-Windows.exe` and `dist\Bonsai-2-Android.apk`. Models other than 2B are deliberately not embedded, keeping optional downloads independent of app updates.

Android builds require JDK 17+, Android SDK 36, and the Gradle wrapper. The build uses the official prebuilt ARM runtime pinned in `config/catalog.json`, so an NDK/CMake cross-build or MSYS2 installation is not needed. Set `JAVA_HOME` and `ANDROID_HOME` when using nonstandard locations. The signing configuration preserves the earlier app's sideload debug key, allowing in-place upgrades from this machine's previous APKs. This is a sideload release. For a separate release keystore, supply `BONSAI_KEYSTORE`, `BONSAI_STORE_PASSWORD`, `BONSAI_KEY_ALIAS`, and `BONSAI_KEY_PASSWORD` as environment variables; do not commit the key or passwords.

## Verification and maintenance

```powershell
.\venv\Scripts\python.exe -m unittest discover -s tests -v
node --check static\app.js
```

`config/catalog.json` is the single catalog for both platforms. Every artifact has a pinned URL, exact byte count, and SHA-256. Downloads resume into `.part` files and are published to their final names only after verification. Windows model loads verify the language and optional vision files again. Update the catalog and test both native runtimes together when upgrading models; replacing filenames in an old Bonsai runtime is insufficient.

The inference process binds only to `127.0.0.1`, requires a per-session secret, and starts with `--offline`. The desktop app checks request origins and hosts. Only inline image data is accepted by desktop inference; external image URLs are rejected. The shared UI uses bundled fonts and assets and does not request CDN resources. Arbitrary generated HTML is escaped before rendering.

See [validation notes](docs/validation.md) for measurements, checks, and device limitations. The Git repository contains source, build scripts, and the catalog; release binaries and models are kept out of Git history. Model backups are in the account's private [Bonsai-2-GGUF-backup](https://huggingface.co/alphastack1/Bonsai-2-GGUF-backup) repository. The app downloads from the pinned, public upstream sources, so its users do not need access to that private backup.

## Model credits

The model weights are PrismML releases derived from Qwen3.5, under Apache 2.0. This app does not claim to train or fine-tune them. See the original [2B](https://huggingface.co/prism-ml/Ternary-Bonsai-2-2B-gguf), [4B](https://huggingface.co/prism-ml/Ternary-Bonsai-2-4B-gguf), and [9B](https://huggingface.co/prism-ml/Ternary-Bonsai-2-9B-gguf) cards, [PrismML runtime release](https://github.com/PrismML-Eng/llama.cpp/releases/tag/prism-b10777-e3de715), and [official demo](https://github.com/PrismML-Eng/Bonsai-demo). Copies of the weights retain their published SHA-256 and license information in the Hugging Face backup.
