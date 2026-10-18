package com.bonsai.llm;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.JavascriptInterface;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The Android half of the shared local API. No remote inference endpoints exist. */
public final class ModelManager {
    private final MainActivity activity;
    private final JSONObject catalog;
    private final JSONObject models;
    private final JSONObject jobs = new JSONObject();
    private final SharedPreferences prefs;
    private final ExecutorService downloads = Executors.newSingleThreadExecutor();
    private final File directory;
    private volatile String selected;
    private volatile boolean vision;
    private volatile boolean closed;

    public ModelManager(MainActivity activity) {
        this.activity = activity;
        prefs = activity.getSharedPreferences("bonsai2_models", Context.MODE_PRIVATE);
        directory = new File(activity.getFilesDir(), "models");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Could not create model storage");
        try (InputStream source = activity.getAssets().open("catalog.json")) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192]; int count;
            while ((count = source.read(chunk)) != -1) buffer.write(chunk, 0, count);
            catalog = new JSONObject(buffer.toString(StandardCharsets.UTF_8.name()));
            models = catalog.getJSONObject("models");
        } catch (Exception error) { throw new IllegalStateException("The model catalog is missing", error); }
        selected = prefs.getString("active", "2B");
        if (!models.has(selected) || !installed(artifact(selected, false))) selected = "2B";
        vision = prefs.getBoolean("vision", false) && installed(artifact(selected, true));
        if (!installed(artifact("2B", false))) startDownload("2B", false);
    }

    private JSONObject artifact(String key, boolean image) {
        try { return models.getJSONObject(key).getJSONObject(image ? "projector" : "model"); }
        catch (Exception error) { throw new IllegalArgumentException("Unknown model"); }
    }
    private File file(JSONObject artifact) { return new File(directory, artifact.optString("file")); }
    private boolean installed(JSONObject artifact) { return file(artifact).isFile() && file(artifact).length() == artifact.optLong("bytes"); }
    public File getActiveModelFile() { JSONObject a = artifact(selected, false); return installed(a) ? file(a) : null; }
    public File getActiveProjectorFile() { JSONObject a = artifact(selected, true); return vision && installed(a) ? file(a) : null; }
    public String getActiveModelName() { return models.optJSONObject(selected).optString("name"); }

    @JavascriptInterface
    public String request(String path, String bodyJSON) {
        try {
            JSONObject body = new JSONObject(bodyJSON);
            switch (path) {
                case "/api/status": return status().toString();
                case "/api/model/download":
                    return new JSONObject().put("started", startDownload(body.getString("key"), body.optBoolean("vision"))).toString();
                case "/api/model/load":
                    loadModel(body.getString("key"), body.optBoolean("vision")); break;
                case "/api/model/delete":
                    deleteModel(body.getString("key"), body.optBoolean("vision")); break;
                case "/api/settings":
                    int threads = body.getInt("threads"), context = body.getInt("ctx_size");
                    if (threads < 0 || threads > 32) throw new IllegalArgumentException("Threads must be between 0 and 32");
                    if (context != 4096 && context != 8192 && context != 16384 && context != 32768)
                        throw new IllegalArgumentException("Unsupported context length");
                    if ("loading".equals(activity.getLlamaState())) throw new IllegalArgumentException("Wait for the model to finish loading");
                    activity.runOnUiThread(() -> activity.applyEngineSettings(threads, context)); break;
                case "/api/clipboard":
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Bonsai answer", body.getString("text"))); break;
                default: throw new IllegalArgumentException("Unknown local action");
            }
            return new JSONObject().put("ok", true).toString();
        } catch (Exception error) {
            try { return new JSONObject().put("error", error.getMessage() == null ? "Local action failed" : error.getMessage()).toString(); }
            catch (Exception ignored) { return "{\"error\":\"Local action failed\"}"; }
        }
    }

    private JSONObject status() throws Exception {
        JSONObject list = new JSONObject();
        for (Iterator<String> it = models.keys(); it.hasNext();) {
            String key = it.next(); JSONObject model = models.getJSONObject(key);
            list.put(key, new JSONObject().put("key", key).put("name", model.getString("name"))
                    .put("label", model.getString("label")).put("description", model.getString("description"))
                    .put("bundled", model.getBoolean("bundled")).put("bytes", artifact(key, false).getLong("bytes"))
                    .put("vision_bytes", artifact(key, true).getLong("bytes"))
                    .put("installed", installed(artifact(key, false))).put("vision_installed", installed(artifact(key, true))));
        }
        JSONObject progress;
        synchronized (jobs) { progress = new JSONObject(jobs.toString()); }
        String engineState = activity.getLlamaState();
        if (selected.equals("2B") && !installed(artifact("2B", false)) && isBusy("model_2B")) engineState = "installing";
        return new JSONObject().put("version", "3.0.0").put("platform", "android")
                .put("ready", activity.isLlamaReady()).put("state", engineState)
                .put("error", activity.getLlamaError() == null ? JSONObject.NULL : activity.getLlamaError())
                .put("active_model", selected).put("vision", vision).put("backend", "cpu")
                .put("engine_ready", true).put("runtime", catalog.getJSONObject("runtime").getString("tag"))
                .put("models", list).put("downloads", progress)
                .put("settings", new JSONObject().put("threads", activity.getThreadsPref())
                        .put("ctx_size", activity.getCtxSizePref()).put("backend", "cpu"))
                .put("inference", new JSONObject().put("url", "http://127.0.0.1:8080").put("token", activity.getApiToken()));
    }

    private boolean isBusy(String job) {
        synchronized (jobs) {
            JSONObject value = jobs.optJSONObject(job);
            if (value == null) return false;
            String s = value.optString("status");
            return !s.equals("done") && !s.equals("error");
        }
    }
    private void progress(String job, String status, long done, long total, String error) {
        synchronized (jobs) {
            try { jobs.put(job, new JSONObject().put("status", status).put("percent", total == 0 ? 0 : Math.min(100, done * 100 / total))
                    .put("downloaded_bytes", done).put("total_bytes", total).put("error", error == null ? JSONObject.NULL : error)); }
            catch (Exception ignored) {}
        }
    }

    private synchronized boolean startDownload(String key, boolean image) {
        if (closed) return false;
        JSONObject artifact = artifact(key, image);
        String job = (image ? "vision_" : "model_") + key;
        if (isBusy(job)) return false;
        progress(job, "queued", 0, artifact.optLong("bytes"), null);
        downloads.execute(() -> {
            try {
                if (!image && key.equals("2B")) copyBundled(artifact, job);
                else download(artifact, job);
                progress(job, "done", artifact.optLong("bytes"), artifact.optLong("bytes"), null);
                if (!closed && !image && key.equals(selected) && !activity.isLlamaReady() && !"loading".equals(activity.getLlamaState()))
                    activity.runOnUiThread(() -> startActive());
            } catch (Exception error) {
                progress(job, "error", 0, artifact.optLong("bytes"), error.getMessage());
            }
        });
        return true;
    }

    private void copyBundled(JSONObject artifact, String job) throws Exception {
        File destination = file(artifact), part = new File(destination + ".part");
        long total = artifact.getLong("bytes");
        if (destination.isFile() && destination.length() == total && hash(destination).equals(artifact.getString("sha256"))) return;
        if (part.length() > total && !part.delete()) throw new IOException("Could not reset partial model");
        long offset = part.isFile() ? part.length() : 0;
        progress(job, "extracting", offset, total, null);
        try (InputStream input = activity.getAssets().open(artifact.getString("file"));
             OutputStream output = new FileOutputStream(part, offset > 0)) {
            long skipped = 0;
            while (skipped < offset) {
                long n = input.skip(offset - skipped);
                if (n <= 0) { if (input.read() == -1) throw new IOException("Included model is incomplete"); n = 1; }
                skipped += n;
            }
            copy(input, output, job, offset, total, "extracting");
        }
        finish(artifact, part, destination, job);
    }

    private void download(JSONObject artifact, String job) throws Exception {
        File destination = file(artifact), part = new File(destination + ".part");
        long total = artifact.getLong("bytes");
        if (destination.isFile() && destination.length() == total && hash(destination).equals(artifact.getString("sha256"))) return;
        if (part.length() > total && !part.delete()) throw new IOException("Could not reset partial download");
        long offset = part.isFile() ? part.length() : 0;
        if (offset < total) {
            progress(job, "connecting", offset, total, null);
            HttpURLConnection connection = (HttpURLConnection) new URL(artifact.getString("url")).openConnection();
            connection.setConnectTimeout(20000); connection.setReadTimeout(60000);
            if (offset > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");
            try {
                int code = connection.getResponseCode();
                if (code == 200) offset = 0;
                else if (code == 206) {
                    String range = connection.getHeaderField("Content-Range");
                    if (range == null || !range.startsWith("bytes " + offset + "-") || !range.endsWith("/" + total))
                        throw new IOException("Invalid download range. Please retry.");
                } else throw new IOException("Download failed (HTTP " + code + "). Tap Download to resume.");
                try (InputStream input = connection.getInputStream(); OutputStream output = new FileOutputStream(part, offset > 0)) {
                    copy(input, output, job, offset, total, "downloading");
                }
            } finally { connection.disconnect(); }
        }
        finish(artifact, part, destination, job);
    }

    private void copy(InputStream source, OutputStream output, String job, long done, long total, String phase) throws Exception {
        byte[] buffer = new byte[1024 * 1024]; int count;
        while ((count = source.read(buffer)) != -1) {
            if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Download paused. Reopen Bonsai to resume.");
            done += count;
            if (done > total) throw new IOException("Model file is larger than expected");
            output.write(buffer, 0, count);
            progress(job, phase, done, total, null);
        }
    }
    private void finish(JSONObject artifact, File part, File destination, String job) throws Exception {
        progress(job, "verifying", part.length(), artifact.getLong("bytes"), null);
        if (part.length() != artifact.getLong("bytes")) throw new IOException("Download interrupted. Tap Download to resume.");
        if (!hash(part).equals(artifact.getString("sha256"))) {
            if (!part.delete()) throw new IOException("Could not remove a damaged download");
            throw new IOException("Model checksum did not match. Please download it again.");
        }
        java.nio.file.Files.move(part.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    private String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[1024 * 1024]; int count;
        try (InputStream input = new FileInputStream(file)) { while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count); }
        StringBuilder out = new StringBuilder(); for (byte b : digest.digest()) out.append(String.format("%02x", b & 255));
        return out.toString();
    }
    private synchronized void loadModel(String key, boolean image) throws Exception {
        if (!installed(artifact(key, false)) || (image && !installed(artifact(key, true)))) throw new IllegalArgumentException("Download this model and its image support first");
        if ("loading".equals(activity.getLlamaState())) throw new IllegalArgumentException("Wait for the current model to finish loading");
        selected = key; vision = image;
        prefs.edit().putString("active", key).putBoolean("vision", image).apply();
        activity.runOnUiThread(this::startActive);
    }
    private void startActive() {
        if (closed || activity.isFinishing() || activity.isDestroyed()) return;
        File model = getActiveModelFile(), projector = getActiveProjectorFile();
        if (model != null) activity.reloadLlamaService(model.getAbsolutePath(), projector == null ? null : projector.getAbsolutePath(), getActiveModelName());
    }
    private synchronized void deleteModel(String key, boolean image) throws Exception {
        if (isBusy("model_" + key) || isBusy("vision_" + key)) throw new IllegalArgumentException("Wait for the download to finish");
        if (key.equals(selected) && (activity.isLlamaReady() || "loading".equals(activity.getLlamaState()))) throw new IllegalArgumentException("Switch to another model before removing this one");
        File target = file(artifact(key, image));
        if (target.exists() && !target.delete()) throw new IOException("Could not remove the model");
        File part = new File(target + ".part");
        if (part.exists() && !part.delete()) throw new IOException("Could not remove the partial download");
    }
    public void close() { closed = true; downloads.shutdownNow(); }
}
