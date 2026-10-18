# Bonsai 2 validation — October 18, 2026

Runtime: `prism-b10777-e3de715` (`e3de715222b2667c54a267441159a5729f11155e`). Model revisions, original URLs, sizes, and SHA-256 are recorded in `config/catalog.json`.

## PC measurements

Measured on this machine: Intel Core i5-12500H, approximately 16 GB system RAM, NVIDIA RTX 4050 Laptop GPU with 6 GB VRAM, driver 610.91, Windows 11. The CUDA 12.4 runtime used a 16,384-token context, 6 CPU threads, one inference slot, q8_0 K/V caches, Flash Attention, and thinking off.

| Model | Approximate generated tokens/second | Generated tokens |
|---|---:|---:|
| Bonsai 2 2B PQ2_0 | 84 | 61 |
| Bonsai 2 4B PQ2_0 | 84 | 108 |
| Bonsai 2 9B PQ2_0 | 54 | 111 |

Prompt: “Write a short paragraph about why trees are important.” These are single short runs reported by the engine, not averaged benchmark-suite results. The 2B run reused part of the preceding prompt cache. Prompt processing, loading, thinking and image processing are additional time. These numbers must not be presented as phone results or as a controlled speed comparison with the old app. A 9B arithmetic smoke test also returned `17 × 23 = 391` through the shared UI.

## Automated checks

The Python regression suite covers verified offline reuse, interrupted-download resume, servers ignoring Range, invalid Content-Range, corrupt downloads, rejected external image URLs, vision gating, mode-specific sampling, malformed settings and API bodies, cross-origin requests, host validation, and static path traversal. JavaScript receives a syntax check. Android release builds include Gradle's release lint checks.

Browser QA uses the user's shared Chrome profile, one run-owned tab at a time. It checks desktop and 390-pixel phone layouts, console errors, local-only UI requests, chat streaming, and saved-chat restoration. No user-owned browser tabs or stored sessions are cleared.

All six mirrored Hugging Face model files were checked against the catalog's exact byte sizes and LFS SHA-256 values after upload. The backup is private, retaining the existing account's model-backup convention.

Packaged EXE and APK checks are recorded in the release notes after the final builds. An emulator can exercise Android packaging, the native service and offline startup; it cannot establish speed, thermals, memory limits, or reliability on the user's physical phone. Physical phone validation remains necessary for those device-specific conclusions.

The packaged Windows EXE was launched with an empty data directory: it extracted and verified the included 2B model, loaded it with CUDA, and answered a local arithmetic request. Switching that packaged app to CPU also loaded successfully and returned a correct arithmetic answer. Closing the native window stopped its HTTP server and inference child process.

The signed APK installed on the available Android 36 x86_64 emulator in airplane mode. Its ARM model asset matched the catalog hash and extracted locally. The shared UI was also checked without secure-context-only UUID generation. A direct browser test exposed cross-origin authentication/preflight restrictions, so Android now intercepts its bundled UI at the inference server's exact loopback origin (`http://127.0.0.1:8080/assets/`). Authenticated streaming was checked using this same-origin protocol against the real local engine. This avoids mixed content, private-network preflights and cross-origin Authorization-header restrictions.

The emulator supports translated ARM app libraries but cannot execute this ARM server subprocess and its dependency chain: its translation runner reports EM_AARCH64 versus EM_X86_64. Therefore **Android text/vision inference is not claimed as tested on a compatible ARM device**. A physical ARM64 phone test is still required. The signed APK and native dependency layout are intended for real ARM64 Android phones.
