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
 * The APK intentionally contains the runtime and UI only. Bonsai 27B is larger
 * than the practical APK distribution limit, so its language weights and
 * optional vision projector are downloaded into app-private storage. Downloads
 * are resumable and every completed artifact is checked against the SHA-256
 * published by Hugging Face before llama-server sees it.
 */
public class ModelManager {
    private static final String TAG = "ModelManager";
    private static final long MIB = 1024L * 1024L;
    private static final long STORAGE_HEADROOM = 512L * MIB;
    private static final int BUFFER_SIZE = 1024 * 1024;
    private static final int MAX_REDIRECTS = 8;

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

        public ModelInfo(String key, String name,
                         String file, String url, String sha256, long sizeBytes,
                         String projectorFile, String projectorUrl,
                         String projectorSha256, long projectorSizeBytes,
                         String description) {
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
        }

        public long totalSizeBytes() {
            return sizeBytes + projectorSizeBytes;
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
    private volatile String activeModelKey = "27B";

    public ModelManager(MainActivity activity) {
        this.ctx = activity.getApplicationContext();
        this.activity = activity;

        final String base =
                "https://huggingface.co/prism-ml/Bonsai-27B-gguf/resolve/main/";
        models.put("27B", new ModelInfo(
                "27B",
                "Bonsai 27B (1-bit)",
                "Bonsai-27B-Q1_0.gguf",
                base + "Bonsai-27B-Q1_0.gguf?download=true",
                "17ef842e47450caeb8eaa3ebfbbab5d2f2278b62b79be107985fb69a2f819aa0",
                3_803_452_480L,
                "Bonsai-27B-mmproj-Q8_0.gguf",
                base + "Bonsai-27B-mmproj-Q8_0.gguf?download=true",
                "eb561d41a7bbeb0fcf04883c8af11078ef6cae0a66862a0b68443cfca495269d",
                629_246_880L,
                "27B-class reasoning plus vision. Downloads 4.43 GB; "
                        + "a 12 GB-class flagship phone is recommended."
        ));
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
        return new File(modelsDir(), model.projectorFile);
    }

    private boolean artifactPresent(File file, long expectedSize) {
        return file.isFile() && file.length() == expectedSize;
    }

    private boolean modelInstalled(ModelInfo model) {
        return artifactPresent(modelFile(model), model.sizeBytes)
                && artifactPresent(projectorFile(model), model.projectorSizeBytes);
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
        return model != null && modelInstalled(model) ? projectorFile(model) : null;
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
                info.put("bundled", false);
                info.put("multimodal", true);
                info.put("installed", modelInstalled(model));
                info.put("path", modelFile(model).getAbsolutePath());
                info.put("projector_path", projectorFile(model).getAbsolutePath());
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
        cleanInvalidFinalFile(projectorFile(model), model.projectorSizeBytes);
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

    @JavascriptInterface
    public String switchModel(String key) {
        ModelInfo model = models.get(key);
        if (model == null) return errorJson("Unknown model");
        if (!modelInstalled(model)) return errorJson("Model download is incomplete");

        activeModelKey = key;
        activity.reloadLlamaService(
                modelFile(model).getAbsolutePath(),
                projectorFile(model).getAbsolutePath(),
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
            return new JSONObject()
                    .put("threads", threadsPref)
                    .put("ctx_size", ctxPref)
                    .put("auto_threads", Math.min(autoThreads, 6))
                    .put("cpu_cores", cores)
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
    public String deleteModel(String key) {
        ModelInfo model = models.get(key);
        if (model == null) return errorJson("Unknown model");

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
                & deleteIfPresent(projectorFile(model))
                & deleteIfPresent(partFile(modelFile(model)))
                & deleteIfPresent(partFile(projectorFile(model)));
        downloads.remove("model_" + key);
        return deleted ? okJson("Deleted") : errorJson("Failed to delete one or more files");
    }

    private void downloadModelArtifacts(ModelInfo model, DownloadProgress progress) {
        try {
            long completeBeforeLanguage = artifactPresent(modelFile(model), model.sizeBytes)
                    ? model.sizeBytes : 0L;
            downloadArtifact(
                    "Language model (1/2)", model.url, modelFile(model),
                    model.sha256, model.sizeBytes, 0L, model.totalSizeBytes(), progress);

            downloadArtifact(
                    "Vision projector (2/2)", model.projectorUrl, projectorFile(model),
                    model.projectorSha256, model.projectorSizeBytes,
                    Math.max(model.sizeBytes, completeBeforeLanguage),
                    model.totalSizeBytes(), progress);

            progress.downloadedBytes = model.totalSizeBytes();
            progress.percent = 100;
            progress.status = "done";
            progress.detail = "Installed and verified";
            Log.i(TAG, "Bonsai 27B download complete");

            activity.runOnUiThread(() -> activity.reloadLlamaService(
                    modelFile(model).getAbsolutePath(),
                    projectorFile(model).getAbsolutePath(),
                    model.name));
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
        return missingArtifactBytes(modelFile(model), model.sizeBytes)
                + missingArtifactBytes(projectorFile(model), model.projectorSizeBytes);
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
