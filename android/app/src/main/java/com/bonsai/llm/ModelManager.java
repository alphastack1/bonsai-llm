package com.bonsai.llm;

import android.content.Context;
import android.os.StatFs;
import android.util.Log;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JavaScript bridge for the Android model catalogue and first-run downloads.
 *
 * The APK includes Bonsai 1.7B as an offline-first default. Larger Bonsai models
 * are downloaded into app-private storage. Both extraction and downloads are
 * resumable, and every completed artifact is checked against the SHA-256
 * published by Hugging Face before llama-server sees it.
 */
public class ModelManager {
    private static final String TAG = "ModelManager";
    private static final long MIB = 1024L * 1024L;
    private static final long STORAGE_HEADROOM = 512L * MIB;
    private static final long BUNDLED_STORAGE_HEADROOM = 128L * MIB;
    private static final int BUFFER_SIZE = 1024 * 1024;
    private static final int MAX_REDIRECTS = 8;
    private static final String PREFS = "bonsai_settings";
    private static final String PREF_ACTIVE_MODEL = "active_model";
    private static final String PREF_SYSTEM_PROMPT = "system_prompt";
    private static final String PREF_TEMPERATURE = "temperature";
    private static final String PREF_TOP_P = "top_p";
    private static final String PREF_TOP_K = "top_k";
    private static final String PREF_MAX_TOKENS = "max_tokens";
    private static final String BUNDLED_MODEL_KEY = "1.7B";
    private static final String DEFAULT_SYSTEM_PROMPT = "You are Bonsai, a helpful and concise "
            + "AI assistant running locally on the user's device. Use markdown formatting when "
            + "helpful. Keep responses focused and clear.";

    public static class ModelInfo {
        public final String key;
        public final String name;
        public final String file;
        public final String url;
        public final String sha256;
        public final long sizeBytes;
        public final String projectorFile;
        public final String projectorUrl;
        public final String projectorSha256;
        public final long projectorSizeBytes;
        public final String description;
        public final boolean bundled;
        public final boolean multimodal;

        public ModelInfo(String key, String name,
                         String file, String url, String sha256, long sizeBytes,
                         String projectorFile, String projectorUrl,
                         String projectorSha256, long projectorSizeBytes,
                         String description, boolean bundled, boolean multimodal) {
            this.key = key;
            this.name = name;
            this.file = file;
            this.url = url;
            this.sha256 = sha256;
            this.sizeBytes = sizeBytes;
            this.projectorFile = projectorFile;
            this.projectorUrl = projectorUrl;
            this.projectorSha256 = projectorSha256;
            this.projectorSizeBytes = projectorSizeBytes;
            this.description = description;
            this.bundled = bundled;
            this.multimodal = multimodal;
        }

        public long totalSizeBytes() {
            return sizeBytes + projectorSizeBytes;
        }

        public boolean hasProjector() {
            return projectorFile != null && !projectorFile.isEmpty()
                    && projectorSizeBytes > 0L;
        }
    }

    public static class DownloadProgress {
        public volatile int percent;
        public volatile String status; // connecting | downloading | verifying | done | error
        public volatile String detail;
        public volatile String error;
        public volatile long downloadedBytes;
        public volatile long totalBytes;

        public DownloadProgress(long totalBytes) {
            this.percent = 0;
            this.status = "connecting";
            this.detail = "Preparing download";
            this.error = null;
            this.downloadedBytes = 0;
            this.totalBytes = totalBytes;
        }
    }

    private final Context ctx;
    private final MainActivity activity;
    private final LinkedHashMap<String, ModelInfo> models = new LinkedHashMap<>();
    private final Map<String, DownloadProgress> downloads =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile String activeModelKey = BUNDLED_MODEL_KEY;

    public ModelManager(MainActivity activity) {
        this.ctx = activity.getApplicationContext();
        this.activity = activity;

        final String base17 =
                "https://huggingface.co/prism-ml/Bonsai-1.7B-gguf/resolve/main/";
        models.put("1.7B", new ModelInfo(
                "1.7B",
                "Bonsai 1.7B (included)",
                "Bonsai-1.7B-Q1_0.gguf",
                base17 + "Bonsai-1.7B-Q1_0.gguf?download=true",
                "3d7c6c90dd98717a203adb22d5eacd2581850e40aa5327e144b97766cae5f7e3",
                248_302_272L,
                null, null, null, 0L,
                "Smallest and fastest. Included in the APK for immediate offline text chat.",
                true, false
        ));

        final String base4 =
                "https://huggingface.co/prism-ml/Bonsai-4B-gguf/resolve/main/";
        models.put("4B", new ModelInfo(
                "4B",
                "Bonsai 4B (1-bit)",
                "Bonsai-4B-Q1_0.gguf",
                base4 + "Bonsai-4B-Q1_0.gguf?download=true",
                "4524b3f997f0f06444e568d1f26e2efd69effa3218c7ad3047432fb171e42168",
                572_270_624L,
                null, null, null, 0L,
                "Fast text model for older and lower-memory phones.",
                false, false
        ));

        final String base8 =
                "https://huggingface.co/prism-ml/Bonsai-8B-gguf/resolve/main/";
        models.put("8B", new ModelInfo(
                "8B",
                "Bonsai 8B (1-bit)",
                "Bonsai-8B-Q1_0.gguf",
                base8 + "Bonsai-8B-Q1_0.gguf?download=true",
                "284a335aa3fb2ced3b1b01fcb40b08aa783e3b70832767f0dd2e3fdfa134bd54",
                1_158_654_496L,
                null, null, null, 0L,
                "Higher-quality text model that still fits many mainstream phones.",
                false, false
        ));

        final String base27 =
                "https://huggingface.co/prism-ml/Bonsai-27B-gguf/resolve/main/";
        models.put("27B", new ModelInfo(
                "27B",
                "Bonsai 27B Vision (1-bit)",
                "Bonsai-27B-Q1_0.gguf",
                base27 + "Bonsai-27B-Q1_0.gguf?download=true",
                "17ef842e47450caeb8eaa3ebfbbab5d2f2278b62b79be107985fb69a2f819aa0",
                3_803_452_480L,
                "Bonsai-27B-mmproj-Q8_0.gguf",
                base27 + "Bonsai-27B-mmproj-Q8_0.gguf?download=true",
                "eb561d41a7bbeb0fcf04883c8af11078ef6cae0a66862a0b68443cfca495269d",
                629_246_880L,
                "27B-class reasoning plus vision. Downloads 4.43 GB; "
                        + "a 12 GB-class flagship phone is recommended.",
                false, true
        ));

        String saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_ACTIVE_MODEL, null);
        if (saved != null && models.containsKey(saved)) {
            activeModelKey = saved;
        } else if (modelInstalled(models.get("27B"))) {
            // Version 2 had no persisted active-model setting and only offered
            // 27B. Preserve that choice when upgrading an existing install.
            activeModelKey = "27B";
        } else {
            activeModelKey = BUNDLED_MODEL_KEY;
        }
        beginBundledInstall(models.get(BUNDLED_MODEL_KEY));
    }

    private File modelsDir() {
        File dir = new File(ctx.getFilesDir(), "models");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Could not create model directory: " + dir);
        }
        return dir;
    }

    private File modelFile(ModelInfo model) {
        return new File(modelsDir(), model.file);
    }

    private File projectorFile(ModelInfo model) {
        if (model == null || !model.hasProjector()) return null;
        return new File(modelsDir(), model.projectorFile);
    }

    private boolean artifactPresent(File file, long expectedSize) {
        return file.isFile() && file.length() == expectedSize;
    }

    private boolean modelInstalled(ModelInfo model) {
        if (model == null || !artifactPresent(modelFile(model), model.sizeBytes)) return false;
        return !model.hasProjector()
                || artifactPresent(projectorFile(model), model.projectorSizeBytes);
    }

    public String getActiveModelKey() {
        return activeModelKey;
    }

    public File getActiveModelFile() {
        ModelInfo model = models.get(activeModelKey);
        return model != null && modelInstalled(model) ? modelFile(model) : null;
    }

    public File getActiveProjectorFile() {
        ModelInfo model = models.get(activeModelKey);
        return model != null && modelInstalled(model) && model.hasProjector()
                ? projectorFile(model) : null;
    }

    public String getActiveModelName() {
        ModelInfo model = models.get(activeModelKey);
        return model == null ? "Bonsai" : model.name;
    }

    @JavascriptInterface
    public String getStatus() {
        try {
            JSONObject result = new JSONObject();
            result.put("binary_installed", true);
            result.put("active_model", activeModelKey);
            result.put("llama_running", activity.isLlamaReady());
            result.put("llama_status", activity.getLlamaState());
            result.put("llama_error", activity.getLlamaError());
            result.put("platform", "android");
            result.put("free_space_mb", availableBytes() / MIB);

            JSONObject modelsJson = new JSONObject();
            for (ModelInfo model : models.values()) {
                JSONObject info = new JSONObject();
                info.put("key", model.key);
                info.put("name", model.name);
                info.put("file", model.file);
                info.put("url", model.url);
                info.put("size_mb", (model.totalSizeBytes() + 999_999L) / 1_000_000L);
                info.put("language_size_mb", (model.sizeBytes + 999_999L) / 1_000_000L);
                info.put("vision_size_mb", (model.projectorSizeBytes + 999_999L) / 1_000_000L);
                info.put("description", model.description);
                info.put("bundled", model.bundled);
                info.put("multimodal", model.multimodal);
                info.put("installed", modelInstalled(model));
                info.put("path", modelFile(model).getAbsolutePath());
                File projector = projectorFile(model);
                info.put("projector_path", projector == null
                        ? JSONObject.NULL : projector.getAbsolutePath());
                modelsJson.put(model.key, info);
            }
            result.put("models", modelsJson);

            JSONObject downloadsJson = new JSONObject();
            for (Map.Entry<String, DownloadProgress> entry : downloads.entrySet()) {
                DownloadProgress progress = entry.getValue();
                JSONObject item = new JSONObject();
                item.put("percent", progress.percent);
                item.put("status", progress.status);
                item.put("detail", progress.detail);
                item.put("error", progress.error);
                item.put("downloaded_bytes", progress.downloadedBytes);
                item.put("total_bytes", progress.totalBytes);
                downloadsJson.put(entry.getKey(), item);
            }
            result.put("downloads", downloadsJson);
            return result.toString();
        } catch (Exception e) {
            Log.e(TAG, "getStatus failed", e);
            return errorJson(e.getMessage());
        }
    }

    @JavascriptInterface
    public String downloadModel(String key) {
        ModelInfo model = models.get(key);
        if (model == null) return errorJson("Unknown model");

        if (model.bundled) {
            if (modelInstalled(model)) return okJson("Included model is already installed");
            return beginBundledInstall(model)
                    ? okJson("Installing included model")
                    : errorJson("Included model installation is already running");
        }

        String downloadKey = "model_" + key;
        DownloadProgress existing = downloads.get(downloadKey);
        if (existing != null && ("connecting".equals(existing.status)
                || "downloading".equals(existing.status)
                || "verifying".equals(existing.status))) {
            return okJson("Already downloading");
        }

        if (modelInstalled(model)) {
            return okJson("Model is already installed");
        }

        cleanInvalidFinalFile(modelFile(model), model.sizeBytes);
        if (model.hasProjector()) {
            cleanInvalidFinalFile(projectorFile(model), model.projectorSizeBytes);
        }
        long missingBytes = missingDownloadBytes(model);
        long freeBytes = availableBytes();
        if (freeBytes < missingBytes + STORAGE_HEADROOM) {
            long requiredMb = (missingBytes + STORAGE_HEADROOM + MIB - 1) / MIB;
            long freeMb = freeBytes / MIB;
            return errorJson("Not enough free storage. Need about " + requiredMb
                    + " MB; only " + freeMb + " MB is available.");
        }

        DownloadProgress progress = new DownloadProgress(model.totalSizeBytes());
        downloads.put(downloadKey, progress);
        new Thread(() -> downloadModelArtifacts(model, progress),
                "model-download-" + key).start();
        return okJson("Download started");
    }

    private boolean beginBundledInstall(ModelInfo model) {
        if (model == null || !model.bundled || modelInstalled(model)) return false;

        String downloadKey = "model_" + model.key;
        DownloadProgress existing = downloads.get(downloadKey);
        if (existing != null && ("extracting".equals(existing.status)
                || "verifying".equals(existing.status))) {
            return false;
        }

        cleanInvalidFinalFile(modelFile(model), model.sizeBytes);
        long missingBytes = missingArtifactBytes(modelFile(model), model.sizeBytes);
        DownloadProgress progress = new DownloadProgress(model.sizeBytes);
        downloads.put(downloadKey, progress);

        long freeBytes = availableBytes();
        if (freeBytes < missingBytes + BUNDLED_STORAGE_HEADROOM) {
            progress.status = "error";
            progress.error = "Not enough free storage to install the included model";
            return true;
        }

        new Thread(() -> installBundledModel(model, progress),
                "bundled-model-install").start();
        return true;
    }

    private void installBundledModel(ModelInfo model, DownloadProgress progress) {
        File destination = modelFile(model);
        File part = partFile(destination);
        try {
            long offset = part.isFile() ? part.length() : 0L;
            if (offset < 0L || offset > model.sizeBytes) {
                if (!part.delete()) Log.w(TAG, "Could not discard invalid bundled partial");
                offset = 0L;
            }

            progress.status = "extracting";
            progress.detail = "Installing included Bonsai 1.7B";
            progress.downloadedBytes = offset;
            progress.percent = percent(offset, model.sizeBytes);

            if (offset < model.sizeBytes) {
                try (InputStream input = ctx.getAssets().open(model.file)) {
                    skipFully(input, offset);
                    try (FileOutputStream output = new FileOutputStream(part, offset > 0L)) {
                        byte[] buffer = new byte[BUFFER_SIZE];
                        long copied = offset;
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            output.write(buffer, 0, count);
                            copied += count;
                            progress.downloadedBytes = copied;
                            progress.percent = percent(copied, model.sizeBytes);
                        }
                        output.getFD().sync();
                    }
                }
            }

            if (part.length() != model.sizeBytes) {
                throw new Exception("Included model is incomplete (got " + part.length()
                        + " of " + model.sizeBytes + " bytes)");
            }

            progress.status = "verifying";
            progress.detail = "Verifying included model";
            String actualSha256 = sha256(part);
            if (!model.sha256.equalsIgnoreCase(actualSha256)) {
                if (!part.delete()) Log.w(TAG, "Could not remove corrupt bundled model");
                throw new Exception("Included model checksum mismatch");
            }

            if (destination.exists() && !destination.delete()) {
                throw new Exception("Could not replace included model");
            }
            if (!part.renameTo(destination)) {
                throw new Exception("Could not finish installing included model");
            }

            progress.downloadedBytes = model.sizeBytes;
            progress.percent = 100;
            progress.status = "done";
            progress.detail = "Included and verified";
            Log.i(TAG, model.name + " installed from APK");

            if (model.key.equals(activeModelKey)) {
                activity.runOnUiThread(() -> activity.reloadLlamaService(
                        destination.getAbsolutePath(), null, model.name));
            }
        } catch (Exception e) {
            Log.e(TAG, "Included model installation failed", e);
            progress.status = "error";
            progress.error = e.getMessage() != null
                    ? e.getMessage() : e.getClass().getSimpleName();
        }
    }

    private void skipFully(InputStream input, long bytes) throws Exception {
        long remaining = bytes;
        while (remaining > 0L) {
            long skipped = input.skip(remaining);
            if (skipped > 0L) {
                remaining -= skipped;
                continue;
            }
            if (input.read() == -1) {
                throw new Exception("Included model asset ended before resume point");
            }
            remaining--;
        }
    }

    private void persistActiveModel() {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(PREF_ACTIVE_MODEL, activeModelKey).apply();
    }

    @JavascriptInterface
    public String switchModel(String key) {
        ModelInfo model = models.get(key);
        if (model == null) return errorJson("Unknown model");
        if (!modelInstalled(model)) return errorJson("Model download is incomplete");

        activeModelKey = key;
        persistActiveModel();
        File projector = projectorFile(model);
        activity.reloadLlamaService(
                modelFile(model).getAbsolutePath(),
                projector == null ? null : projector.getAbsolutePath(),
                model.name);
        return okJson("Loading " + model.name);
    }

    @JavascriptInterface
    public String getEngineSettings() {
        try {
            int threadsPref = activity.getThreadsPref();
            int ctxPref = activity.getCtxSizePref();
            LlamaService service = activity.getLlamaService();
            int autoThreads = service != null ? service.getAutoThreads() : Math.max(4,
                    Runtime.getRuntime().availableProcessors() - 2);
            int cores = Runtime.getRuntime().availableProcessors();
            android.content.SharedPreferences preferences =
                    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return new JSONObject()
                    .put("threads", threadsPref)
                    .put("ctx_size", ctxPref)
                    .put("auto_threads", Math.min(autoThreads, 6))
                    .put("cpu_cores", cores)
                    .put("system_prompt", preferences.getString(
                            PREF_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT))
                    .put("temperature", preferences.getFloat(PREF_TEMPERATURE, 0.7f))
                    .put("top_p", preferences.getFloat(PREF_TOP_P, 0.95f))
                    .put("top_k", preferences.getInt(PREF_TOP_K, 20))
                    .put("max_tokens", preferences.getInt(PREF_MAX_TOKENS, 512))
                    .toString();
        } catch (Exception e) {
            return errorJson(e.getMessage());
        }
    }

    @JavascriptInterface
    public String applyEngineSettings(int threads, int ctxSize) {
        if (threads < 0) threads = 0;
        if (ctxSize < 1024) ctxSize = 1024;
        activity.applyEngineSettings(threads, ctxSize);
        return okJson("Engine settings applied");
    }

    @JavascriptInterface
    public String applyAdvancedSettings(int threads, int ctxSize, String systemPrompt,
                                        double temperature, double topP,
                                        int topK, int maxTokens) {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        threads = Math.max(0, Math.min(cores, threads));
        ctxSize = Math.max(1024, Math.min(32_768, ctxSize));
        temperature = Math.max(0.0, Math.min(2.0, temperature));
        topP = Math.max(0.05, Math.min(1.0, topP));
        topK = Math.max(0, Math.min(200, topK));
        maxTokens = Math.max(64, Math.min(4096, maxTokens));
        if (systemPrompt == null || systemPrompt.trim().isEmpty()) {
            systemPrompt = DEFAULT_SYSTEM_PROMPT;
        }
        if (systemPrompt.length() > 4000) {
            systemPrompt = systemPrompt.substring(0, 4000);
        }

        boolean engineChanged = threads != activity.getThreadsPref()
                || ctxSize != activity.getCtxSizePref();
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PREF_SYSTEM_PROMPT, systemPrompt)
                .putFloat(PREF_TEMPERATURE, (float) temperature)
                .putFloat(PREF_TOP_P, (float) topP)
                .putInt(PREF_TOP_K, topK)
                .putInt(PREF_MAX_TOKENS, maxTokens)
                .apply();

        if (engineChanged) activity.applyEngineSettings(threads, ctxSize);
        return okJson(engineChanged
                ? "Settings saved; reloading the model"
                : "Settings saved");
    }

    @JavascriptInterface
    public String deleteModel(String key) {
        ModelInfo model = models.get(key);
        if (model == null) return errorJson("Unknown model");
        if (model.bundled) return errorJson("The included offline model cannot be deleted");

        DownloadProgress progress = downloads.get("model_" + key);
        if (progress != null && ("connecting".equals(progress.status)
                || "downloading".equals(progress.status)
                || "verifying".equals(progress.status))) {
            return errorJson("Cannot delete a model while it is downloading");
        }

        String llamaState = activity.getLlamaState();
        if (key.equals(activeModelKey)
                && ("loading".equals(llamaState) || "ready".equals(llamaState))) {
            return errorJson("Cannot delete the active model while it is in use");
        }

        boolean deleted = deleteIfPresent(modelFile(model))
                & deleteIfPresent(partFile(modelFile(model)));
        if (model.hasProjector()) {
            deleted = deleteIfPresent(projectorFile(model))
                    & deleteIfPresent(partFile(projectorFile(model)))
                    & deleted;
        }
        downloads.remove("model_" + key);
        return deleted ? okJson("Deleted") : errorJson("Failed to delete one or more files");
    }

    private void downloadModelArtifacts(ModelInfo model, DownloadProgress progress) {
        try {
            long completeBeforeLanguage = artifactPresent(modelFile(model), model.sizeBytes)
                    ? model.sizeBytes : 0L;
            downloadArtifact(
                    model.hasProjector() ? "Language model (1/2)" : "Language model",
                    model.url, modelFile(model),
                    model.sha256, model.sizeBytes, 0L, model.totalSizeBytes(), progress);

            if (model.hasProjector()) {
                downloadArtifact(
                        "Vision projector (2/2)", model.projectorUrl, projectorFile(model),
                        model.projectorSha256, model.projectorSizeBytes,
                        Math.max(model.sizeBytes, completeBeforeLanguage),
                        model.totalSizeBytes(), progress);
            }

            progress.downloadedBytes = model.totalSizeBytes();
            progress.percent = 100;
            progress.status = "done";
            progress.detail = "Installed and verified";
            activeModelKey = model.key;
            persistActiveModel();
            Log.i(TAG, model.name + " download complete");

            File projector = projectorFile(model);
            activity.runOnUiThread(() -> activity.reloadLlamaService(
                    modelFile(model).getAbsolutePath(),
                    projector == null ? null : projector.getAbsolutePath(), model.name));
        } catch (Exception e) {
            Log.e(TAG, "Download failed", e);
            progress.status = "error";
            progress.error = e.getMessage() != null
                    ? e.getMessage() : e.getClass().getSimpleName();
        }
    }

    private void downloadArtifact(String label, String urlString, File destination,
                                  String expectedSha256, long expectedSize,
                                  long completedBefore, long totalSize,
                                  DownloadProgress progress) throws Exception {
        if (artifactPresent(destination, expectedSize)) {
            progress.downloadedBytes = completedBefore + expectedSize;
            progress.percent = percent(progress.downloadedBytes, totalSize);
            return;
        }

        File part = partFile(destination);
        long offset = part.isFile() ? part.length() : 0L;
        if (offset < 0 || offset > expectedSize) {
            if (!part.delete()) Log.w(TAG, "Could not discard oversized partial file");
            offset = 0L;
        }

        progress.status = "connecting";
        progress.detail = label;
        progress.downloadedBytes = completedBefore + offset;
        progress.percent = percent(progress.downloadedBytes, totalSize);

        // A process can be killed after the final byte is flushed but before
        // verification/rename. In that case verify the complete .part directly
        // instead of issuing an unsatisfiable "bytes=<size>-" request.
        if (offset < expectedSize) {
            HttpURLConnection connection = openFollowingRedirects(urlString, offset);
            try {
                int code = connection.getResponseCode();
                boolean append = offset > 0 && code == HttpURLConnection.HTTP_PARTIAL;
                if (offset > 0 && code == HttpURLConnection.HTTP_OK) {
                    offset = 0L;
                    append = false;
                } else if (code < 200 || code >= 300) {
                    throw new Exception("HTTP " + code + " while downloading " + label);
                }

                progress.status = "downloading";
                try (InputStream input = connection.getInputStream();
                     FileOutputStream output = new FileOutputStream(part, append)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    long received = offset;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                        received += count;
                        progress.downloadedBytes = completedBefore + received;
                        progress.percent = percent(progress.downloadedBytes, totalSize);
                    }
                    output.getFD().sync();
                }
            } finally {
                connection.disconnect();
            }
        }

        if (part.length() != expectedSize) {
            throw new Exception(label + " is incomplete (got " + part.length()
                    + " of " + expectedSize + " bytes). Tap retry to resume.");
        }

        progress.status = "verifying";
        progress.detail = "Verifying " + label;
        String actualSha256 = sha256(part);
        if (!expectedSha256.equalsIgnoreCase(actualSha256)) {
            if (!part.delete()) Log.w(TAG, "Could not delete failed checksum file");
            throw new Exception(label + " failed its SHA-256 integrity check");
        }

        if (destination.exists() && !destination.delete()) {
            throw new Exception("Could not replace " + destination.getName());
        }
        if (!part.renameTo(destination)) {
            throw new Exception("Could not finalize " + destination.getName());
        }
        progress.downloadedBytes = completedBefore + expectedSize;
        progress.percent = percent(progress.downloadedBytes, totalSize);
    }

    private HttpURLConnection openFollowingRedirects(String urlString, long offset)
            throws Exception {
        URL current = new URL(urlString);
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(60_000);
            connection.setRequestProperty("User-Agent", "Bonsai-Android/2.0");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (offset > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");

            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER
                    || code == 307 || code == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) throw new Exception("Download redirect had no location");
                current = new URL(current, location);
                continue;
            }
            return connection;
        }
        throw new Exception("Too many download redirects");
    }

    private long availableBytes() {
        return new StatFs(modelsDir().getAbsolutePath()).getAvailableBytes();
    }

    private long missingDownloadBytes(ModelInfo model) {
        long missing = missingArtifactBytes(modelFile(model), model.sizeBytes);
        if (model.hasProjector()) {
            missing += missingArtifactBytes(projectorFile(model), model.projectorSizeBytes);
        }
        return missing;
    }

    private long missingArtifactBytes(File destination, long expectedSize) {
        if (artifactPresent(destination, expectedSize)) return 0L;
        File part = partFile(destination);
        long partial = part.isFile() ? Math.min(part.length(), expectedSize) : 0L;
        return expectedSize - partial;
    }

    private void cleanInvalidFinalFile(File file, long expectedSize) {
        if (file.exists() && !artifactPresent(file, expectedSize) && !file.delete()) {
            Log.w(TAG, "Could not remove invalid file: " + file);
        }
    }

    private File partFile(File destination) {
        return new File(destination.getAbsolutePath() + ".part");
    }

    private boolean deleteIfPresent(File file) {
        return !file.exists() || file.delete();
    }

    private int percent(long current, long total) {
        if (total <= 0) return 0;
        return (int) Math.max(0, Math.min(100, (current * 100L) / total));
    }

    private String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        }
        return hex.toString();
    }

    private String okJson(String message) {
        try {
            return new JSONObject().put("ok", true).put("message", message).toString();
        } catch (Exception e) {
            return "{\"ok\":true}";
        }
    }

    private String errorJson(String message) {
        try {
            return new JSONObject().put("ok", false).put("error", message).toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"Unknown error\"}";
        }
    }
}
